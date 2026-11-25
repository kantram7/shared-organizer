package team.kotiki.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.io.File

@Composable fun AttachmentPicker(target:ChatTarget,chats:CloudChats,onSent:(String,String)->Unit,onDismiss:()->Unit,replyTo:String="",initialText:String="",initialFormat:String=PLAIN_TEXT_FORMAT) {
    val context=LocalContext.current
    val store=remember(chats.session){AttachmentsStore(context.applicationContext,chats.session)}
    val scope=rememberCoroutineScope()
    var draft by remember(target.id){mutableStateOf(store.readDraft(target)?:AttachmentDraft(newId(),emptyList(),text=initialText,replyTo=replyTo,format=initialFormat.takeIf{it in listOf(PLAIN_TEXT_FORMAT,MARKDOWN_TEXT_FORMAT)}?:PLAIN_TEXT_FORMAT))}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var progress by remember{mutableStateOf("")}
    var galleryIndex by remember{mutableStateOf<Int?>(null)}
    fun persist(next:AttachmentDraft){draft=next;runCatching{store.saveDraft(target,next)}.onFailure{error="Не удалось сохранить черновик на телефоне"}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        if(uris.isNotEmpty())scope.launch{busy=true;error=null
            try{val files=store.addFiles(uris,draft.files);persist(store.forkDraft(files,draft.text,draft.replyTo,draft.format))}
            catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось добавить файлы"}finally{busy=false}
        }
    }
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=!busy,dismissOnClickOutside=!busy)) {
        EditorSurface(Modifier.fillMaxWidth().fillMaxHeight(.92f).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),shape=MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text("Вложения",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);IconButton(onDismiss,enabled=!busy,modifier=Modifier.semantics{contentDescription="Закрыть вложения"}){KotikiIcon(Glyph.CLOSE)}}
                Row{Text("${draft.files.size} из 10",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium);Text("Без сжатия",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    draft.files.chunked(3).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        row.forEach{file->Box(Modifier.weight(1f).aspectRatio(1f)){
                            AttachmentTile(file,store.thumbnail(file.id),Modifier.fillMaxSize()){if(file.mime.startsWith("image/"))galleryIndex=draft.files.filter{it.mime.startsWith("image/")}.indexOfFirst{it.id==file.id}else runCatching{store.open(file)}.onFailure{error="На телефоне нет приложения для этого файла"}}
                            IconButton({scope.launch{busy=true;try{persist(store.forkDraft(draft.files.filterNot{it.id==file.id},draft.text,draft.replyTo,draft.format))}catch(e:CancellationException){throw e}catch(_:Exception){error="Не удалось обновить черновик"}finally{busy=false}}},Modifier.align(Alignment.TopEnd).size(36.dp),enabled=!busy){KotikiIcon(Glyph.CLOSE,Modifier.size(18.dp))}
                        }}
                        repeat(3-row.size){Spacer(Modifier.weight(1f))}
                    }}
                    TextButton({picker.launch(arrayOf("*/*"))},Modifier.fillMaxWidth(),enabled=!busy && draft.files.size<10){Text(if(draft.files.isEmpty())"+ Выбрать файлы" else "+ Добавить ещё")}
                    RichTextEditor(draft.text,{persist(draft.copy(text=it,format=MARKDOWN_TEXT_FORMAT))},Modifier.fillMaxWidth(),format=draft.format,label="Подпись",minLines=2,readOnly=busy,maxLength=4000)
                    Text("Оригиналы хранятся на сервере 30 дней. Скачанные файлы остаются на телефоне.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                    if(error!=null && draft.files.isNotEmpty() && !busy)TextButton({scope.launch{busy=true;try{persist(store.forkDraft(draft.files,draft.text,draft.replyTo,draft.format));error=null}catch(e:CancellationException){throw e}catch(_:Exception){error="Не удалось подготовить новую отправку"}finally{busy=false}}}){Text("Начать новую отправку")}
                }
                if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(progress.ifBlank{"Подготавливаем файлы…"},style=MaterialTheme.typography.labelSmall)}
                Button({busy=true;error=null;scope.launch{
                    try{store.send(target,chats,draft){sent,total->progress="Загружено $sent из $total"};store.folderProblem()?.let{android.widget.Toast.makeText(context,"Сообщение отправлено. Копия в выбранную папку не сохранена — повтори в настройках файлов.",android.widget.Toast.LENGTH_LONG).show()};onSent(draft.text,draft.format)}
                    catch(e:CancellationException){throw e}catch(_:Exception){error="Отправка не завершена. Черновик и файлы сохранены на телефоне — нажми «Отправить» для повторной попытки."}
                    finally{busy=false}
                }},Modifier.fillMaxWidth(),enabled=!busy && draft.files.isNotEmpty()) {Text("Отправить ${draft.files.size}");Spacer(Modifier.width(6.dp));Icon(painterResource(R.drawable.ic_send_plane),null,Modifier.size(20.dp))}
                if(!busy && draft.files.isNotEmpty())TextButton({store.discardDraft(target,deleteFiles=true);draft=AttachmentDraft(newId(),emptyList());onDismiss()},Modifier.fillMaxWidth()){Text("Убрать черновик")}
            }
        }
    }
    galleryIndex?.let{index->AttachmentGallery(draft.files.filter{it.mime.startsWith("image/")},index,store,{galleryIndex=null})}
}

@Composable private fun AttachmentTile(file:ChatAttachment,preview:File,modifier:Modifier=Modifier,onOpen:()->Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,preview.absolutePath,preview.lastModified()){
        value=withContext(Dispatchers.IO){if(preview.exists())BitmapFactory.decodeFile(preview.absolutePath)else null}
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick=onOpen),contentAlignment=Alignment.Center){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),file.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Icon(painterResource(R.drawable.ic_attachment_file),null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.primary)
            Text(if(file.mime.startsWith("image/"))"Фото" else file.name.substringAfterLast('.',"ФАЙЛ").uppercase().take(5),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
            Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
            Text(fileSize(file.size),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun fileSize(bytes:Long)=if(bytes>=1024*1024)"%.1f МБ".format(bytes/(1024f*1024f))else "${(bytes+1023)/1024} КБ"

/** Dialog ownership belongs to the chat screen, never to a recycled message row. */
internal class ChatGalleryState(val scope:CoroutineScope) {
    private data class Request(val files:List<ChatAttachment>,val index:Int,val store:AttachmentsStore,val onResend:((ChatAttachment)->Unit)?)
    private var request by mutableStateOf<Request?>(null)
    val isOpen:Boolean get()=request!=null
    fun open(files:List<ChatAttachment>,index:Int,store:AttachmentsStore,onResend:((ChatAttachment)->Unit)?=null) {
        if(files.isNotEmpty())request=Request(files.toList(),index.coerceIn(0,files.lastIndex),store,onResend)
    }
    fun close(){request=null}
    @Composable fun Render() {
        request?.let{value->AttachmentGallery(value.files,value.index,value.store,::close,onResend=value.onResend)}
    }
}
internal val LocalChatGallery=staticCompositionLocalOf<ChatGalleryState?>{null}

@Composable fun MessageAttachments(message:ChatMessage,chats:CloudChats) {
    val galleryHost=LocalChatGallery.current
    if(message.deleted || message.attachmentIds.isEmpty())return
    val context=LocalContext.current
    val store=remember(chats.session){AttachmentsStore(context.applicationContext,chats.session)}
    var files by remember(message.id){mutableStateOf(message.attachmentIds.mapIndexed{index,id->ChatAttachment(id,"Вложение ${index+1}","application/octet-stream",0)})}
    val unresolved=remember(message.id){mutableStateMapOf<String,Boolean>().apply{message.attachmentIds.forEach{put(it,true)}}}
    val errors=remember(message.id){mutableStateMapOf<String,String>()}
    val progress=remember(message.id){mutableStateMapOf<String,Float>()}
    val requested=remember(message.id){mutableStateMapOf<String,Boolean>()}
    var loaded by remember(message.id){mutableIntStateOf(0)}
    var autoAllowed by remember{mutableStateOf(store.canAutoDownload())}
    var galleryIndex by remember(message.id){mutableStateOf<Int?>(null)}
    val scope=rememberCoroutineScope()
    DisposableEffect(store){val stop=store.observeAutoDownload{autoAllowed=store.canAutoDownload()};onDispose{stop()}}
    suspend fun resolve(id:String):ChatAttachment {
        val file=store.metadata(id);files=files.map{if(it.id==id)file else it};unresolved.remove(id);errors.remove(id);return file
    }
    LaunchedEffect(message.id,message.attachmentIds){
        message.attachmentIds.forEach{id->try{resolve(id)}catch(e:CancellationException){throw e}catch(_:Exception){errors[id]="Не удалось загрузить сведения. Нажми, чтобы повторить."}}
    }
    LaunchedEffect(files,autoAllowed){
        files.filter{it.id !in unresolved}.forEach{file->
            try{
                val local=store.localCopy(file)
                if(local!=null)loaded++
                if(file.mime.startsWith("image/"))try{store.preview(file);loaded++}catch(e:CancellationException){throw e}catch(_:Exception){}
                if(local==null && autoAllowed){
                    progress[file.id]=0f
                    store.download(file,onProgress={done,total->progress[file.id]=done.toFloat()/total},allowNetwork={store.canAutoDownload()})
                    errors.remove(file.id);loaded++
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){if(store.canAutoDownload())errors[file.id]="Не удалось скачать. Нажми для повторной попытки."}
            finally{progress.remove(file.id)}
        }
    }
    fun resend(file:ChatAttachment){if(requested[file.id]==true)return;requested[file.id]=true
        (galleryHost?.scope ?: scope).launch{try{
            val target=store.target(message.chatId)
            chats.send(target,"Пожалуйста, отправь ещё раз «${file.name}»: файл больше недоступен для скачивания.",onQueued={},onFailure={errors[file.id]=it;requested.remove(file.id)},replyTo=message.id,format="plain-v1")
        }catch(e:CancellationException){throw e}catch(_:Exception){requested.remove(file.id);errors[file.id]="Не удалось отправить просьбу. Попробуй снова."}}
    }
    fun open(file:ChatAttachment){scope.launch{
        try{
            val resolved=if(file.id in unresolved)resolve(file.id)else file
            if(resolved.mime.startsWith("image/")) {
                val images=files
                val index=images.indexOfFirst{it.id==resolved.id}
                if(galleryHost!=null)galleryHost.open(images,index,store,onResend={resend(it)})
                else galleryIndex=index
            }
            else {val index=files.indexOfFirst{it.id==resolved.id};if(galleryHost!=null)galleryHost.open(files,index,store,onResend={resend(it)})else galleryIndex=index}
            errors.remove(file.id)
        }catch(e:CancellationException){throw e}catch(_:Exception){errors[file.id]="Не удалось открыть файл. Проверь соединение и повтори."}finally{progress.remove(file.id)}
    }}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        val images=files.filter{it.mime.startsWith("image/")}
        images.chunked(if(images.size==1)1 else if(images.size==2)2 else 3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            row.forEach{file->key(file.id,loaded){AttachmentTile(file,store.thumbnail(file.id),Modifier.weight(1f).aspectRatio(if(images.size==1)1.5f else 1f)){open(file)}}}
            if(images.size>2)repeat(3-row.size){Spacer(Modifier.weight(1f))}
        }}
        files.filterNot{it.mime.startsWith("image/")}.forEach{file->Surface(Modifier.fillMaxWidth().clickable{open(file)},shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface.copy(alpha=.6f)){
            Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Icon(painterResource(R.drawable.ic_attachment_file),null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
                    Text(file.name.substringAfterLast('.',"ФАЙЛ").uppercase().take(5),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)){Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                    Text(if(file.id in unresolved){if(file.id in errors)"Повторить загрузку сведений"else "Загрузка сведений…"}else fileSize(file.size),style=MaterialTheme.typography.labelSmall)
                }
            }
        }}
        files.forEach{file->
            val expired=file.expiresAt>0 && file.expiresAt<System.currentTimeMillis() && !store.hasLocal(file)
            progress[file.id]?.let{fraction->LinearProgressIndicator(progress={fraction.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Text("${file.name}: ${(fraction*100).toInt()}%",style=MaterialTheme.typography.labelSmall)}
            if(expired){
                Text("${file.name} — срок хранения истёк, локальной копии нет",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({resend(file)},enabled=requested[file.id]!=true){Text(if(requested[file.id]==true)"Просьба отправлена"else "Попросить отправить снова")}
            }else errors[file.id]?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            store.exportProblem(file.id)?.let{problem->
                Text(problem,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                TextButton({scope.launch{progress[file.id]=0f;try{store.retryExport(file);loaded++}finally{progress.remove(file.id)}}}){Text("Повторить сохранение в папку")}
            }
        }
    }
    galleryIndex?.let{index->AttachmentGallery(files,index,store,{galleryIndex=null},onResend={resend(it)})}
}

@Composable fun AttachmentSettings(session:CloudSession) {
    val context=LocalContext.current
    val store=remember(session){AttachmentsStore(context.applicationContext,session)}
    val scope=rememberCoroutineScope()
    var folder by remember{mutableStateOf(store.folderName())}
    var policy by remember{mutableStateOf(store.autoDownload())}
    var usage by remember{mutableStateOf(AttachmentStorageUsage())}
    var error by remember{mutableStateOf<String?>(null)}
    var confirmClear by remember{mutableStateOf(false)}
    var includeSaved by remember{mutableStateOf(false)}
    var clearing by remember{mutableStateOf(false)}
    var folderError by remember{mutableStateOf(store.folderProblem())}
    LaunchedEffect(store){usage=store.storageUsage()}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri:Uri?->
        if(uri!=null)runCatching{store.setFolder(uri);folder=store.folderName();error=null}.onFailure{error="Не удалось получить доступ к папке"}
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Файлы чатов",style=MaterialTheme.typography.titleMedium)
        Text(folder?.let{"Папка: $it / Kotiki team / Файлы чатов"}?:"Оригиналы хранятся внутри приложения. Выбери Documents на этом телефоне, чтобы копии оставались после удаления приложения.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton({picker.launch(null)}){Text(if(folder==null)"Выбрать папку" else "Изменить папку")}
        Text("Автозагрузка оригиналов",style=MaterialTheme.typography.titleSmall)
        SettingsDropdown("Автозагрузка оригиналов",policy.name,
            AttachmentAutoDownload.entries.map{SettingsDropdownOption(it.name,it.label)}){value->
            val option=AttachmentAutoDownload.valueOf(value);policy=option;store.setAutoDownload(option)
        }
        Text("Миниатюры загружаются отдельно. Нажатием на файл можно скачать оригинал через любую сеть.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("В приложении: ${fileSize(usage.appBytes)} · В выбранной папке: ${fileSize(usage.savedBytes)}",style=MaterialTheme.typography.bodySmall)
        OutlinedButton({scope.launch{usage=store.storageUsage();confirmClear=true}},enabled=!clearing && usage.appBytes+usage.savedBytes>0){Text("Очистить локальные файлы")}
        Text("На сервере — 30 дней. На телефоне — пока ты сам не удалишь файлы.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        folderError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            OutlinedButton({scope.launch{store.retryFailedExports();folderError=store.folderProblem();usage=store.storageUsage()}}){Text("Повторить сохранение в папку")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    if(confirmClear)AlertDialog(onDismissRequest={if(!clearing)confirmClear=false},title={Text("Очистить файлы чатов?")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Будут удалены скачанные оригиналы и миниатюры из приложения. Черновики отправки сохранятся.")
        Text(if(usage.expiredFiles>0)"У ${usage.expiredFiles} файлов срок хранения на сервере уже истёк. Их локальная копия может быть единственной: после удаления восстановить её нельзя." else "Некоторые копии могут быть уже недоступны на сервере. После удаления повторное скачивание не гарантируется.",color=MaterialTheme.colorScheme.error)
        if(usage.savedBytes>0)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeSaved,{includeSaved=it},enabled=!clearing);Text("Также удалить сохранённые приложением копии из выбранной папки")}
    }},confirmButton={TextButton({clearing=true;scope.launch{try{val failed=store.clearLocalFiles(includeSaved);usage=store.storageUsage();folderError=store.folderProblem();error=if(failed>0)"Не удалось удалить $failed файлов. Проверь доступ к папке."else null;confirmClear=false}catch(e:CancellationException){throw e}catch(_:Exception){error="Не удалось очистить файлы"}finally{clearing=false}}},enabled=!clearing){Text(if(clearing)"Удаляем…"else "Удалить копии")}},dismissButton={TextButton({confirmClear=false},enabled=!clearing){Text("Отмена")}})
}
