package team.kotiki.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

data class GatheringNotificationTarget(val id:String,val profile:String)

object GatheringNotifications {
    const val CHANNEL="gathering_invitations"
    private const val ENTRY="gathering_entry"
    private const val PROFILE="gathering_profile"

    fun target(intent:Intent?):GatheringNotificationTarget? {
        val id=intent?.getStringExtra(ENTRY) ?: return null
        val profile=intent.getStringExtra(PROFILE) ?: return null
        return GatheringNotificationTarget(id,profile)
    }
    fun createChannel(context:Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Приглашения на общий сбор",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="Приглашения на совместные встречи, ожидающие ответа"
            })
    }
    fun canPost(context:Context):Boolean = Build.VERSION.SDK_INT<33 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED

    // Explicit local preview only: saving an outgoing invitation never notifies its author.
    fun buildPreview(context:Context,entry:Entry,identity:DemoIdentity):Notification {
        require(entry.isGathering && entry.gatheringError()==null)
        val sender=identity.names[entry.owner] ?: entry.owner.label
        val text="$sender приглашает на «${entry.title}». ${fullDayLabel(entry.date!!)}, ${entry.start}–${entry.end}. Ожидается ответ."
        val intent=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action="${context.packageName}.gathering.${identity.viewer.name}.${entry.id}"
            putExtra(ENTRY,entry.id);putExtra(PROFILE,identity.viewer.name)
        }
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_meeting)
            .setContentTitle("Общий сбор · приглашение").setContentText("Открой приложение, чтобы посмотреть подробности").build()
        return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_meeting)
            .setContentTitle("Общий сбор · приглашение").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setSubText("Локальный пример")
            .setCategory(NotificationCompat.CATEGORY_EVENT).setColor(0xFF99536C.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(300_000).build()
    }
    fun showPreview(context:Context,entry:Entry,identity:DemoIdentity):Boolean {
        createChannel(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!canPost(context) || !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance==NotificationManager.IMPORTANCE_NONE)return false
        return try {
            manager.notify("gathering-preview-${identity.viewer.name}-${entry.id}",0,buildPreview(context,entry,identity));true
        } catch(_:SecurityException){false}
    }
}

@Composable fun GatheringNotificationPreview(entry:Entry) {
    val context=LocalContext.current
    val identity=LocalDemoIdentity.current
    var status by rememberSaveable(entry.id){mutableStateOf("")}
    fun show(){status=if(GatheringNotifications.showPreview(context,entry,identity))"Пример появился в шторке уведомлений" else "Уведомления отключены в настройках Android"}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)show() else status="Разрешение не предоставлено. Приглашение сохранено."
    }
    TextButton(onClick={
        if(GatheringNotifications.canPost(context))show() else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }){Text("Проверить уведомление")}
    if(status.isNotEmpty())Text(status,style=MaterialTheme.typography.bodySmall)
}
