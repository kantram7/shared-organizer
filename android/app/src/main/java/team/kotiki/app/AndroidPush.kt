package team.kotiki.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal data class PushBinding(val authUid:String,val canonicalUid:String,val spaceId:String,val viewer:Person)
internal data class PushNotice(val eventId:String,val authUid:String,val spaceId:String,val kind:String,
    val recordId:String,val chatId:String,val collectionId:String,val itemId:String,val gathering:Boolean=false)

internal object PushPolicy {
    private fun segment(value:String)=value.isNotEmpty() && value.length<=256 && '/' !in value
    fun parse(data:Map<String,String>,authUid:String,spaceId:String):PushNotice? {
        if(data["authUid"]!=authUid || data["spaceId"]!=spaceId || !segment(authUid) || !segment(spaceId))return null
        val id=data["eventId"] ?: return null
        val kind=data["kind"] ?: return null
        if(!segment(id) || kind !in listOf("invite","event","cancel","chat","refresh"))return null
        val record=data["recordId"].orEmpty();val chat=data["chatId"].orEmpty()
        val collection=data["collectionId"].orEmpty();val item=data["itemId"].orEmpty()
        if(record.isNotEmpty() && !segment(record))return null
        if(kind!="chat"){
            if(!record.startsWith("entry_") || record.length<=6 || chat.isNotEmpty() || collection.isNotEmpty() || item.isNotEmpty())return null
        }else{
            val valid=when {
                chat=="general"->record.isEmpty() && collection.isEmpty() && item.isEmpty()
                chat.startsWith("entry_")->segment(chat) && record==chat && record.length>6 && collection.isEmpty() && (item.isEmpty() || item==record.removePrefix("entry_"))
                else->segment(collection) && segment(item) && chat=="plan_${collection}_${item}" && record=="plan_${collection}"
            }
            if(!valid)return null
        }
        return PushNotice(id,authUid,spaceId,kind,record,chat,collection,item,kind!="chat" && data["gathering"]=="true")
    }
}

/** Delivery is triggered by the server's scheduled queue poll. No external HTTP or ID-token transfer. */
object AndroidPush {
    const val CHANNEL="shared_organizer_updates"
    var registrationStatus by mutableStateOf("Устройство ещё не зарегистрировано для push");private set
    private const val PREFS="android_push"
    private const val JOB_SYNC=19208
    private fun prefs(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    internal fun ensureFirebase(context:Context):Boolean=runCatching {
        if(FirebaseApp.getApps(context).isEmpty() && BuildConfig.AGENT_TEST){
            val config=JSONObject(context.assets.open("agent-firebase.json").bufferedReader().use{it.readText()})
            val options=FirebaseOptions.Builder().setProjectId(config.getString("projectId"))
                .setApplicationId(config.getString("applicationId")).setApiKey(config.getString("apiKey"))
            config.optString("messagingSenderId").takeIf{it.isNotBlank()}?.let(options::setGcmSenderId)
            FirebaseApp.initializeApp(context,options.build())
        }
        FirebaseApp.getApps(context).isNotEmpty()
    }.getOrDefault(false)
    @Synchronized private fun installation(context:Context):String {
        val file=File(context.noBackupFilesDir,"push-installation-id")
        if(file.exists())file.readText().trim().takeIf{runCatching{UUID.fromString(it)}.isSuccess}?.let{return it}
        return UUID.randomUUID().toString().also{file.writeText(it)}
    }
    internal fun binding(context:Context):PushBinding? {
        val p=prefs(context);val auth=p.getString("authUid",null) ?: return null
        return runCatching{PushBinding(auth,p.getString("canonicalUid",null)!!,p.getString("spaceId",null)!!,Person.valueOf(p.getString("viewer",null)!!))}.getOrNull()
    }
    internal fun active(context:Context,profile:PushBinding):Boolean = ensureFirebase(context) &&
        FirebaseAuth.getInstance().currentUser?.uid==profile.authUid && binding(context)==profile
    fun register(context:Context,session:CloudSession){
        val app=context.applicationContext
        if(!ensureFirebase(app) || FirebaseAuth.getInstance().currentUser?.uid!=session.authUid)return
        prefs(app).edit().putString("authUid",session.authUid).putString("canonicalUid",session.uid)
            .putString("spaceId",session.spaceId).putString("viewer",session.viewer.name).apply()
        createChannel(app)
        registrationStatus="Получаем push-токен устройства…"
        val profile=binding(app)!!
        FirebaseMessaging.getInstance().token.addOnSuccessListener{registerToken(app,it)}
            .addOnFailureListener{if(active(app,profile))registrationStatus="Не удалось получить push-токен. Проверь сервисы Google и интернет."}
        refreshReminders(app)
    }
    internal fun registerToken(context:Context,token:String){
        val profile=binding(context) ?: return
        if(!active(context,profile) || token.isBlank())return
        val id=profile.authUid+"_"+installation(context)
        registrationStatus="Регистрируем устройство для push…"
        FirebaseFirestore.getInstance().collection("spaces").document(profile.spaceId).collection("devices").document(id)
            .set(mapOf("authUid" to profile.authUid,"canonicalUid" to profile.canonicalUid,"token" to token,"updatedAt" to FieldValue.serverTimestamp()))
            .addOnCompleteListener{task->if(active(context,profile))registrationStatus=if(task.isSuccessful)"Устройство зарегистрировано для push" else "Не удалось зарегистрировать устройство для push"}
    }
    /** Remove the server device before signOut. Offline removal remains pending; errors reach the logout UI. */
    fun unregister(context:Context,session:CloudSession,onComplete:(Boolean)->Unit){
        val app=context.applicationContext;val profile=binding(app)
        if(profile==null || profile.authUid!=session.authUid || !ensureFirebase(app)){onComplete(true);return}
        val id=profile.authUid+"_"+installation(app)
        FirebaseFirestore.getInstance().collection("spaces").document(profile.spaceId).collection("devices").document(id).delete()
            .addOnCompleteListener{result->
                if(result.isSuccessful){
                    registrationStatus="Устройство не зарегистрировано для push"
                    if(binding(app)==profile)prefs(app).edit().remove("authUid").remove("canonicalUid").remove("spaceId").remove("viewer").apply()
                    app.getSystemService(JobScheduler::class.java).cancel(JOB_SYNC)
                    val manager=app.getSystemService(NotificationManager::class.java)
                    manager.activeNotifications.filter{it.tag?.startsWith("push-${profile.authUid}-")==true}.forEach{manager.cancel(it.tag,it.id)}
                    LocalReminders.clearProfile(app,profile.authUid)
                    if(FirebaseAuth.getInstance().currentUser?.uid==profile.authUid)FirebaseMessaging.getInstance().deleteToken()
                }
                onComplete(result.isSuccessful)
            }
    }
    /** Reserved call site: the Worker polls pushQueue on its cron schedule. No network request is made. */
    @Suppress("UNUSED_PARAMETER") fun wake(context:Context) = Unit
    internal fun refreshReminders(context:Context){
        val profile=binding(context) ?: return
        if(!active(context,profile))return
        val extras=PersistableBundle().apply{putString("authUid",profile.authUid);putString("spaceId",profile.spaceId)}
        val job=JobInfo.Builder(JOB_SYNC,ComponentName(context,PushSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }
    internal fun perform(context:Context,params:JobParameters):Boolean {
        val profile=binding(context) ?: return true
        if(profile.authUid!=params.extras.getString("authUid") || profile.spaceId!=params.extras.getString("spaceId") || !active(context,profile))return true
        return try {
            val snapshot=Tasks.await(FirebaseFirestore.getInstance().collection("spaces").document(profile.spaceId)
                .collection("records").whereArrayContains("readers",profile.canonicalUid).get(Source.SERVER),25,TimeUnit.SECONDS)
            val entries=snapshot.documents.filter{it.getString("type")=="entry" && it.getBoolean("deleted")!=true}
                .map{document->readEntry(JSONObject(document.get("data") as Map<*,*>),emptyMap())}
            if(active(context,profile))LocalReminders.sync(context,profile.authUid,entries,profile.viewer)
            true
        }catch(_:Exception){!active(context,profile)}
    }
    private fun createChannel(context:Context){
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Чаты и совместные планы",NotificationManager.IMPORTANCE_DEFAULT).apply{
                description="Приглашения, ответы и изменения общих записей";lockscreenVisibility=NotificationCompat.VISIBILITY_PRIVATE
            })
    }
    @Synchronized internal fun receive(context:Context,data:Map<String,String>){
        val profile=binding(context) ?: return
        if(!active(context,profile))return
        val notice=PushPolicy.parse(data,profile.authUid,profile.spaceId) ?: return
        if(notice.recordId.isNotEmpty())refreshReminders(context)
        if(notice.kind=="refresh")return
        val seen=runCatching{JSONArray(prefs(context).getString("seen","[]"))}.getOrDefault(JSONArray())
        val key=notice.authUid+":"+notice.eventId
        if((0 until seen.length()).any{seen.optString(it)==key})return
        createChannel(context)
        if(!GatheringNotifications.canPost(context))return
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return
        val channel=if(notice.gathering){GatheringNotifications.createChannel(context);GatheringNotifications.CHANNEL}else CHANNEL
        val title=if(notice.gathering)when(notice.kind){"invite"->"Общий сбор · приглашение";"cancel"->"Общий сбор отменён";else->"Общий сбор · обновление"}
            else when(notice.kind){"chat"->"Котики на связи";"invite"->"Приглашение котикам";"cancel"->"Планы изменились";else->"Обновление планов"}
        val body=when(notice.kind){"chat"->"Новое сообщение в чате";"invite"->"Открой приложение, чтобы посмотреть приглашение или ответ";"cancel"->"Совместная запись отменена";else->"Изменена запись с твоим участием"}
        val intent=Intent(context,MainActivity::class.java).apply{
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action=context.packageName+".push."+notice.authUid+"."+notice.eventId
            putExtra("push_record_id",notice.recordId);putExtra("push_chat_id",notice.chatId);putExtra("push_auth_uid",notice.authUid)
            putExtra("push_collection_id",notice.collectionId);putExtra("push_item_id",notice.itemId)
        }
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_meeting)
            .setContentTitle("Kotiki team").setContentText("Есть обновления. Открой приложение.").build()
        val notification=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_meeting)
            .setContentTitle(title).setContentText(body).setContentIntent(pending).setAutoCancel(true)
            .setCategory(if(notice.kind=="chat")NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        if(!active(context,profile))return
        try{
            manager.notify("push-${notice.authUid}-${notice.eventId}",0,notification)
            val next=JSONArray();(maxOf(0,seen.length()-255) until seen.length()).forEach{next.put(seen.getString(it))};next.put(key)
            prefs(context).edit().putString("seen",next.toString()).apply()
        }catch(_:SecurityException){/* Permission may have changed after the check. */}
    }
}

class AndroidPushService:FirebaseMessagingService(){
    override fun onNewToken(token:String){if(AndroidPush.ensureFirebase(this))AndroidPush.registerToken(this,token)}
    override fun onMessageReceived(message:RemoteMessage){if(AndroidPush.ensureFirebase(this))AndroidPush.receive(this,message.data)}
    override fun onDeletedMessages(){if(AndroidPush.ensureFirebase(this))AndroidPush.refreshReminders(this)}
}

/** Full reminder refresh is retried by Android outside the short FCM callback window. */
class PushSyncJob:JobService(){
    private val executor=Executors.newFixedThreadPool(2)
    private val running=ConcurrentHashMap<JobParameters,Future<*>>()
    private val main=Handler(Looper.getMainLooper())
    override fun onStartJob(params:JobParameters):Boolean {
        running[params]=executor.submit{val success=AndroidPush.perform(this,params);main.post{if(running.remove(params)!=null)jobFinished(params,!success)}}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {running.remove(params)?.cancel(true);return true}
    override fun onDestroy(){running.values.forEach{it.cancel(true)};running.clear();executor.shutdownNow();super.onDestroy()}
}
