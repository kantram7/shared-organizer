package team.kotiki.app

import android.content.Context
import android.content.Intent
import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

data class SharedContent(val id:String,val text:String,val subject:String="",val textFormat:String=PLAIN_TEXT_FORMAT)
enum class ShareDestination(val label:String) { CHAT("В чат"), TASK("В задачу"), PLAN("В план"), NOTE("В заметку") }

/** Only text is accepted. Received text stays local until the user chooses a destination and sends. */
object SharedContentInbox {
    var pending by mutableStateOf<SharedContent?>(null);private set
    var error by mutableStateOf("");private set
    internal fun parse(intent:Intent):SharedContent? {
        if(intent.action!=Intent.ACTION_SEND || intent.type?.startsWith("text/")!=true)return null
        val text=runCatching{intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()}.getOrNull()
            ?:runCatching{intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let{Html.fromHtml(it,Html.FROM_HTML_MODE_LEGACY).toString()}}.getOrNull()
            ?:return null
        if(text.isBlank()||text.length>100000)return null
        val subject=runCatching{intent.getStringExtra(Intent.EXTRA_SUBJECT)}.getOrNull().orEmpty().take(200)
        return SharedContent(newId(),text,subject)
    }
    private fun file(context:Context)=File(context.noBackupFilesDir,"incoming-shared-text.json")
    fun restore(context:Context) {
        if(pending!=null)return
        runCatching{if(file(context).exists())JSONObject(file(context).readText()).let{pending=SharedContent(it.getString("id"),it.getString("text"),it.optString("subject"),it.optString("textFormat",PLAIN_TEXT_FORMAT))}}
            .onFailure{error="Не удалось восстановить текст из «Поделиться». Исходный файл оставлен на телефоне."}
    }
    fun capture(context:Context,intent:Intent) {
        val content=parse(intent)?:return
        // Do not replace an unsaved incoming draft; a new explicit share can be retried after it is handled.
        if(pending!=null){error="Сначала закончи работу с предыдущим текстом из «Поделиться».";return}
        runCatching {
            val json=JSONObject().put("id",content.id).put("text",content.text).put("subject",content.subject).put("textFormat",content.textFormat)
            val target=file(context);target.parentFile?.mkdirs()
            val next=File(target.path+".new")
            java.io.FileOutputStream(next).use{it.write(json.toString().toByteArray());it.fd.sync()}
            java.nio.file.Files.move(next.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            pending=content;error=""
        }.onFailure{error="Не удалось сохранить полученный текст на телефоне. Повтори «Поделиться»."}
    }
    fun clear(context:Context,id:String) {if(pending?.id==id){if(file(context).exists()&&!file(context).delete()){error="Не удалось убрать локальный черновик.";return};pending=null;error=""}}
}

@Composable fun SharedContentPicker(content:SharedContent,onChoose:(ShareDestination,SharedContent)->Unit,onDismiss:()->Unit) {
    var text by remember(content.id){mutableStateOf(content.text)}
    var format by remember(content.id){mutableStateOf(content.textFormat)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Куда добавить?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        RichTextEditor(text,{text=it;format=MARKDOWN_TEXT_FORMAT},Modifier.fillMaxWidth().heightIn(max=240.dp),format=format,minLines=2)
        ShareDestination.entries.forEach{destination->OutlinedButton({onChoose(destination,content.copy(text=text,textFormat=format))},Modifier.fillMaxWidth(),enabled=richPlainText(text,format).isNotBlank()){Text(destination.label)}}
    }},confirmButton={},dismissButton={TextButton(onDismiss){Text("Отмена")}})
}
