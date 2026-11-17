package team.kotiki.app

import com.google.firebase.firestore.*
import java.io.Serializable

data class ChatTarget(val id:String,val title:String,val collectionId:String="",val itemId:String=""):Serializable

data class ChatMessage(
    val id:String,val chatId:String,val sender:String,val actor:String,val role:Person,
    val text:String,val time:Long,val deleted:Boolean,val pending:Boolean,
)
data class ChatSnapshot(
    val messages:List<ChatMessage> = emptyList(),val loading:Boolean=true,
    val fromCache:Boolean=false,val pending:Boolean=false,val error:String?=null,
)

/** Text chat. Authentication and the canonical participant identity stay separate. */
class CloudChats(val session:CloudSession,private val onPending:(Boolean)->Unit) {
    private val db=FirebaseFirestore.getInstance()
    private val root=db.collection("spaces").document(session.spaceId).collection("chats")
    private val listeners=mutableMapOf<String,ListenerRegistration>()
    private val snapshotPending=mutableMapOf<String,Boolean>()
    private val knownChats=mutableSetOf<String>()
    private var initialDrain=true
    private var operations=0
    private var closed=false

    init {
        pendingChanged()
        // Includes writes persisted before the current process started, even for a closed chat.
        db.waitForPendingWrites().addOnCompleteListener {
            if(!closed){initialDrain=false;pendingChanged()}
        }
    }
    private fun pendingChanged(){if(!closed)onPending(initialDrain || operations>0 || snapshotPending.values.any{it})}
    private fun begin(){operations++;pendingChanged()}
    private fun end(){operations=(operations-1).coerceAtLeast(0);pendingChanged()}
    private fun valid(target:ChatTarget)=target.id=="general" && target.collectionId.isEmpty() && target.itemId.isEmpty() ||
        target.collectionId.isNotBlank() && target.itemId.isNotBlank() &&
            target.id=="plan_${target.collectionId}_${target.itemId}" && !target.id.contains('/') ||
        target.collectionId.isEmpty() && target.itemId.isNotBlank() && target.id=="entry_${target.itemId}" && !target.id.contains('/')

    private fun ensureChat(target:ChatTarget,ready:()->Unit,failed:(Exception?)->Unit) {
        if(closed)return
        if(!valid(target)){failed(IllegalArgumentException("Invalid chat"));return}
        if(target.id in knownChats){ready();return}
        val ref=root.document(target.id)
        fun available(){if(!closed){knownChats.add(target.id);ready()}}
        fun createOrRead() {
            db.runTransaction { transaction->
                val old=transaction.get(ref)
                if(!old.exists())transaction.set(ref,mapOf(
                    "type" to if(target.id=="general")"general" else if(target.id.startsWith("entry_"))"entry" else "plan",
                    "collectionId" to target.collectionId,"itemId" to target.itemId,
                    "createdBy" to session.uid,"createdAt" to FieldValue.serverTimestamp()))
                true
            }.addOnSuccessListener{
                if(!closed){
                    available()
                    // Transaction reads need not populate the persistent document cache.
                    ref.get(Source.SERVER)
                }
            }.addOnFailureListener{if(!closed)failed(it)}
        }
        fun readServer() {
            ref.get(Source.SERVER).addOnSuccessListener{document->
                if(!closed){if(document.exists())available() else createOrRead()}
            }.addOnFailureListener{if(!closed)failed(it)}
        }
        // Cache metadata explicitly so previously opened rooms also work after an offline restart.
        ref.get(Source.CACHE).addOnSuccessListener{cached->
            if(!closed){if(cached.exists())available() else readServer()}
        }.addOnFailureListener{if(!closed)readServer()}
    }

    fun observe(target:ChatTarget,onState:(ChatSnapshot)->Unit):()->Unit {
        val token=newId()
        var active=true
        var last=ChatSnapshot()
        begin()
        ensureChat(target,{
            end()
            if(active && !closed)listeners[token]=root.document(target.id).collection("messages")
                .orderBy("createdAt",Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener(MetadataChanges.INCLUDE){snapshot,error->
                    if(!active || closed)return@addSnapshotListener
                    if(error!=null){
                        snapshotPending.remove(token);pendingChanged()
                        val visible=if(error.code==FirebaseFirestoreException.Code.PERMISSION_DENIED)emptyList() else last.messages
                        onState(last.copy(messages=visible,loading=false,pending=false,error=messageFor(error)))
                        return@addSnapshotListener
                    }
                    if(snapshot==null)return@addSnapshotListener
                    val messages=snapshot.documents.mapNotNull{document->runCatching {
                        ChatMessage(document.id,target.id,document.getString("sender")!!,document.getString("actor")!!,
                            Person.valueOf(document.getString("role")!!),document.getString("text") ?: "",
                            document.getTimestamp("createdAt",DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time
                                ?: document.getLong("clientTime") ?: 0L,
                            document.getBoolean("deleted") ?: false,document.metadata.hasPendingWrites())
                    }.getOrNull()}.sortedWith(compareBy<ChatMessage>{it.time}.thenBy{it.id})
                    snapshotPending[token]=snapshot.metadata.hasPendingWrites();pendingChanged()
                    last=ChatSnapshot(messages,false,snapshot.metadata.isFromCache,snapshot.metadata.hasPendingWrites())
                    onState(last)
                }
        },{error->end();if(active && !closed)onState(last.copy(loading=false,error=messageFor(error)))})
        return {active=false;listeners.remove(token)?.remove();snapshotPending.remove(token);pendingChanged()}
    }

    fun send(target:ChatTarget,text:String,onQueued:()->Unit,onFailure:(String)->Unit) {
        if(closed){onFailure("Чат закрыт. Открой его снова.");return}
        val body=text.trim()
        if(body.isEmpty() || body.length>4000){onFailure("Сообщение должно содержать от 1 до 4000 символов.");return}
        begin()
        ensureChat(target,{
            val payload=mapOf("sender" to session.uid,"actor" to session.authUid,"role" to session.viewer.name,
                "text" to body,"createdAt" to FieldValue.serverTimestamp(),"clientTime" to System.currentTimeMillis(),"deleted" to false)
            val message=root.document(target.id).collection("messages").document()
            val task=db.batch().set(message,payload).set(root.parent!!.collection("pushQueue").document(message.id),mapOf(
                "kind" to "chat","recordId" to "","revision" to "","chatId" to target.id,"messageId" to message.id,
                "actor" to session.authUid,"createdAt" to FieldValue.serverTimestamp())).commit()
            onQueued()
            task.addOnCompleteListener {result->
                if(!closed){end();if(!result.isSuccessful)onFailure(messageFor(result.exception)) else AndroidPush.wake(com.google.firebase.FirebaseApp.getInstance().applicationContext)}
            }
        },{error->end();onFailure(messageFor(error))})
    }

    fun delete(message:ChatMessage,onFailure:(String)->Unit) {
        if(closed || message.sender!=session.uid || message.deleted)return
        begin()
        root.document(message.chatId).collection("messages").document(message.id)
            .update(mapOf("text" to "","deleted" to true)).addOnCompleteListener{result->
                if(!closed){end();if(!result.isSuccessful)onFailure(messageFor(result.exception))}
            }
    }
    private fun messageFor(error:Exception?):String = when((error as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED->"Чат недоступен: проверь доступ к пространству или существование плана."
        FirebaseFirestoreException.Code.UNAVAILABLE->"Нет соединения. Новый чат нужно один раз открыть с интернетом; черновик сохранён."
        else->"Не удалось выполнить действие. Черновик сохранён; попробуй снова."
    }
    fun close(){closed=true;listeners.values.forEach{it.remove()};listeners.clear();snapshotPending.clear()}
}
