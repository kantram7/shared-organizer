package team.kotiki.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One local read cursor per account, space and event. Viewing an event never advances it. */
internal class EntryChatReads(private val context:Context,private val viewer:String,private val profile:String) {
    private val states=mutableMapOf<String,ChatUnreadState>()
    fun state(id:String)=states.getOrPut(id){ChatUnreadState(context,viewer,"$profile/entry-chat/$id")}
    fun count(id:String)=states[id]?.count ?: 0
}
internal val LocalEntryChatReads=staticCompositionLocalOf<EntryChatReads?>{null}

@Composable internal fun ObserveEntryChats(chats:CloudChats?,reads:EntryChatReads?,entries:List<Entry>,viewer:Person) {
    if(chats==null || reads==null)return
    entries.filter{it.visibility==Visibility.SHARED && it.visibleTo(viewer)}.forEach { entry ->
        key(chats,entry.id) {
            val state=reads.state(entry.id)
            DisposableEffect(chats,entry.id,state.since) {
                val stop=chats.observeExisting(ChatTarget("entry_${entry.id}",entry.title,itemId=entry.id),state.since,state::receive)
                onDispose{stop()}
            }
        }
    }
}

@Composable internal fun ChatCountBadge(count:Int) {
    if(count>0)Badge(Modifier.semantics{contentDescription="Непрочитанных сообщений: ${if(count>99)"больше 99" else count}"}) {
        Text(if(count>99)"99+" else count.toString())
    }
}
@Composable internal fun EntryChatBadge(entry:Entry,onOpen:()->Unit) {
    val count=if(entry.visibility==Visibility.SHARED)LocalEntryChatReads.current?.count(entry.id) ?: 0 else 0
    if(count>0)Row(Modifier.clickable(onClick=onOpen).padding(horizontal=4.dp,vertical=2.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        KotikiIcon(Glyph.CHAT,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));ChatCountBadge(count)
    }
}

internal fun eventIdFromChat(target:GatheringNotificationTarget):String? =
    target.chatId.takeIf{it.startsWith("entry_") && it.length>6 && '/' !in it && target.collectionId.isBlank()}
        ?.removePrefix("entry_")
