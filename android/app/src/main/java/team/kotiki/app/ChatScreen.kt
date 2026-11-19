package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun ChatScreen(target:ChatTarget,chats:CloudChats?,onBack:(()->Unit)?=null,header:(@Composable ()->Unit)?=null) {
    if(chats==null) {
        Column(Modifier.fillMaxSize()) {
            ChatHeading(target.title,onBack,if(target.collectionId.isNotBlank())"Назад к планам" else "Назад")
            header?.invoke()
            Text("Войди в облако для переписки",Modifier.padding(vertical=20.dp))
        }
        return
    }
    key(chats.session.uid,chats.session.spaceId,target.id) {

        val context=LocalContext.current
        val prefs=remember{context.getSharedPreferences("chat_drafts_${chats.session.uid}",0)}
        val draftKey="${chats.session.spaceId}/${target.id}"
        var draft by rememberSaveable{mutableStateOf(prefs.getString(draftKey,"") ?: "")}
        var sending by remember{mutableStateOf(false)}
        var sendError by remember{mutableStateOf<String?>(null)}
        var recoverText by rememberSaveable{mutableStateOf("")}
        var retry by remember{mutableIntStateOf(0)}
        var messageLimit by rememberSaveable{mutableIntStateOf(100)}
        var snapshot by remember{mutableStateOf(ChatSnapshot())}
        val listState=rememberLazyListState()
        val unread=if(target.id=="general")LocalChatUnread.current else if(target.id.startsWith("entry_"))LocalEntryChatReads.current?.state(target.itemId.ifBlank{target.id.removePrefix("entry_")}) else null
        val lifecycle=LocalLifecycleOwner.current.lifecycle
        var resumed by remember{mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
        DisposableEffect(lifecycle){
            val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
            lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
        }
        val visibleKeys by remember{derivedStateOf{listState.layoutInfo.visibleItemsInfo.map{it.key.toString()}.toSet()}}
        LaunchedEffect(resumed,snapshot.messages,visibleKeys){
            if(resumed && !snapshot.loading && snapshot.error==null)
                unread?.readThrough(snapshot.messages.filter{it.id in visibleKeys})
        }
        if(!snapshot.loading && snapshot.error==null)NotificationReadEffect(chats.session.authUid,NotificationReadState.chat(target.id))
        val colors=MaterialTheme.colorScheme
        fun setDraft(value:String){draft=value;prefs.edit().putString(draftKey,value).apply()}
        fun send() {
            if(sending || draft.isBlank())return
            val original=draft
            sending=true;sendError=null
            chats.send(target,original,onQueued={
                sending=false
                if(draft==original)setDraft("")
                recoverText=""
            },onFailure={error->
                sending=false;sendError=error
                if(draft.isBlank())setDraft(original) else if(draft!=original)recoverText=original
            })
        }
        DisposableEffect(chats,target.id,retry,messageLimit) {
            val stop=chats.observe(target,messageLimit=messageLimit){snapshot=it}
            onDispose{stop()}
        }
        LaunchedEffect(snapshot.messages.lastOrNull()?.id) {
            if(snapshot.messages.isNotEmpty())listState.animateScrollToItem(snapshot.messages.lastIndex+if(snapshot.messages.size>=messageLimit)1 else 0)
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            ChatHeading(target.title,onBack,if(target.collectionId.isNotBlank())"Назад к планам" else "Назад")
            header?.invoke()
            Text(when {
                snapshot.loading->"Открываем чат…"
                snapshot.error!=null->snapshot.error!!
                snapshot.pending->"Сообщения ожидают отправки"
                snapshot.fromCache->"Локальная копия · ожидаем соединение"
                else->"Сообщения синхронизированы"
            },style=MaterialTheme.typography.labelSmall,color=if(snapshot.error==null)colors.onSurfaceVariant else colors.error)
            if(snapshot.error!=null)TextButton({retry++}){Text("Повторить подключение")}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(snapshot.messages.size>=messageLimit)item(key="older-messages") {
                    OutlinedButton({messageLimit+=100},Modifier.fillMaxWidth()){Text("Предыдущие сообщения")}
                }
                if(!snapshot.loading && snapshot.messages.isEmpty())item {
                    Text("Здесь можно обсудить ${if(target.id=="general")"ваши планы и дела" else if(target.collectionId.isNotBlank())"этот план" else "эту запись"}.",color=colors.onSurfaceVariant)
                }
                items(snapshot.messages,key={it.id}){message->
                    val own=message.sender==chats.session.uid
                    MessageBubble(message,personLabel(message.role),own,
                        onDelete={chats.delete(message){sendError=it}},onEdit={text->chats.edit(message,text){sendError=it}})

                }
            }
            sendError?.let{Text(it,color=colors.error,style=MaterialTheme.typography.bodySmall)}
            if(recoverText.isNotEmpty())TextButton({setDraft(recoverText);recoverText=""}){Text("Вернуть неотправленный текст")}
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
                OutlinedTextField(draft,{setDraft(it.take(4000))},Modifier.weight(1f).semantics{contentDescription="Текст сообщения"},
                    label={Text("Сообщение")},maxLines=5,enabled=!sending,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                Button({send()},enabled=!sending && draft.isNotBlank(),modifier=Modifier.heightIn(min=56.dp).semantics{contentDescription="Отправить сообщение"},
                    contentPadding=PaddingValues(horizontal=12.dp)){Text(if(sending)"…" else "Отправить")}
            }
        }

    }
}

@Composable private fun ChatHeading(title:String,onBack:(()->Unit)?,backLabel:String) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        if(onBack!=null)IconButton(onBack,Modifier.semantics{contentDescription=backLabel}){KotikiIcon(Glyph.LEFT)}
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    }
}

@Composable internal fun MessageBubble(message:ChatMessage,senderName:String,own:Boolean,onDelete:()->Unit,onEdit:((String)->Unit)?=null) {
    val colors=MaterialTheme.colorScheme
    val actions=rememberItemMenu()
    val actionable=own && !message.deleted && !message.pending
    val context=LocalContext.current
    val copy:()->Unit={context.getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("Сообщение",message.text))}
    var editing by rememberSaveable{mutableStateOf(false)}
    var text by rememberSaveable{mutableStateOf("")}
    Row(Modifier.fillMaxWidth().padding(start=if(own)24.dp else 0.dp,end=if(own)0.dp else 24.dp),horizontalArrangement=if(own)Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max=340.dp).width(IntrinsicSize.Max).winterIcicles(message.id)
            .combinedClickable(onClick={},onLongClick={if(!message.deleted)actions.expanded=true}),
            shape=RoundedCornerShape(16.dp),color=winterSurface(if(own)colors.primaryContainer else colors.secondaryContainer)) {
            Column(Modifier.padding(horizontal=10.dp,vertical=4.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    KotikiIcon(if(message.role==Person.USER_2)Glyph.CAT_GIRL else Glyph.CAT,Modifier.size(18.dp))
                    Text(senderName,Modifier.padding(start=4.dp).weight(1f),style=MaterialTheme.typography.labelMedium,color=colors.primary,maxLines=1)
                    if(!message.deleted)ItemActions("сообщение",if(actionable)onDelete else null,actions,
                        onEdit=if(actionable && onEdit!=null){{text=message.text;editing=true}}else null,
                        deleteMessage="У обоих участников останется отметка об удалении сообщения.",compactGear=true,onCopy=copy)
                    else Spacer(Modifier.height(28.dp))
                }
                HorizontalDivider(color=colors.primary.copy(alpha=.15f))
                Row(Modifier.padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Bottom) {
                    LinkText(if(message.deleted)"Сообщение удалено" else message.text,Modifier.weight(1f,false),style=MaterialTheme.typography.bodyMedium,
                        color=if(message.deleted)colors.onSurfaceVariant else colors.onSurface)
                    Text(if(message.pending)"Отправляем…" else SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(Date(message.time)),
                        style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant,maxLines=1)
                }
            }
        }
    }
    if(editing)AlertDialog(onDismissRequest={editing=false},title={Text("Изменить сообщение")},
        text={OutlinedTextField(text,{text=it.take(4000)},Modifier.fillMaxWidth(),minLines=2,maxLines=8)},
        confirmButton={TextButton({onEdit?.invoke(text);editing=false},enabled=text.isNotBlank()){Text("Сохранить")}},
        dismissButton={TextButton({editing=false}){Text("Отмена")}})
}
