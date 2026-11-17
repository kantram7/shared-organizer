package team.kotiki.app

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun ChatScreen(target:ChatTarget,chats:CloudChats?,onBack:(()->Unit)?=null) {
    if(chats==null) {
        Column(Modifier.fillMaxSize()) {
            ChatHeading(target.title,onBack,if(target.collectionId.isNotBlank())"Назад к планам" else "Назад")
            Text("Войди в облако для переписки",Modifier.padding(vertical=20.dp))
        }
        return
    }
    key(chats.session.uid,chats.session.spaceId,target.id) {
        NotificationReadEffect(chats.session.authUid,NotificationReadState.chat(target.id))
        val context=LocalContext.current
        val prefs=remember{context.getSharedPreferences("chat_drafts_${chats.session.uid}",0)}
        val draftKey="${chats.session.spaceId}/${target.id}"
        var draft by rememberSaveable{mutableStateOf(prefs.getString(draftKey,"") ?: "")}
        var sending by remember{mutableStateOf(false)}
        var sendError by remember{mutableStateOf<String?>(null)}
        var recoverText by rememberSaveable{mutableStateOf("")}
        var retry by remember{mutableIntStateOf(0)}
        var snapshot by remember{mutableStateOf(ChatSnapshot())}
        var deleting by remember{mutableStateOf<ChatMessage?>(null)}
        val listState=rememberLazyListState()
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
        DisposableEffect(chats,target.id,retry) {
            val stop=chats.observe(target){snapshot=it}
            onDispose{stop()}
        }
        LaunchedEffect(snapshot.messages.lastOrNull()?.id) {
            if(snapshot.messages.isNotEmpty())listState.animateScrollToItem(snapshot.messages.lastIndex)
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            ChatHeading(target.title,onBack,if(target.collectionId.isNotBlank())"Назад к планам" else "Назад")
            Text(when {
                snapshot.loading->"Открываем чат…"
                snapshot.error!=null->snapshot.error!!
                snapshot.pending->"Сообщения ожидают отправки"
                snapshot.fromCache->"Локальная копия · ожидаем соединение"
                else->"Сообщения синхронизированы"
            },style=MaterialTheme.typography.labelSmall,color=if(snapshot.error==null)colors.onSurfaceVariant else colors.error)
            if(snapshot.error!=null)TextButton({retry++}){Text("Повторить подключение")}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(!snapshot.loading && snapshot.messages.isEmpty())item {
                    Text("Здесь можно обсудить ${if(target.id=="general")"ваши планы и дела" else if(target.collectionId.isNotBlank())"этот план" else "эту запись"}.",color=colors.onSurfaceVariant)
                }
                items(snapshot.messages,key={it.id}){message->
                    val own=message.sender==chats.session.uid
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(own)Arrangement.End else Arrangement.Start) {
                        Surface(Modifier.widthIn(max=320.dp).fillMaxWidth(.94f),shape=RoundedCornerShape(14.dp),
                            color=if(own)colors.primaryContainer else colors.secondaryContainer) {
                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    KotikiIcon(if(message.role==Person.USER_2)Glyph.CAT_GIRL else Glyph.CAT,Modifier.size(20.dp))
                                    Text(chats.session.names[message.role] ?: message.role.label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                                    Text(SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(Date(message.time)),style=MaterialTheme.typography.labelSmall)
                                }
                                Text(if(message.deleted)"Сообщение удалено" else message.text,
                                    color=if(message.deleted)colors.onSurfaceVariant else colors.onSurface)
                                if(message.pending)Text("Ожидает отправки",style=MaterialTheme.typography.labelSmall)
                                if(own && !message.deleted)TextButton({deleting=message},enabled=!message.pending,
                                    modifier=Modifier.align(Alignment.End).semantics{contentDescription="Удалить сообщение ${message.id}"},
                                    contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)){Text("Удалить",style=MaterialTheme.typography.labelSmall)}
                            }
                        }
                    }
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
        deleting?.let{message->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить сообщение?")},
            text={Text("Вместо текста останется отметка об удалении у обоих участников.")},
            confirmButton={TextButton({deleting=null;chats.delete(message){sendError=it}}){Text("Удалить")}},
            dismissButton={TextButton({deleting=null}){Text("Отмена")}})}
    }
}

@Composable private fun ChatHeading(title:String,onBack:(()->Unit)?,backLabel:String) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        if(onBack!=null)IconButton(onBack,Modifier.semantics{contentDescription=backLabel}){KotikiIcon(Glyph.LEFT)}
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    }
}
