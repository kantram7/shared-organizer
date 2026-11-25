package team.kotiki.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class HistoryNotification(val id:String,val title:String,val body:String,val kind:String,val time:Long,val expires:Long,val read:Boolean,val target:GatheringNotificationTarget,val messageId:String="",val authorUid:String="",val authorRole:Person?=null)

/** Server-authored history; the client can only acknowledge read=true. */
@Composable fun NotificationHistoryDialog(session:CloudSession,onDismiss:()->Unit,onOpen:(GatheringNotificationTarget)->Unit,store:DemoStore?=null,chats:CloudChats?=null) {
    var rows by remember(session){mutableStateOf<List<HistoryNotification>>(emptyList())}
    var loading by remember(session){mutableStateOf(true)}
    var error by remember(session){mutableStateOf<String?>(null)}
    val root=remember(session){FirebaseFirestore.getInstance().collection("spaces").document(session.spaceId).collection("notifications").document(session.uid).collection("items")}
    DisposableEffect(session){
        val listener=root.whereGreaterThan("expiresAt",Timestamp.now()).orderBy("expiresAt",Query.Direction.DESCENDING).limit(200)
            .addSnapshotListener{snapshot,failure->
                loading=false
                if(failure!=null){rows=emptyList();error="Не удалось загрузить историю уведомлений";return@addSnapshotListener}
                error=null
                rows=snapshot?.documents.orEmpty().mapNotNull{d->
                    val created=d.getTimestamp("createdAt")?.toDate()?.time?:return@mapNotNull null
                    val expires=d.getTimestamp("expiresAt")?.toDate()?.time?:return@mapNotNull null
                    HistoryNotification(d.id,d.getString("title")?:"Kotiki team",d.getString("body").orEmpty(),d.getString("kind").orEmpty(),created,expires,d.getBoolean("read")==true,
                        GatheringNotificationTarget(d.getString("recordId").orEmpty().removePrefix("entry_"),authUid=session.authUid,chatId=d.getString("chatId").orEmpty(),collectionId=d.getString("collectionId").orEmpty(),itemId=d.getString("itemId").orEmpty()),d.getString("messageId").orEmpty(),d.getString("authorUid").orEmpty(),runCatching{Person.valueOf(d.getString("authorRole").orEmpty())}.getOrNull())
                }.sortedByDescending{it.time}
            }
        onDispose{listener.remove()}
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        EditorSurface(Modifier.fillMaxWidth().fillMaxHeight(.92f).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),shape=MaterialTheme.shapes.extraLarge){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("Уведомления",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);IconButton(onDismiss,Modifier.semantics{contentDescription="Закрыть уведомления"}){KotikiIcon(Glyph.CLOSE)}}
                Text("За последние 30 дней",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                val visible=rows.filter{it.expires>System.currentTimeMillis()}
                if(!loading && visible.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text("Пока нет уведомлений",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    items(visible,key={it.id}){item->
                        Surface(Modifier.fillMaxWidth().clickable{
                            root.document(item.id).update("read",true).addOnFailureListener{error="Не удалось отметить уведомление прочитанным"}
                            rows=rows.map{if(it.id==item.id)it.copy(read=true)else it}
                            onOpen(item.target)
                        },shape=MaterialTheme.shapes.medium,
                            color=if(item.read)MaterialTheme.colorScheme.surface.copy(alpha=.65f)else MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f),
                            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))){
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                KotikiIcon(if(item.kind=="chat")Glyph.CHAT else Glyph.CALENDAR,Modifier.size(20.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                    HistoryLiveContent(item,session,store,chats)
                                    Text(Instant.ofEpochMilli(item.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(!item.read)Badge(Modifier.semantics{contentDescription="Новое уведомление"})
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The history itself stores no message text or private title. Details have a live source. */
@Composable private fun HistoryLiveContent(item:HistoryNotification,session:CloudSession,store:DemoStore?,chats:CloudChats?) {
    val entryId=if(item.target.chatId.startsWith("entry_"))item.target.chatId.removePrefix("entry_")else item.target.id
    val entry=store?.entries?.firstOrNull{it.id==entryId && it.visibleTo(session.viewer)}?.redactFor(session.viewer)
    val chatAllowed=when {
        item.target.chatId=="general"->true
        item.target.chatId.startsWith("entry_")->entry?.visibility==Visibility.SHARED
        item.target.chatId.startsWith("plan_")->store?.plans?.any{group->group.id==item.target.collectionId && (group.shared || group.owner==session.viewer) && group.items.any{it.id==item.target.itemId}}==true
        else->false
    }
    var message by remember(session.authUid,item.id,item.messageId){mutableStateOf<ChatMessage?>(null)}
    var resolved by remember(session.authUid,item.id,item.messageId){mutableStateOf(false)}
    DisposableEffect(session.authUid,chats,item.target.chatId,item.messageId,chatAllowed){
        message=null;resolved=false
        var active=true
        val stop=if(item.kind=="chat" && item.messageId.isNotBlank() && chatAllowed && chats!=null)
            chats.observeMessage(item.target.chatId,item.messageId){value->if(active){message=value?.takeUnless{it.deleted};resolved=true}}
        else null
        onDispose{active=false;stop?.invoke()}
    }
    // Apply parent privacy immediately, before a disposed message listener can clear its state.
    val visibleMessage=message?.takeIf{chatAllowed && it.chatId==item.target.chatId && it.id==item.messageId && !it.deleted}
    val role=visibleMessage?.role ?: session.roles[item.authorUid] ?: item.authorRole
    val author=role?.takeUnless{it==Person.BOTH}?.let{store?.identity?.names?.get(it) ?: session.names[it] ?: it.label}
    author?.let{Text(it,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
    Text(item.title,style=MaterialTheme.typography.titleSmall)
    if(item.kind=="chat") {
        val preview=when {
            visibleMessage!=null && visibleMessage.text.isNotBlank()->richPlainText(visibleMessage.text,visibleMessage.format).replace(Regex("\\s+")," ").take(240)
            visibleMessage!=null && visibleMessage.attachmentIds.isNotEmpty()->"Вложения: ${visibleMessage.attachmentIds.size}"
            resolved || !chatAllowed->"Сообщение недоступно"
            else->item.body
        }
        if(preview.isNotBlank())Text(preview,style=MaterialTheme.typography.bodySmall,maxLines=4,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    } else {
        entry?.let{richPlainText(it.title,it.titleFormat)}?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
        if(item.body.isNotBlank())Text(item.body,style=MaterialTheme.typography.bodySmall)
    }
}
