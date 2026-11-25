package team.kotiki.app

import com.google.firebase.firestore.*
import java.io.Serializable

data class ChatTarget(val id:String,val title:String,val collectionId:String="",val itemId:String=""):Serializable

data class ChatMessage(
    val id:String,val chatId:String,val sender:String,val actor:String,val role:Person,
    val text:String,val time:Long,val deleted:Boolean,val pending:Boolean,
    val editedAt:Long=0L,val replyTo:String="",val format:String="plain-v1",val attachmentIds:List<String> = emptyList(),val error:String="",val readByMe:Boolean=false,
)
data class ChatSnapshot(
    val messages:List<ChatMessage> = emptyList(),val loading:Boolean=true,
    val fromCache:Boolean=false,val pending:Boolean=false,val error:String?=null,val readStateReady:Boolean=true,val accessDenied:Boolean=false,
)

/** Text chat. Authentication and the canonical participant identity stay separate. */
class CloudChats(val session:CloudSession,private val onPending:(Boolean)->Unit) {
    private val db=FirebaseFirestore.getInstance()
    private val root=db.collection("spaces").document(session.spaceId).collection("chats")
    private val listeners=mutableMapOf<String,ListenerRegistration>()
    private val snapshotPending=mutableMapOf<String,Boolean>()
    private val knownChats=mutableSetOf<String>()
    private val deniedChats=mutableSetOf<String>()
    private var initialDrain=true
    private var operations=0
    private var closed=false
    private val context=com.google.firebase.FirebaseApp.getInstance().applicationContext
    private val outbox=ChatOutbox(context,session.uid,session.spaceId)
    private val outboxObservers=mutableMapOf<String,()->Unit>()
    private val sending=mutableSetOf<String>()
    private val ownReads=mutableMapOf<String,Boolean>()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val retryTick=object:Runnable {override fun run(){if(!closed){flush();handler.postDelayed(this,15000)}}}
    private fun changed(){outboxObservers.values.toList().forEach{it()}}
    private fun pendingMessage(m:PendingChatMessage)=ChatMessage(m.id,m.target.id,session.uid,session.authUid,session.viewer,m.text,m.time,false,!m.delivered,
        replyTo=m.replyTo,format=m.format,attachmentIds=m.attachmentIds,error=m.error)
    private fun parse(d:DocumentSnapshot,chat:String):ChatMessage?=runCatching {
        ChatMessage(d.id,chat,d.getString("sender")!!,d.getString("actor")!!,Person.valueOf(d.getString("role")!!),d.getString("text").orEmpty(),
            d.getTimestamp("createdAt",DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: d.getLong("clientTime") ?: 0L,
            d.getBoolean("deleted") ?: false,d.metadata.hasPendingWrites(),d.getTimestamp("editedAt")?.toDate()?.time ?: 0L,
            d.getString("replyTo").orEmpty(),d.getString("format") ?: "plain-v1",(d.get("attachmentIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList())
    }.getOrNull()

    init {
        handler.post(retryTick)
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

    internal fun ensureChat(target:ChatTarget,ready:()->Unit,failed:(Exception?)->Unit) {
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
        // After a confirmed revocation, cache cannot prove a subsequent grant.
        if(target.id in deniedChats){readServer();return}
        // Cache metadata explicitly so previously opened rooms also work after an offline restart.
        ref.get(Source.CACHE).addOnSuccessListener{cached->
            if(!closed){if(cached.exists())available() else readServer()}
        }.addOnFailureListener{if(!closed)readServer()}
    }

    fun observe(target:ChatTarget,since:Long?=null,messageLimit:Int=100,onState:(ChatSnapshot)->Unit):()->Unit {
        val token=newId()
        var active=true
        var last=ChatSnapshot(accessDenied=target.id in deniedChats)
        val receipts=mutableMapOf<String,ListenerRegistration>()
        val handoff=mutableMapOf<String,ChatMessage>()
        fun emit(){
            if(!active || closed)return
            if(last.accessDenied){handoff.clear();onState(last.copy(messages=emptyList(),pending=false));return}
            val pending=outbox.all().filter{it.target.id==target.id && last.messages.none{remote->remote.id==it.id}}.map(::pendingMessage)
            onState(last.copy(messages=mergeChatOutgoing(last.messages.map{it.copy(readByMe=ownReads["${target.id}/${it.id}"]==true)},pending,handoff),pending=last.pending || pending.any{it.pending && it.error.isEmpty()},readStateReady=last.messages.filter{it.sender!=session.uid && !it.deleted && !it.pending}.all{ownReads.containsKey("${target.id}/${it.id}")}))
        }
        fun denied(error:Exception?) {
            receipts.values.forEach{it.remove()};receipts.clear();knownChats.remove(target.id);deniedChats.add(target.id)
            ownReads.keys.filter{it.startsWith("${target.id}/")}.toList().forEach(ownReads::remove)
            last=ChatSnapshot(loading=false,error=messageFor(error),accessDenied=true);emit()
        }
        outboxObservers[token]=::emit
        emit()
        begin()
        ensureChat(target,{
            end()
            val base=root.document(target.id).collection("messages").orderBy("createdAt",Query.Direction.DESCENDING)
            // The unread listener only downloads messages at/after its persisted read cursor.
            // It is not capped at the last 100 messages shown in the conversation.
            val query=if(since==null)base.limit(messageLimit.coerceAtLeast(100).toLong())else base.whereGreaterThanOrEqualTo("createdAt",com.google.firebase.Timestamp(java.util.Date(since)))
            if(active && !closed)listeners[token]=query.addSnapshotListener(MetadataChanges.INCLUDE){snapshot,error->
                    if(!active || closed)return@addSnapshotListener
                    if(error!=null){
                        snapshotPending.remove(token);pendingChanged()
                        if(error.code==FirebaseFirestoreException.Code.PERMISSION_DENIED)denied(error)
                        else {last=last.copy(loading=false,pending=false,error=messageFor(error));emit()}
                        return@addSnapshotListener
                    }
                    if(snapshot==null || last.accessDenied && snapshot.metadata.isFromCache)return@addSnapshotListener
                    val messages=snapshot.documents.mapNotNull{parse(it,target.id)}.sortedWith(compareBy<ChatMessage>{it.time}.thenBy{it.id})
                    if(!snapshot.metadata.isFromCache)messages.filter{!it.pending}.forEach{runCatching{outbox.remove(it.id)}}
                    val incoming=messages.filter{it.sender!=session.uid && !it.deleted && !it.pending}.map{it.id}.toSet()
                    receipts.keys.filter{it !in incoming}.toList().forEach{receipts.remove(it)?.remove()}
                    incoming.filter{it !in receipts}.forEach{id->receipts[id]=root.document(target.id).collection("messages").document(id).collection("reads").document(session.uid).addSnapshotListener{receipt,receiptError->
                        if(active && !closed && !last.accessDenied){
                            if(receiptError?.code==FirebaseFirestoreException.Code.PERMISSION_DENIED)denied(receiptError)
                            else if(receiptError==null){ownReads["${target.id}/$id"]=receipt?.exists()==true;emit()}
                        }
                    }}
                    snapshotPending[token]=snapshot.metadata.hasPendingWrites();pendingChanged()
                    if(!snapshot.metadata.isFromCache)deniedChats.remove(target.id)
                    last=ChatSnapshot(messages,false,snapshot.metadata.isFromCache,snapshot.metadata.hasPendingWrites())
                    emit()
                }
        },{error->end();if(active && !closed){
            if((error as? FirebaseFirestoreException)?.code==FirebaseFirestoreException.Code.PERMISSION_DENIED)denied(error)
            else {last=last.copy(loading=false,error=messageFor(error));emit()}
        }})
        return {active=false;receipts.values.forEach{it.remove()};outboxObservers.remove(token);listeners.remove(token)?.remove();snapshotPending.remove(token);pendingChanged()}
    }

    /** Observe metadata without creating empty rooms while showing board/calendar badges. */
    fun observeExisting(target:ChatTarget,since:Long,onState:(ChatSnapshot)->Unit):()->Unit {
        require(valid(target))
        val token=newId();var active=true;var messages:(()->Unit)?=null
        listeners[token]=root.document(target.id).addSnapshotListener { document,error ->
            if(active && !closed) {
                if(error!=null || document?.exists()!=true) {
                    messages?.invoke();messages=null
                    if(error?.code==FirebaseFirestoreException.Code.PERMISSION_DENIED){deniedChats.add(target.id);knownChats.remove(target.id)}
                    onState(ChatSnapshot(loading=false,error=error?.let(::messageFor),accessDenied=error?.code==FirebaseFirestoreException.Code.PERMISSION_DENIED))
                } else if(messages==null) {
                    knownChats.add(target.id)
                    messages=observe(target,since,onState=onState)
                }
            }
        }
        return {active=false;messages?.invoke();listeners.remove(token)?.remove()}
    }

    fun send(target:ChatTarget,text:String,onQueued:()->Unit,onFailure:(String)->Unit,replyTo:String="",format:String="markdown-v1",attachmentIds:List<String> = emptyList(),id:String=newId()) {
        if(closed){onFailure("Чат закрыт. Открой его снова.");return}
        if(!valid(target) || (text.isBlank() && attachmentIds.isEmpty()) || text.length>4000 || attachmentIds.size>10){onFailure("Проверь текст и вложения сообщения.");return}
        try {outbox.put(PendingChatMessage(id,target,text.trim(),System.currentTimeMillis(),replyTo,format,attachmentIds))}
        catch(_:Exception){onFailure("Не удалось сохранить сообщение на телефоне. Освободи место и повтори.");return}
        onQueued();changed();flush()
    }
    fun retryMessage(id:String){outbox.all().find{it.id==id}?.let{runCatching{outbox.put(it.copy(error=""))}};changed();flush()}
    private fun flush(){
        if(closed)return
        outbox.all().filter{!it.delivered && it.id !in sending && it.error.isEmpty()}.forEach{m->
            sending.add(m.id);begin()
            fun finished(error:Exception?){
                sending.remove(m.id);end()
                if(error==null)runCatching{outbox.acknowledge(m.id)}
                else {
                    val permanent=(error as? FirebaseFirestoreException)?.code in listOf(FirebaseFirestoreException.Code.PERMISSION_DENIED,FirebaseFirestoreException.Code.INVALID_ARGUMENT,FirebaseFirestoreException.Code.NOT_FOUND) || (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code in listOf(com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED,com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT,com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND,com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION)
                    if(permanent)runCatching{outbox.put(m.copy(error=messageFor(error)))}
                }
                changed()
            }
            ensureChat(m.target,{
                if(m.attachmentIds.isNotEmpty()) {
                    com.google.firebase.functions.FirebaseFunctions.getInstance("europe-west3").getHttpsCallable("commitAttachmentMessage").call(mapOf(
                        "spaceId" to session.spaceId,"chatId" to m.target.id,"messageId" to m.id,"text" to m.text,"replyTo" to m.replyTo,
                        "format" to m.format,"attachmentIds" to m.attachmentIds,"clientTime" to m.time))
                        .addOnSuccessListener{finished(null)}.addOnFailureListener{finished(it)}
                } else {
                    val ref=root.document(m.target.id).collection("messages").document(m.id)
                    db.runTransaction {tx->
                        val old=tx.get(ref)
                        if(!old.exists())tx.set(ref,mapOf("sender" to session.uid,"actor" to session.authUid,"role" to session.viewer.name,
                            "text" to m.text,"createdAt" to FieldValue.serverTimestamp(),"clientTime" to m.time,"deleted" to false,"replyTo" to m.replyTo,"format" to m.format,"attachmentIds" to emptyList<String>()))
                        true
                    }.addOnSuccessListener{finished(null)}.addOnFailureListener{finished(it)}
                }
            },{finished(it ?: IllegalStateException("Unavailable"))})
        }
    }
    fun markRead(message:ChatMessage,onComplete:(String?)->Unit={}){
        if(closed){onComplete("Чат закрыт. Открой его снова.");return}
        if(message.sender==session.uid || message.pending || message.deleted){onComplete(null);return}
        root.document(message.chatId).collection("messages").document(message.id).collection("reads").document(session.uid)
            .set(mapOf("readAt" to FieldValue.serverTimestamp(),"actor" to session.authUid))
            .addOnCompleteListener{result->onComplete(if(result.isSuccessful)null else messageFor(result.exception))}
    }
    fun observeRead(message:ChatMessage,onRead:(Boolean)->Unit):()->Unit {
        val other=session.uids.firstOrNull{it!=session.uid} ?: return {}
        val registration=root.document(message.chatId).collection("messages").document(message.id).collection("reads").document(other)
            .addSnapshotListener{d,_->onRead(d?.exists()==true)}
        return {registration.remove()}
    }
    fun react(message:ChatMessage,emoji:String,winter:Boolean,failed:(String)->Unit,finished:()->Unit={}){
        if(message.sender==session.uid || message.pending || message.deleted){failed("Реакции доступны только на чужие отправленные сообщения");finished();return}
        val ref=root.document(message.chatId).collection("messages").document(message.id).collection("reactions").document(session.uid)
        FirebaseFirestore.getInstance().runTransaction { transaction ->
            val current=ReactionChoices.decode(transaction.get(ref).data)
            val next=ReactionChoices.toggle(current,emoji,winter)
            if(next.isEmpty())transaction.delete(ref)
            else transaction.set(ref,mapOf("emoji" to next.first(),"emojis" to next,"updatedAt" to FieldValue.serverTimestamp(),"actor" to session.authUid))
        }.addOnFailureListener{failed(it.cause?.message?.takeIf{m->m.contains("трёх") || m.contains("зимней")}?:messageFor(it))}.addOnCompleteListener{finished()}
    }
    fun observeReactions(message:ChatMessage,onReactions:(Map<String,List<String>>)->Unit):()->Unit {
        val registration=root.document(message.chatId).collection("messages").document(message.id).collection("reactions")
            .addSnapshotListener{d,_->onReactions(d?.documents?.filter{it.id!=message.sender}?.associate{it.id to ReactionChoices.decode(it.data)} ?: emptyMap())}
        return {registration.remove()}
    }
    fun search(target:ChatTarget,text:String,onResult:(List<ChatMessage>,String?)->Unit){
        val found=mutableListOf<ChatMessage>()
        fun page(after:DocumentSnapshot?){
            var q:Query=root.document(target.id).collection("messages").orderBy("createdAt",Query.Direction.DESCENDING).limit(200)
            if(after!=null)q=q.startAfter(after)
            q.get(Source.SERVER).addOnSuccessListener{snap->
                if(closed)return@addOnSuccessListener
                found+=snap.documents.mapNotNull{parse(it,target.id)}.filter{!it.deleted && richPlainText(it.text,it.format).contains(text,ignoreCase=true)}
                if(snap.size()==200)page(snap.documents.last())else onResult(found,null)
            }.addOnFailureListener{onResult(emptyList(),messageFor(it))}
        };page(null)
    }
    fun observeMessage(chatId:String,id:String,onResult:(ChatMessage?)->Unit):()->Unit {
        val listener=root.document(chatId).collection("messages").document(id).addSnapshotListener{d,_->onResult(d?.let{parse(it,chatId)})}
        return{listener.remove()}
    }
    fun getMessage(target:ChatTarget,id:String,onResult:(ChatMessage?)->Unit){root.document(target.id).collection("messages").document(id).get().addOnSuccessListener{onResult(parse(it,target.id))}.addOnFailureListener{onResult(null)}}

    fun edit(message:ChatMessage,text:String,onFailure:(String)->Unit) {
        val body=text.trim()
        if(closed || message.sender!=session.uid || message.deleted || body.isEmpty() || body.length>4000)return
        begin()
        root.document(message.chatId).collection("messages").document(message.id).update(mapOf("text" to body,"editedAt" to FieldValue.serverTimestamp(),"format" to "markdown-v1"))
            .addOnCompleteListener{r->if(!closed){end();if(!r.isSuccessful)onFailure(messageFor(r.exception))}}
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
    fun close(){closed=true;handler.removeCallbacks(retryTick);outboxObservers.clear();listeners.values.forEach{it.remove()};listeners.clear();snapshotPending.clear()}
}
