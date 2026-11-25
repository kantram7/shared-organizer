package team.kotiki.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

@Composable internal fun AttachmentActions(file:ChatAttachment,store:AttachmentsStore,tint:Color=MaterialTheme.colorScheme.primary) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val exporter=remember(store){AttachmentExport(context.applicationContext,store)}
    var menu by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var progress by remember{mutableStateOf("")}
    var result by remember{mutableStateOf<String?>(null)}
    var pending by remember{mutableStateOf<ChatAttachment?>(null)}
    val update:(Long,Long)->Unit={done,total->scope.launch{progress="Загружаем оригинал · ${if(total>0)done*100/total else 0}%"}}
    fun run(action:suspend ()->String){scope.launch{busy=true;progress="Подготавливаем оригинал…";try{result=action()}catch(e:CancellationException){throw e}catch(_:Exception){result="Не удалось сохранить или передать оригинал. Проверь соединение и доступ к файлу. Если срок хранения истёк и копии на телефоне нет, попроси отправить его снова."}finally{busy=false}}}
    fun gallery(item:ChatAttachment)=run{if(exporter.gallery(item,update))"Сохранено в галерею"else "Это фото уже сохранено в галерее"}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->pending?.let{if(allowed)gallery(it)else result="Без разрешения нельзя сохранить фото в галерею"};pending=null}
    val document=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)){uri->val item=pending;pending=null;if(uri!=null && item!=null)run{exporter.save(item,uri,update);"Файл сохранён"}}
    Box {
        IconButton({menu=true},enabled=!busy,modifier=Modifier.semantics{contentDescription="Действия с вложением"}){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_attachment_more),null,tint=tint)}
        DropdownMenu(menu,{menu=false}) {
            DropdownMenuItem({Text(if(file.mime.startsWith("image/"))"Сохранить в галерею"else "Сохранить файл…")},{
                menu=false
                if(file.mime.startsWith("image/")){
                    if(Build.VERSION.SDK_INT<29 && ContextCompat.checkSelfPermission(context,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){pending=file;permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)}else gallery(file)
                }else{pending=file;document.launch(file.name)}
            })
            DropdownMenuItem({Text("Переслать")},{menu=false;run{exporter.share(file,update);""}})
        }
    }
    if(busy)AlertDialog(onDismissRequest={},text={Column{LinearProgressIndicator(Modifier.fillMaxWidth());Spacer(Modifier.height(12.dp));Text(progress)}},confirmButton={})
    result?.takeIf{it.isNotBlank()}?.let{text->AlertDialog(onDismissRequest={result=null},text={Text(text)},confirmButton={TextButton({result=null}){Text("Понятно")}})}
}
