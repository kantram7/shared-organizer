package team.kotiki.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ChatAttachment(val id:String,val name:String,val mime:String,val size:Long,val expiresAt:Long=0L,val thumbnailSize:Long=0L)
internal enum class AttachmentAutoDownload(val label:String){ WIFI("Wi-Fi"),ANY("Любая сеть"),MANUAL("Вручную") }
internal data class AttachmentStorageUsage(val appBytes:Long=0,val savedBytes:Long=0,val expiredFiles:Int=0,val protectedBytes:Long=0)
internal fun attachmentAutoDownloadAllowed(policy:AttachmentAutoDownload,connected:Boolean,wifi:Boolean)=connected && when(policy){AttachmentAutoDownload.WIFI->wifi;AttachmentAutoDownload.ANY->true;AttachmentAutoDownload.MANUAL->false}
internal data class AttachmentDraft(val messageId:String,val files:List<ChatAttachment>,val text:String="",val replyTo:String="",val format:String=PLAIN_TEXT_FORMAT)
internal object AttachmentLimits {
    const val fileBytes=100L*1024*1024
    const val messageBytes=500L*1024*1024
    fun validate(sizes:List<Long>) {
        require(sizes.size<=10){"До 10 файлов в одном сообщении"}
        require(sizes.all{it in 1..fileBytes}){"Файл должен быть непустым и не больше 100 МБ"}
        require(sizes.sum()<=messageBytes){"Общий размер больше 500 МБ"}
    }
}
internal suspend fun copyAttachmentOriginal(input:java.io.InputStream,output:java.io.OutputStream,limit:Long=AttachmentLimits.fileBytes):Long {
    val buffer=ByteArray(65536);var total=0L
    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit){"Файл больше 100 МБ"};output.write(buffer,0,n)}
    return total
}
internal suspend fun <T> Task<T>.attachmentAwait():T=suspendCancellableCoroutine{continuation->
    addOnSuccessListener{if(continuation.isActive)continuation.resume(it)}
    addOnFailureListener{if(continuation.isActive)continuation.resumeWithException(it)}
    addOnCanceledListener{continuation.cancel()}
}

/** Originals are immutable. Only a separate thumbnail is reduced. Signed URLs never touch disk. */
internal class AttachmentsStore(private val context:Context,val session:CloudSession) {
    companion object { private val downloads=java.util.concurrent.ConcurrentHashMap<String,Mutex>() }
    private val profile=MessageDigest.getInstance("SHA-256").digest("${session.spaceId}/${session.uid}".toByteArray()).joinToString(""){"%02x".format(it)}
    private val folder=File(context.filesDir,"chat-files/$profile").apply{mkdirs()}
    private val draftFolder=File(context.noBackupFilesDir,"chat-drafts/$profile").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("chat-files-$profile",0)
    private val functions by lazy{FirebaseFunctions.getInstance("europe-west3")}
    private fun safeId(id:String):String {require(id.matches(Regex("[A-Za-z0-9_-]{1,200}")));return id}
    fun original(id:String)=File(folder,safeId(id))
    fun thumbnail(id:String)=File(folder,"${safeId(id)}.preview.jpg")
    private fun draftFile(target:ChatTarget)=File(draftFolder,"${safeId(target.id)}.json")
    fun readDraft(target:ChatTarget):AttachmentDraft? {
        val file=draftFile(target)
        return parseDraft(file) ?: parseDraft(File(file.path+".bak"))
    }
    private fun parseDraft(file:File):AttachmentDraft?=runCatching {
        val j=JSONObject(file.readText());val a=j.getJSONArray("files")
        AttachmentDraft(j.getString("messageId"),(0 until a.length()).map{val f=a.getJSONObject(it);ChatAttachment(f.getString("id"),f.getString("name"),f.getString("mime"),f.getLong("size"))},j.optString("text"),j.optString("replyTo"),j.optString("format",PLAIN_TEXT_FORMAT).takeIf{it in listOf(PLAIN_TEXT_FORMAT,MARKDOWN_TEXT_FORMAT)}?:PLAIN_TEXT_FORMAT)
    }.getOrNull()
    @Synchronized fun saveDraft(target:ChatTarget,draft:AttachmentDraft) {
        val json=JSONObject().put("messageId",draft.messageId).put("text",draft.text).put("replyTo",draft.replyTo).put("format",draft.format).put("files",JSONArray(draft.files.map{JSONObject().put("id",it.id).put("name",it.name).put("mime",it.mime).put("size",it.size)}))
        val file=draftFile(target);val next=File(file.path+".new")
        // Explicit replace propagates failure instead of AtomicFile.finishWrite logging a failed rename.
        // The old committed manifest remains readable until the durable new manifest replaces it.
        java.io.FileOutputStream(next,false).use{it.write(json.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
        java.nio.file.Files.move(next.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        File(file.path+".bak").delete()
    }
    fun discardDraft(target:ChatTarget,deleteFiles:Boolean=false){
        val files=if(deleteFiles)readDraft(target)?.files.orEmpty()else emptyList()
        draftFile(target).delete();File(draftFile(target).path+".bak").delete();File(draftFile(target).path+".new").delete()
        if(deleteFiles){val protected=protectedIds();files.filter{it.id !in protected}.forEach{original(it.id).delete();thumbnail(it.id).delete()}}
    }
    internal fun markAttempted(draft:AttachmentDraft){check(prefs.edit().apply{draft.files.forEach{putBoolean("reserved-${it.id}",true)}}.commit()){ "Не удалось сохранить состояние отправки" }}
    suspend fun forkDraft(files:List<ChatAttachment>,text:String,replyTo:String="",format:String=PLAIN_TEXT_FORMAT):AttachmentDraft=withContext(Dispatchers.IO) {
        // A modified selection is a new immutable upload session, including its file IDs.
        val copies=files.map{old->
            if(!prefs.getBoolean("reserved-${old.id}",false))return@map old
            val next=old.copy(id=newId());original(old.id).copyTo(original(next.id),overwrite=false)
            if(thumbnail(old.id).exists())thumbnail(old.id).copyTo(thumbnail(next.id),overwrite=false)
            next
        }
        AttachmentDraft(newId(),copies,text,replyTo,format)
    }
    fun autoDownload():AttachmentAutoDownload=runCatching{AttachmentAutoDownload.valueOf(prefs.getString("autoDownload","ANY")!!)}.getOrDefault(AttachmentAutoDownload.ANY)
    fun setAutoDownload(policy:AttachmentAutoDownload){prefs.edit().putString("autoDownload",policy.name).apply()}
    fun canAutoDownload():Boolean=runCatching {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps=manager.getNetworkCapabilities(manager.activeNetwork)
        attachmentAutoDownloadAllowed(autoDownload(),caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true)
    }.getOrDefault(false)
    fun observeAutoDownload(changed:()->Unit):()->Unit {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback=object:android.net.ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:android.net.Network){changed()}
            override fun onLost(network:android.net.Network){changed()}
            override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities){changed()}
        }
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="autoDownload")changed()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        runCatching{manager.registerDefaultNetworkCallback(callback)}
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener);runCatching{manager.unregisterNetworkCallback(callback)}}
    }
    private fun protectedIds():Set<String> = draftFolder.listFiles().orEmpty().flatMap{file->runCatching{
        val entries=JSONObject(file.readText()).getJSONArray("files");(0 until entries.length()).map{entries.getJSONObject(it).getString("id")}
    }.getOrDefault(emptyList())}.toSet()
    suspend fun storageUsage():AttachmentStorageUsage=withContext(Dispatchers.IO){
        val protected=protectedIds();val all=folder.listFiles().orEmpty().filter{it.isFile}
        val protectedBytes=all.filter{it.name.removeSuffix(".preview.jpg").removeSuffix(".part") in protected}.sumOf{it.length()}
        val saved=prefs.all.filterKeys{it.startsWith("saved-")}.mapNotNull{(k,v)->runCatching{k.removePrefix("saved-") to DocumentFile.fromSingleUri(context,Uri.parse(v as String))}.getOrNull()}
        val savedBytes=saved.sumOf{(_,d)->runCatching{d?.length()?:0L}.getOrDefault(0L)}
        val localIds=all.map{it.name.removeSuffix(".preview.jpg").removeSuffix(".part")}.toSet()+saved.filter{runCatching{it.second?.exists()==true}.getOrDefault(false)}.map{it.first}
        val expired=localIds.count{id->cachedMetadata(id)?.expiresAt?.let{it>0 && it<System.currentTimeMillis()}==true && id !in protected}
        AttachmentStorageUsage(all.sumOf{it.length()},savedBytes,expired,protectedBytes)
    }
    suspend fun clearLocalFiles(includeSavedFolder:Boolean):Int=withContext(Dispatchers.IO){
        val protected=protectedIds();var failed=0
        // Only exact files created by this account are removed; folders are never deleted recursively.
        folder.listFiles().orEmpty().filter{it.isFile && it.name.removeSuffix(".preview.jpg").removeSuffix(".part") !in protected}.forEach{if(!it.delete())failed++}
        prefs.edit().apply{prefs.all.keys.filter{it.startsWith("export-error-") && it.removePrefix("export-error-") !in protected}.forEach{remove(it)}}.apply()
        if(includeSavedFolder)prefs.all.filterKeys{it.startsWith("saved-") && it.removePrefix("saved-") !in protected}.forEach{(key,value)->
            val removed=runCatching{val file=DocumentFile.fromSingleUri(context,Uri.parse(value as String));file==null || !file.exists() || file.delete()}.getOrDefault(false)
            if(removed)prefs.edit().remove(key).apply()else failed++
        }
        failed
    }
    fun hasLocal(file:ChatAttachment)=original(file.id).isFile && original(file.id).length()==file.size
    suspend fun localCopy(file:ChatAttachment):File?=withContext(Dispatchers.IO){
        if(hasLocal(file))return@withContext original(file.id)
        val saved=prefs.getString("saved-${file.id}",null)?:return@withContext null
        val temp=File(folder,"${safeId(file.id)}.restore")
        try {
            context.contentResolver.openInputStream(Uri.parse(saved))?.use{input->temp.outputStream().use{copyAttachmentOriginal(input,it,file.size)}}?:return@withContext null
            if(temp.length()!=file.size)return@withContext null
            if(!temp.renameTo(original(file.id)))return@withContext null
            if(file.mime.startsWith("image/"))runCatching{makeThumbnail(original(file.id),thumbnail(file.id))}
            original(file.id)
        }catch(e:CancellationException){throw e}catch(_:Exception){null}finally{temp.delete()}
    }
    private fun cacheMetadata(file:ChatAttachment){prefs.edit().putString("meta-${file.id}",JSONObject().put("id",file.id).put("name",file.name).put("mime",file.mime).put("size",file.size).put("expiresAt",file.expiresAt).put("thumbnailSize",file.thumbnailSize).toString()).apply()}
    private fun cachedMetadata(id:String):ChatAttachment?=runCatching{val j=JSONObject(prefs.getString("meta-$id",null)?:error("Metadata missing"));ChatAttachment(id,j.getString("name"),j.getString("mime"),j.getLong("size"),j.optLong("expiresAt"),j.optLong("thumbnailSize"))}.getOrNull()
    suspend fun target(chatId:String):ChatTarget {
        if(chatId=="general")return ChatTarget("general","Общий чат")
        val doc=FirebaseFirestore.getInstance().collection("spaces").document(session.spaceId).collection("chats").document(safeId(chatId)).get().attachmentAwait()
        check(doc.exists()) {"Чат недоступен"}
        return ChatTarget(chatId,"Обсуждение",doc.getString("collectionId").orEmpty(),doc.getString("itemId").orEmpty())
    }
    fun folderName():String?=prefs.getString("tree",null)?.let{runCatching{DocumentFile.fromTreeUri(context,Uri.parse(it))?.name}.getOrNull()}
    fun setFolder(uri:Uri) {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(DocumentFile.fromTreeUri(context,uri)?.canWrite()==true){"Папка недоступна для записи"}
        prefs.edit().putString("tree",uri.toString()).apply()
    }
    suspend fun addFiles(uris:List<Uri>,existing:List<ChatAttachment>):List<ChatAttachment> = withContext(Dispatchers.IO) {
        require(existing.size+uris.size<=10){"До 10 файлов в одном сообщении"}
        val added=mutableListOf<ChatAttachment>()
        try {
            uris.forEach{uri->
                ensureActive()
                val id=newId();val mime=context.contentResolver.getType(uri)?:"application/octet-stream"
                var name="Файл"
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)?:name}
                name=name.replace(Regex("[\\\\/\\p{Cntrl}]"),"_").take(180).ifBlank{"Файл"}
                val destination=original(id)
                try {
                    context.contentResolver.openInputStream(uri)?.use{input->destination.outputStream().use{output->
                        copyAttachmentOriginal(input,output)
                    }}?:error("Не удалось прочитать файл")
                    AttachmentLimits.validate((existing+added).map{it.size}+destination.length())
                    val file=ChatAttachment(id,name,mime,destination.length());added+=file
                    if(mime.startsWith("image/"))runCatching{makeThumbnail(destination,thumbnail(id))}
                }catch(e:Exception){destination.delete();throw e}
            }
            existing+added
        }catch(e:Exception){added.forEach{original(it.id).delete();thumbnail(it.id).delete()};throw e}
    }
    private fun makeThumbnail(source:File,destination:File) {
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)){decoder,info,_->
            val factor=(512f/maxOf(info.size.width,info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width*factor).toInt().coerceAtLeast(1),(info.size.height*factor).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        destination.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,82,it)};bitmap.recycle()
        if(destination.length()>512*1024)destination.delete()
    }
    private suspend fun call(name:String,data:Map<String,Any>):Map<*,*> = functions.getHttpsCallable(name).call(data).attachmentAwait().data as Map<*,*>
    suspend fun send(target:ChatTarget,chats:CloudChats,draft:AttachmentDraft,onProgress:(Int,Int)->Unit) {
        require(draft.files.isNotEmpty() && draft.files.size<=10)
        AttachmentLimits.validate(draft.files.map{it.size})
        require(draft.text.length<=4000 && draft.format in listOf(PLAIN_TEXT_FORMAT,MARKDOWN_TEXT_FORMAT)){"Проверь длину и формат подписи"}
        // Reserve locally before contacting the server, including a lost prepare response.
        withContext(Dispatchers.IO){markAttempted(draft)}
        suspendCancellableCoroutine<Unit>{c->chats.ensureChat(target,{if(c.isActive)c.resume(Unit)},{if(c.isActive)c.resumeWithException(it?:IllegalStateException("Чат недоступен"))})}
        val prepared=try{call("prepareAttachments",mapOf("spaceId" to session.spaceId,"chatId" to target.id,"messageId" to draft.messageId,"files" to draft.files.map{file->
            mutableMapOf<String,Any>("id" to file.id,"name" to file.name,"mime" to file.mime,"size" to file.size).apply{val thumb=thumbnail(file.id);if(thumb.exists()){put("thumbnailSize",thumb.length());put("thumbnailMime","image/jpeg")}}
        }))}catch(e:FirebaseFunctionsException){
            if(e.code!=FirebaseFunctionsException.Code.ALREADY_EXISTS)throw e
            // Commit may have succeeded immediately before a disconnect or process death.
            val committed=call("commitAttachmentMessage",mapOf("spaceId" to session.spaceId,"chatId" to target.id,"messageId" to draft.messageId,"text" to draft.text,"attachmentIds" to draft.files.map{it.id},"format" to draft.format,"replyTo" to draft.replyTo))
            finishCommitted(target,draft,committed)
            return
        }
        val uploads=prepared["attachments"] as List<*>
        uploads.forEachIndexed{index,value->val item=value as Map<*,*>;val id=item["id"] as String
            draft.files.firstOrNull{it.id==id}?.let{cacheMetadata(it.copy(expiresAt=(item["expiresAtMillis"] as? Number)?.toLong()?:0,thumbnailSize=thumbnail(id).takeIf{f->f.exists()}?.length()?:0))}
            withContext(Dispatchers.IO){put(item["uploadUrl"] as String,item["headers"] as Map<*,*>,original(id));
                (item["thumbnailUploadUrl"] as? String)?.let{put(it,item["thumbnailHeaders"] as Map<*,*>,thumbnail(id))}}
            onProgress(index+1,uploads.size)
        }
        val committed=call("commitAttachmentMessage",mapOf("spaceId" to session.spaceId,"chatId" to target.id,"messageId" to draft.messageId,"text" to draft.text,"attachmentIds" to draft.files.map{it.id},"format" to draft.format,"replyTo" to draft.replyTo))
        finishCommitted(target,draft,committed)
    }
    private suspend fun finishCommitted(target:ChatTarget,draft:AttachmentDraft,response:Map<*,*>) {
        val committedExpiry=(response["expiresAtMillis"] as? Number)?.toLong()?:0L
        // A prepare URL has a provisional expiry; only the committed manifest owns the 30-day clock.
        draft.files.forEach{file->
            val ready=if(committedExpiry>0)(cachedMetadata(file.id)?:file).copy(expiresAt=committedExpiry)
                else try{metadata(file.id)}catch(e:CancellationException){throw e}catch(_:Exception){file.copy(expiresAt=0)}
            withContext(Dispatchers.IO){cacheMetadata(ready);saveToPhoneFolder(ready)}
        }
        withContext(Dispatchers.IO){discardDraft(target)}
    }
    private fun connection(url:String):HttpURLConnection {
        val parsed=URL(url);require(parsed.protocol=="https"){"Небезопасная ссылка файла"}
        return (parsed.openConnection() as HttpURLConnection).apply{connectTimeout=30000;readTimeout=60000;instanceFollowRedirects=false}
    }
    private suspend fun put(url:String,headers:Map<*,*>,file:File) {
        require(file.exists()){ "Локальный файл недоступен; добавь его заново" }
        val c=connection(url)
        try{c.requestMethod="PUT";c.doOutput=true;headers.forEach{(k,v)->c.setRequestProperty(k.toString(),v.toString())};c.setFixedLengthStreamingMode(file.length())
            c.outputStream.use{output->file.inputStream().use{input->val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)}}}
            require(c.responseCode in 200..299 || c.responseCode==412){"Не удалось загрузить файл (${c.responseCode})"}
        }finally{c.disconnect()}
    }
    suspend fun metadata(id:String):ChatAttachment {
        try {
            val doc=FirebaseFirestore.getInstance().collection("spaces").document(session.spaceId).collection("attachments").document(safeId(id)).get().attachmentAwait()
            require(doc.exists()){ "Файл недоступен" }
            return ChatAttachment(id,doc.getString("name")?:"Файл",doc.getString("mime")?:"application/octet-stream",doc.getLong("size")?:0,doc.getTimestamp("expiresAt")?.toDate()?.time?:0,doc.getLong("thumbnailSize")?:0).also{cacheMetadata(it)}
        }catch(e:com.google.firebase.firestore.FirebaseFirestoreException){
            if(e.code==com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE)cachedMetadata(id)?.let{return it}
            throw e
        }
    }
    suspend fun preview(file:ChatAttachment):File?=downloads.getOrPut("$profile/${file.id}/preview"){Mutex()}.withLock {withContext(Dispatchers.IO){
        val destination=thumbnail(file.id)
        if(destination.exists())return@withContext destination
        if(hasLocal(file)){runCatching{makeThumbnail(original(file.id),destination)};return@withContext destination.takeIf{it.exists()}}
        if(file.thumbnailSize<=0)return@withContext null
        val response=call("attachmentDownload",mapOf("spaceId" to session.spaceId,"attachmentId" to file.id,"thumbnail" to true))
        val expected=(response["size"] as? Number)?.toLong()?:file.thumbnailSize
        require(expected in 1..512L*1024)
        val c=connection(response["url"] as String);val temp=File(folder,"${safeId(file.id)}.preview.part")
        try {
            require(c.responseCode==200)
            c.inputStream.use{input->temp.outputStream().use{copyAttachmentOriginal(input,it,expected)}}
            require(temp.length()==expected)
            require(temp.renameTo(destination));destination
        }finally{c.disconnect();temp.delete()}
    }}
    suspend fun download(file:ChatAttachment,onProgress:(Long,Long)->Unit={_,_->},allowNetwork:()->Boolean={true}):File=downloads.getOrPut("$profile/${file.id}"){Mutex()}.withLock {withContext(Dispatchers.IO) {
        localCopy(file)?.let{saveToPhoneFolder(file);return@withContext it}
        check(allowNetwork()){"Автозагрузка ждёт подходящую сеть"}
        val destination=original(file.id)
        val response=call("attachmentDownload",mapOf("spaceId" to session.spaceId,"attachmentId" to file.id))
        val c=connection(response["url"] as String);val temp=File(folder,"${safeId(file.id)}.part")
        try{
            check(allowNetwork()){"Автозагрузка ждёт подходящую сеть"}
            require(c.responseCode==200){"Не удалось скачать файл"}
            val digest=MessageDigest.getInstance("MD5");var total=0L
            c.inputStream.use{input->temp.outputStream().use{output->val buffer=ByteArray(65536);while(true){ensureActive();check(allowNetwork()){"Автозагрузка ждёт подходящую сеть"};val n=input.read(buffer);if(n<0)break;total+=n;require(total<=file.size && total<=AttachmentLimits.fileBytes);digest.update(buffer,0,n);output.write(buffer,0,n);onProgress(total,file.size)}}}
            require(total==file.size){"Файл скачан не полностью"}
            val expected=response["md5Hash"] as? String
            if(!expected.isNullOrBlank())require(android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP)==expected){"Файл не прошёл проверку целостности"}
            require(temp.renameTo(destination)){"Не удалось сохранить файл"}
            if(file.mime.startsWith("image/"))runCatching{makeThumbnail(destination,thumbnail(file.id))}
            saveToPhoneFolder(file);destination
        }finally{c.disconnect();temp.delete()}
    }}
    fun exportProblem(id:String):String?=prefs.getString("export-error-${safeId(id)}",null)
    fun folderProblem():String?=prefs.all.entries.firstOrNull{it.key.startsWith("export-error-")}?.value as? String
    suspend fun retryExport(file:ChatAttachment):Boolean=withContext(Dispatchers.IO){
        if(localCopy(file)==null)return@withContext false
        saveToPhoneFolder(file)
    }
    suspend fun retryFailedExports():Int=withContext(Dispatchers.IO){
        val ids=prefs.all.keys.filter{it.startsWith("export-error-")}.map{it.removePrefix("export-error-")}
        ids.forEach{id->cachedMetadata(id)?.let{retryExport(it)}}
        prefs.all.keys.count{it.startsWith("export-error-")}
    }
    private fun saveToPhoneFolder(file:ChatAttachment):Boolean {
        val tree=prefs.getString("tree",null)?:return true
        var created:DocumentFile?=null
        return try {
            val saved=prefs.getString("saved-${file.id}",null)
            if(saved!=null && DocumentFile.fromSingleUri(context,Uri.parse(saved))?.exists()==true){prefs.edit().remove("export-error-${file.id}").apply();return true}
            val root=DocumentFile.fromTreeUri(context,Uri.parse(tree))?:error("Папка недоступна")
            check(root.canWrite()){"Нет доступа к папке"}
            val sub=root.findFile("Kotiki team")?:root.createDirectory("Kotiki team")?:error("Не удалось создать папку")
            val chatsFolder=sub.findFile("Файлы чатов")?:sub.createDirectory("Файлы чатов")?:error("Не удалось создать папку")
            val result=chatsFolder.createFile(file.mime,file.name)?:error("Не удалось создать файл")
            created=result
            context.contentResolver.openOutputStream(result.uri,"w")?.use{output->original(file.id).inputStream().use{it.copyTo(output)}}?:error("Папка недоступна")
            check(result.length()==file.size){"Файл сохранён не полностью"}
            prefs.edit().putString("saved-${file.id}",result.uri.toString()).remove("export-error-${file.id}").apply()
            true
        }catch(_:Exception){
            runCatching{created?.delete()}
            prefs.edit().putString("export-error-${file.id}","Копия в выбранную папку не сохранена. Оригинал остаётся в приложении. Проверь доступ и свободное место, затем повтори сохранение.").apply()
            false
        }
    }
    fun open(file:ChatAttachment) {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.chatfiles",original(file.id))
        val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent,file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
