package team.kotiki.app

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun ChatScreen(target:ChatTarget,chats:CloudChats?,onBack:(()->Unit)?=null,header:(@Composable ()->Unit)?=null,initialMessageId:String?=null) {
    if(chats==null){Column{ChatHeading(target.title,onBack);header?.invoke();Text("Войди в облако для переписки")};return}
    key(chats.session.uid,chats.session.spaceId,target.id) {
        val context=LocalContext.current
        val prefs=remember{context.getSharedPreferences("chat_drafts_${chats.session.uid}",0)}
        val draftKey="${chats.session.spaceId}/${target.id}"
        var draft by rememberSaveable{mutableStateOf(prefs.getString(draftKey,"").orEmpty().let{if(prefs.getString("format/$draftKey","")==MARKDOWN_TEXT_FORMAT)it else RichTextCodec.escape(it)})}
        var reply by remember{mutableStateOf<ChatMessage?>(null)}
        var failure by remember{mutableStateOf<String?>(null)}
        var messageLimit by rememberSaveable{mutableIntStateOf(100)}
        var retry by remember{mutableIntStateOf(0)}
        var snapshot by remember{mutableStateOf(ChatSnapshot())}
        var searchOpen by rememberSaveable{mutableStateOf(false)}
        var searchText by rememberSaveable{mutableStateOf("")}
        var searchResults by remember{mutableStateOf<List<ChatMessage>?>(null)}
        var searching by remember{mutableStateOf(false)}
        var searchEpoch by remember{mutableIntStateOf(0)}
        var searchIndex by remember{mutableIntStateOf(-1)}
        var highlightedMessage by remember{mutableStateOf<String?>(null)}
        val screenLife=remember{object{var active=true}}
        DisposableEffect(Unit){screenLife.active=true;onDispose{screenLife.active=false}}
        var attachments by remember{mutableStateOf(false)}
        var attachmentOpenText by remember{mutableStateOf("")}
        var sourceMessage by remember{mutableStateOf<ChatMessage?>(null)}
        var editingMessage by remember{mutableStateOf<ChatMessage?>(null)}
        val scope=rememberCoroutineScope()
        val gallery=remember(scope){ChatGalleryState(scope)}
        val state=rememberLazyListState()
        val unread=if(target.id=="general")LocalChatUnread.current else if(target.id.startsWith("entry_"))LocalEntryChatReads.current?.state(target.itemId.ifBlank{target.id.removePrefix("entry_")}) else remember(chats.session.uid,target.id){ChatUnreadState(context,chats.session.uid,"${chats.session.spaceId}/${chats.session.uid}/${target.id}")}
        val welcomeVisible=LocalWelcomeVisible.current
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        var resumed by remember{mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
        DisposableEffect(lifecycle){val o=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)};lifecycle.addObserver(o);onDispose{lifecycle.removeObserver(o)}}
        val visible by remember{derivedStateOf{state.layoutInfo.visibleItemsInfo.filter{it.offset+it.size>0 && it.offset<state.layoutInfo.viewportEndOffset}.map{it.key.toString()}.toSet()}}
        val marked=remember{mutableSetOf<String>()}
        val readsInFlight=remember{mutableSetOf<String>()}
        var readRetry by remember{mutableIntStateOf(0)}
        var readFailure by remember{mutableStateOf<String?>(null)}
        var initialUnread by remember{mutableStateOf<String?>(null)}
        var positioned by remember{mutableStateOf(false)}
        var followBottom by remember{mutableStateOf(true)}
        val dragging by state.interactionSource.collectIsDraggedAsState()
        var previousLast by remember{mutableStateOf<String?>(null)}
        var jumpId by remember{mutableStateOf<String?>(initialMessageId)}
        LaunchedEffect(initialMessageId){if(initialMessageId!=null)jumpId=initialMessageId}
        LaunchedEffect(highlightedMessage){if(highlightedMessage!=null){delay(2200);highlightedMessage=null}}
        LaunchedEffect(snapshot.accessDenied){if(snapshot.accessDenied){
            searchEpoch++;searching=false;searchOpen=false;searchResults=null;searchIndex=-1
            sourceMessage=null;editingMessage=null;reply=null;attachments=false;attachmentOpenText="";gallery.close()
            highlightedMessage=null;jumpId=null;followBottom=false;failure=null;readFailure=null
            marked.clear();readsInFlight.clear()
        }}
        LaunchedEffect(dragging,state.canScrollForward){if(dragging || positioned && !state.canScrollForward)followBottom=!state.canScrollForward}
        LaunchedEffect(resumed,positioned,visible,snapshot.messages,snapshot.readStateReady,searchOpen,attachments,sourceMessage,welcomeVisible,editingMessage,gallery.isOpen,readRetry){
            if(!welcomeVisible && resumed && positioned && !searchOpen && !attachments && sourceMessage==null && editingMessage==null && !gallery.isOpen && snapshot.error==null){
                val shown=snapshot.messages.filter{it.id in visible};unread?.readThrough(shown)
                shown.filter{!it.pending && !it.deleted && it.sender!=chats.session.uid && it.id !in marked && it.id !in readsInFlight}.forEach{message->
                    readsInFlight.add(message.id)
                    NotificationReadState.markMessageRead(context,chats.session.authUid,message.chatId,message.id)
                    chats.markRead(message){error->if(screenLife.active){
                        readsInFlight.remove(message.id)
                        if(error==null){marked.add(message.id);readFailure=null}
                        else if(!snapshot.accessDenied){readFailure="Не удалось синхронизировать прочтение. Повторим автоматически.";scope.launch{delay(15000);readRetry++}}
                    }}
                }
            }
        }
        DisposableEffect(chats,target.id,retry,messageLimit){val stop=chats.observe(target,messageLimit=messageLimit){snapshot=it;if(target.collectionId.isNotBlank())unread?.receive(it)};onDispose{stop()}}
        fun indexOf(id:String)=snapshot.messages.indexOfFirst{it.id==id}+if(snapshot.messages.size>=messageLimit)1 else 0
        fun bottomIndex()=snapshot.messages.size+if(snapshot.messages.size>=messageLimit)1 else 0
        LaunchedEffect(snapshot.messages.lastOrNull()?.id,snapshot.loading,snapshot.readStateReady,jumpId,messageLimit,positioned){
            if(snapshot.accessDenied || snapshot.loading || !snapshot.readStateReady || snapshot.messages.isEmpty())return@LaunchedEffect
            val last=snapshot.messages.last().id
            if(!positioned){
                initialUnread=snapshot.messages.firstOrNull{unread?.isUnread(it) ?: (it.sender!=chats.session.uid && !it.readByMe && !it.deleted)}?.id
                if(initialUnread==null)state.scrollToItem(bottomIndex()) else state.scrollToItem(indexOf(initialUnread!!).coerceAtLeast(0));positioned=true;followBottom=initialUnread==null || !state.canScrollForward
            }else if(jumpId!=null){
                if(snapshot.messages.any{it.id==jumpId}){followBottom=false;val destination=jumpId!!;state.animateScrollToItem(indexOf(destination));highlightedMessage=destination;jumpId=null}
                else if(snapshot.messages.size>=messageLimit)messageLimit+=100
                else{chats.getMessage(target,jumpId!!){if(screenLife.active&&!snapshot.accessDenied){sourceMessage=it;if(it==null)failure="Исходное сообщение удалено или недоступно"}};jumpId=null}
            }else if(last!=previousLast && followBottom){state.animateScrollToItem(bottomIndex())}
            previousLast=last
        }
        // Follow layout changes too (reactions, downloaded thumbnails, IME). The end
        // sentinel reaches the actual bottom even when a single album is taller than the viewport.
        FollowChatBottom(state,!snapshot.accessDenied && !gallery.isOpen && !searchOpen && followBottom && positioned && editingMessage==null && jumpId==null && !dragging)
        fun setDraft(v:String){draft=v;prefs.edit().putString(draftKey,v).putString("format/$draftKey",MARKDOWN_TEXT_FORMAT).apply()}
        fun send(){if(snapshot.accessDenied || draft.isBlank())return;val original=draft;chats.send(target,original,onQueued={if(draft==original)setDraft("");reply=null;followBottom=true},onFailure={failure=it},replyTo=reply?.id.orEmpty())}
        Column(Modifier.fillMaxSize().imePadding()) {
            ChatHeading(target.title,onBack,if(snapshot.accessDenied)null else ({searchOpen=!searchOpen}));header?.invoke()
            if(searchOpen&&!snapshot.accessDenied){
                OutlinedTextField(searchText,{searchText=it;searchEpoch++;searching=false;searchResults=null;searchIndex=-1},Modifier.fillMaxWidth(),label={Text("Поиск по всей переписке")},singleLine=true,
                    trailingIcon={TextButton({
                        val epoch=++searchEpoch;val query=searchText.trim();searching=true
                        chats.search(target,query){rows,error->if(screenLife.active && epoch==searchEpoch && !snapshot.accessDenied){searchResults=rows;failure=error;searching=false;searchIndex=-1}}
                    },enabled=searchText.isNotBlank() && !searching){Text("Найти")}})
                searchResults?.let{results->
                    fun choose(index:Int){searchIndex=index;followBottom=false;jumpId=results[index].id}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(if(results.isEmpty())"Ничего не найдено"else "${if(searchIndex<0)0 else searchIndex+1} / ${results.size}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                        TextButton({choose(if(searchIndex<0)results.lastIndex else (searchIndex-1+results.size)%results.size)},enabled=results.isNotEmpty()){Text("Предыдущее")}
                        TextButton({choose((searchIndex+1)%results.size)},enabled=results.isNotEmpty()){Text("Следующее")}
                    }
                    Surface(Modifier.fillMaxWidth().padding(bottom=6.dp),shape=RoundedCornerShape(12.dp),
                        color=MaterialTheme.colorScheme.surface.copy(alpha=.90f),
                        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.24f))) {
                        LazyColumn(Modifier.heightIn(max=160.dp)){itemsIndexed(results,key={_,m->m.id}){index,m->
                            Column(Modifier.fillMaxWidth().background(if(index==searchIndex)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.75f)else Color.Transparent).clickable{choose(index)}) {
                                Text(chatSearchHighlighted(chatSearchExcerpt(richPlainText(m.text,m.format),searchText.trim()),searchText.trim(),MaterialTheme.colorScheme.primaryContainer),
                                    Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodySmall)
                                if(index<results.lastIndex)HorizontalDivider(color=MaterialTheme.colorScheme.primary.copy(alpha=.12f))
                            }
                        }}
                    }
                }
            }
            if(snapshot.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(snapshot.error!=null){Text(snapshot.error!!,color=MaterialTheme.colorScheme.error);TextButton({retry++}){Text("Повторить подключение")}}
            else if(snapshot.fromCache)Text("Локальная копия · ожидаем соединение",style=MaterialTheme.typography.labelSmall)
            readFailure?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)}
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalChatGallery provides gallery){
                LazyColumn(Modifier.fillMaxSize(),state=state,contentPadding=PaddingValues(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(snapshot.messages.size>=messageLimit)item(key="older-messages"){TextButton({followBottom=false;messageLimit+=100}){Text("Предыдущие сообщения")}}
                    itemsIndexed(snapshot.messages,key={_,m->m.id}){index,message->
                        val previous=snapshot.messages.getOrNull(index-1)
                        val first=previous==null || previous.sender!=message.sender || message.time-previous.time>5*60*1000 || previous.deleted
                        if(previous==null || SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(Date(previous.time))!=SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(Date(message.time)))Text(SimpleDateFormat("d MMMM",Locale.forLanguageTag("ru")).format(Date(message.time)),Modifier.fillMaxWidth().padding(vertical=8.dp),style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        if(initialUnread==message.id)Text("Непрочитанные сообщения",Modifier.fillMaxWidth().padding(vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        Box(Modifier.fillMaxWidth().background(if(highlightedMessage==message.id)MaterialTheme.colorScheme.primary.copy(alpha=.14f)else Color.Transparent,RoundedCornerShape(16.dp))){
                        MessageBubble(message,personLabel(message.role),message.sender==chats.session.uid,
                            onDelete={chats.delete(message){failure=it}},onEdit={editingMessage=message},first=first,chats=chats,
                            onReply={reply=message},onQuote={jumpId=message.replyTo},onRetry={chats.retryMessage(message.id)},onError={failure=it})
                        }
                    }
                    item(key="chat-end"){Spacer(Modifier.height(1.dp).testTag("chat-end"))}
                }
                }
                if(state.canScrollForward)FilledTonalIconButton({followBottom=true;scope.launch{if(snapshot.messages.isNotEmpty())state.animateScrollToItem(bottomIndex())}},Modifier.align(Alignment.BottomEnd).padding(8.dp).alpha(.78f).semantics{contentDescription="К последним сообщениям"}){Icon(painterResource(R.drawable.ic_chat_latest),null,Modifier.size(21.dp))}
            }
            failure?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(!snapshot.accessDenied)reply?.let{quoted->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Ответ: ${richPlainText(quoted.text,quoted.format).take(90)}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=2);IconButton({reply=null}){Text("×")}}}
            if(!snapshot.accessDenied)Surface(shape=RoundedCornerShape(20.dp),color=winterSurface(MaterialTheme.colorScheme.surface),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().padding(vertical=7.dp)){
                ChatComposer(draft,::setDraft,::send,{attachmentOpenText=draft;attachments=true}) {
                    followBottom=true;scope.launch{if(snapshot.messages.isNotEmpty())state.animateScrollToItem(bottomIndex())}
                }
            }
        }
        if(!snapshot.accessDenied)gallery.Render()
        if(!snapshot.accessDenied)editingMessage?.let { message -> key(message.id) {
            MessageEditDialog(message,onDismiss={editingMessage=null},onSave={value->chats.edit(message,value){failure=it};editingMessage=null})
        } }
        if(attachments&&!snapshot.accessDenied)AttachmentPicker(target,chats,onSent={sentText,sentFormat->
            attachments=false;reply=null;followBottom=true
            if(draft==attachmentOpenText && richPlainText(draft,MARKDOWN_TEXT_FORMAT)==richPlainText(sentText,sentFormat))setDraft("")
        },onDismiss={attachments=false},replyTo=reply?.id.orEmpty(),initialText=attachmentOpenText,initialFormat=MARKDOWN_TEXT_FORMAT)
        if(!snapshot.accessDenied)sourceMessage?.let{m->AlertDialog(onDismissRequest={sourceMessage=null},title={Text("Исходное сообщение")},text={LinkText(if(m.deleted)"Сообщение удалено"else m.text,format=m.format)},confirmButton={TextButton({sourceMessage=null}){Text("Закрыть")}})}
    }
}

@Composable private fun ChatHeading(title:String,onBack:(()->Unit)?,onSearch:(()->Unit)?=null){Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){
    if(onBack!=null)IconButton(onBack){KotikiIcon(Glyph.LEFT)}
    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    if(onSearch!=null)IconButton(onSearch,Modifier.semantics{contentDescription="Поиск сообщений"}){Text("⌕",style=MaterialTheme.typography.headlineSmall)}
}}

@Composable internal fun MessageBubble(message:ChatMessage,senderName:String,own:Boolean,onDelete:()->Unit,onEdit:(()->Unit)?=null,
    first:Boolean=true,chats:CloudChats?=null,onReply:()->Unit={},onQuote:()->Unit={},onRetry:()->Unit={},onError:(String)->Unit={}){
    val colors=MaterialTheme.colorScheme;val context=LocalContext.current
    val createTask=LocalCreateTask.current;val createNote=LocalCreateNote.current
    var menu by remember{mutableStateOf(false)};var confirming by remember{mutableStateOf(false)}
    var quote by remember(message.replyTo){mutableStateOf<ChatMessage?>(null)}
    DisposableEffect(chats,message.replyTo){val stop=if(chats!=null && message.replyTo.isNotBlank())chats.observeMessage(message.chatId,message.replyTo){quote=it}else null;onDispose{stop?.invoke()}}
    var read by remember(message.id){mutableStateOf(false)}
    var reactions by remember(message.id){mutableStateOf<Map<String,List<String>>>(emptyMap())}
    var reacting by remember(message.id){mutableStateOf(false)}
    val winter= winterDesignEnabled()
    fun toggleReaction(emoji:String){if(own || reacting || chats==null)return;reacting=true;chats.react(message,emoji,winter,onError){reacting=false}}

    DisposableEffect(chats,message.id,message.pending){val stop=if(chats!=null && own && !message.pending)chats.observeRead(message){read=it}else null;onDispose{stop?.invoke()}}
    DisposableEffect(chats,message.id,message.pending){val stop=if(chats!=null && !message.pending && !message.deleted)chats.observeReactions(message){reactions=it}else null;onDispose{stop?.invoke()}}
    val metadata:@Composable ()->Unit={Row(verticalAlignment=Alignment.CenterVertically){
        Text((if(message.editedAt>0)"изменено · "else "")+SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(message.time)),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        if(own)Box(Modifier.width(24.dp),contentAlignment=Alignment.Center){Text(if(message.pending)"◷"else if(read)"✓✓"else"✓",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)}
        if(!message.deleted)Box{
            IconButton({menu=true},Modifier.size(32.dp).semantics{contentDescription="Действия: сообщение"}){KotikiIcon(Glyph.SETTINGS,Modifier.size(14.dp).alpha(.55f))}
            DropdownMenu(menu,{menu=false},modifier=Modifier.width(192.dp)){
                if(!own && !message.pending && chats!=null)ReactionPicker(reactions[chats.session.uid].orEmpty(),winter,reacting,::toggleReaction)
                DropdownMenuItem({Text("Ответить")},{menu=false;onReply()},enabled=!message.pending)
                DropdownMenuItem({Text("Копировать")},{menu=false;context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Сообщение",richPlainText(message.text,message.format)))})
                if(createTask!=null)DropdownMenuItem({Text("Создать задачу")},{menu=false;createTask(richPlainText(message.text,message.format)+"\n\nkotiki://chat/${message.chatId}/message/${message.id}")})
                if(createNote!=null)DropdownMenuItem({Text("Создать заметку")},{menu=false;createNote(richPlainText(message.text,message.format))})
                if(own && !message.pending){if(onEdit!=null)DropdownMenuItem({Text("Редактировать")},{menu=false;onEdit.invoke()});DropdownMenuItem({Text("Удалить")},{menu=false;confirming=true})}
            }
        }
    }}
    val body:@Composable ()->Unit={
        Column {
            if(message.replyTo.isNotBlank())Text("↩ "+(quote?.takeIf{!it.deleted}?.let{richPlainText(it.text,it.format).take(120)} ?: "Исходное сообщение недоступно"),Modifier.clickable(onClick=onQuote).padding(vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=colors.primary)
            if(message.attachmentIds.isNotEmpty() && chats!=null && !message.deleted)MessageAttachments(message,chats)
            if(first)SelectableMessageText(message,Modifier.padding(vertical=5.dp))
            else InlineMessageMeta({SelectableMessageText(message,Modifier.padding(vertical=5.dp))},metadata)
            if(message.error.isNotEmpty())TextButton(onRetry){Text("Не отправлено · Повторить",color=colors.error)}

        }
    }
    val visibleReactions=reactions.values.flatten().distinct().take(3)
    Row(Modifier.fillMaxWidth().padding(start=if(own)22.dp else 0.dp,end=if(own)0.dp else 22.dp).padding(bottom=if(visibleReactions.isNotEmpty())14.dp else 0.dp),horizontalArrangement=if(own)Arrangement.End else Arrangement.Start){
        Box {
        Surface(Modifier.widthIn(max=350.dp).testTag("message-bubble-${message.id}"),shape=RoundedCornerShape(16.dp),color=if(winterDesignEnabled())androidx.compose.ui.graphics.Color(if(own)0xFFF1E6D3 else 0xFFD9ECE2).copy(alpha=.94f) else winterSurface(if(own)colors.primaryContainer else colors.secondaryContainer)){
            Box(Modifier.padding(horizontal=10.dp,vertical=3.dp)){
                if(first)FirstMessageContent(senderName,message.role,metadata,body) else body()
            }
        }
        if(visibleReactions.isNotEmpty())Row(Modifier.align(Alignment.BottomEnd).offset(x=(-10).dp,y=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            visibleReactions.forEach { emoji ->
                Surface(shape=CircleShape,color=colors.surface,border=androidx.compose.foundation.BorderStroke(1.dp,colors.primary.copy(alpha=if(own).25f else .65f))) {
                    Box(Modifier.size(26.dp).then(if(!own)Modifier.clickable(enabled=!reacting){toggleReaction(emoji)} else Modifier)
                        .semantics{contentDescription=if(own)"Реакция собеседника $emoji"else "Убрать реакцию $emoji"},contentAlignment=Alignment.Center) {
                        Text(emoji,style=MaterialTheme.typography.bodySmall.copy(fontSize=14.sp,lineHeight=14.sp,platformStyle=PlatformTextStyle(includeFontPadding=false)))
                    }
                }
            }
        }
        }
    }
    if(confirming)AlertDialog(onDismissRequest={confirming=false},text={Text("Удалить сообщение у обоих участников?")},confirmButton={TextButton({confirming=false;onDelete()}){Text("Удалить")}},dismissButton={TextButton({confirming=false}){Text("Отмена")}})
}

/** Measure native text once with normal constraints; AndroidView has no intrinsic measurement. */
@Composable private fun FirstMessageContent(senderName:String,role:Person,metadata:@Composable ()->Unit,body:@Composable ()->Unit){
    val colors=MaterialTheme.colorScheme
    Layout(content={
        KotikiIcon(if(role==Person.USER_2)Glyph.CAT_GIRL else Glyph.CAT,Modifier.size(17.dp))
        Text(senderName,style=MaterialTheme.typography.labelMedium,color=colors.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
        Box{metadata()}
        HorizontalDivider(color=colors.primary.copy(alpha=.12f))
        Box{body()}
    }){children,constraints->
        val loose=constraints.copy(minWidth=0,minHeight=0)
        val icon=children[0].measure(loose)
        val meta=children[2].measure(loose)
        val gap=4.dp.roundToPx()
        val name=children[1].measure(loose.copy(maxWidth=(loose.maxWidth-icon.width-meta.width-gap*2).coerceAtLeast(0)))
        val content=children[4].measure(loose)
        val width=constraints.constrainWidth(maxOf(icon.width+gap+name.width+gap+meta.width,content.width))
        // Only the divider fills the final measured width; it cannot stretch a short bubble.
        val divider=children[3].measure(loose.copy(minWidth=width,maxWidth=width))
        val headerHeight=maxOf(icon.height,name.height,meta.height)
        val height=constraints.constrainHeight(headerHeight+divider.height+content.height)
        layout(width,height){
            icon.placeRelative(0,(headerHeight-icon.height)/2)
            name.placeRelative(icon.width+gap,(headerHeight-name.height)/2)
            meta.placeRelative(width-meta.width,(headerHeight-meta.height)/2)
            divider.placeRelative(0,headerHeight)
            content.placeRelative(0,headerHeight+divider.height)
        }
    }
}

@Composable private fun InlineMessageMeta(body:@Composable ()->Unit,meta:@Composable ()->Unit){
    Layout(content={Box{body()};Box{meta()}}){children,constraints->
        val loose=constraints.copy(minWidth=0,minHeight=0);val m=children[1].measure(loose);val b=children[0].measure(loose)
        val gap=6.dp.roundToPx();val inline=b.width+m.width+gap<=constraints.maxWidth
        val width=if(inline)b.width+m.width+gap else maxOf(b.width,m.width);val height=if(inline)maxOf(b.height,m.height)else b.height+m.height
        layout(constraints.constrainWidth(width),constraints.constrainHeight(height)){b.placeRelative(0,0);m.placeRelative(width-m.width,if(inline)(height-m.height)/2 else b.height)}
    }
}

/** Owned by the screen, not a recycled LazyColumn item. IME scrolling cannot dispose it. */
@Composable internal fun MessageEditDialog(message:ChatMessage,onDismiss:()->Unit,onSave:(String)->Unit) {
    var text by rememberSaveable(message.id){mutableStateOf(message.text)}
    var format by rememberSaveable(message.id){mutableStateOf(message.format)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить сообщение")},
        text={RichTextEditor(text,{text=it;format=MARKDOWN_TEXT_FORMAT},format=format,maxLength=4000,label="Текст сообщения")},
        confirmButton={TextButton({onSave(if(format==PLAIN_TEXT_FORMAT)RichTextCodec.escape(text)else text)},enabled=text.isNotBlank()){Text("Сохранить")}},
        dismissButton={TextButton(onDismiss){Text("Отмена")}})
}

@Composable internal fun FollowChatBottom(state:androidx.compose.foundation.lazy.LazyListState,enabled:Boolean) {
    LaunchedEffect(enabled,state) {
        if(enabled)snapshotFlow { Triple(state.layoutInfo.totalItemsCount,state.layoutInfo.viewportEndOffset,
            state.layoutInfo.visibleItemsInfo.map { Triple(it.key,it.offset,it.size) }) }
            .collect { if(state.canScrollForward)state.scrollToItem((state.layoutInfo.totalItemsCount-1).coerceAtLeast(0)) }
    }
}



internal fun chatSearchExcerpt(text:String,query:String,maxLength:Int=180):String {
    if(text.length<=maxLength)return text
    val hit=if(query.isBlank())0 else text.indexOf(query,ignoreCase=true).coerceAtLeast(0)
    val start=(hit-45).coerceIn(0,(text.length-maxLength).coerceAtLeast(0))
    val end=(start+maxLength).coerceAtMost(text.length)
    return (if(start>0)"…"else "")+text.substring(start,end)+(if(end<text.length)"…"else "")
}
internal fun chatSearchHighlighted(text:String,query:String,color:Color):AnnotatedString {
    val builder=AnnotatedString.Builder(text)
    if(query.isNotBlank()) {
        var start=text.indexOf(query,ignoreCase=true)
        while(start>=0){builder.addStyle(SpanStyle(background=color,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold),start,start+query.length);start=text.indexOf(query,start+query.length,ignoreCase=true)}
    }
    return builder.toAnnotatedString()
}
