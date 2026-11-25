package team.kotiki.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun attachmentShareIntent(context:Context,uri:Uri,mime:String,name:String):Intent =
    Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
        .putExtra(Intent.EXTRA_TITLE,name).apply {
            clipData=ClipData(name,arrayOf(mime),ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

internal class AttachmentExport(private val context:Context,private val store:AttachmentsStore) {
    private val prefs=context.getSharedPreferences("attachment-gallery-exports",0)
    private fun key(file:ChatAttachment)="${store.session.spaceId}/${store.session.uid}/${file.id}"
    suspend fun gallery(file:ChatAttachment,onProgress:(Long,Long)->Unit):Boolean=withContext(Dispatchers.IO) {
        require(file.mime.startsWith("image/"))
        val resolver=context.contentResolver
        prefs.getString(key(file),null)?.let { saved ->
            val exists=runCatching{resolver.openInputStream(Uri.parse(saved))?.use{true}?:false}.getOrDefault(false)
            if(exists)return@withContext false
        }
        val original=store.download(file,onProgress)
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,file.name)
            put(MediaStore.Images.Media.MIME_TYPE,file.mime)
            if(Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Kotiki team");put(MediaStore.Images.Media.IS_PENDING,1)}
        }
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("Не удалось создать файл в галерее")
        try {
            resolver.openOutputStream(uri,"w")?.use{output->original.inputStream().use{input->check(copyAttachmentOriginal(input,output)==file.size)}}?:error("Нет доступа к галерее")
            if(Build.VERSION.SDK_INT>=29)check(resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)==1)
            prefs.edit().putString(key(file),uri.toString()).apply()
            true
        }catch(e:Exception){runCatching{resolver.delete(uri,null,null)};throw e}
    }
    suspend fun save(file:ChatAttachment,uri:Uri,onProgress:(Long,Long)->Unit)=withContext(Dispatchers.IO) {
        try {
            val original=store.download(file,onProgress)
            context.contentResolver.openOutputStream(uri,"wt")?.use{out->original.inputStream().use{input->check(copyAttachmentOriginal(input,out)==file.size)}}?:error("Нет доступа к выбранному файлу")
        } catch(e:Exception){runCatching{android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)};throw e}
    }
    suspend fun share(file:ChatAttachment,onProgress:(Long,Long)->Unit) {
        val local=store.download(file,onProgress)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.chatfiles",local,file.name)
        context.startActivity(Intent.createChooser(attachmentShareIntent(context,uri,file.mime,file.name),"Переслать").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
