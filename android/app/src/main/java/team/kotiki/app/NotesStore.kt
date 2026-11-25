package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.*
import com.google.firebase.firestore.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class NoteGroup(val id:String,val name:String,val shared:Boolean,val owner:Person,val editable:Boolean=true,val archived:Boolean=false,val updatedAt:Long=System.currentTimeMillis())
enum class NoteBlockKind { TEXT, HEADING, BULLET, NUMBERED, CHECK }
data class NoteBlock(val id:String=newId(),val kind:NoteBlockKind=NoteBlockKind.TEXT,val text:String="",val checked:Boolean=false,val bold:Boolean=false)
data class Note(val id:String,val groupId:String,val title:String="",val blocks:List<NoteBlock> = listOf(NoteBlock()),val archived:Boolean=false,val updatedAt:Long=System.currentTimeMillis(),val revision:String=newId(),val textFormat:String=PLAIN_TEXT_FORMAT,val markdown:String="",val titleFormat:String=PLAIN_TEXT_FORMAT) {
    val body get()=if(textFormat==MARKDOWN_TEXT_FORMAT)markdown else legacyNoteMarkdown(blocks)
    val preview get()=richPlainText(body,MARKDOWN_TEXT_FORMAT)
    fun matches(query:String)=richPlainText(title,titleFormat).contains(query,true)||preview.contains(query,true)
    val label get()=richPlainText(title,titleFormat).ifBlank{"Без названия"}
}
internal fun NoteGroup.json()=JSONObject().put("id",id).put("name",name).put("shared",shared).put("owner",owner.name).put("editable",editable).put("archived",archived).put("updatedAt",updatedAt)
internal fun Note.json()=JSONObject().put("id",id).put("groupId",groupId).put("title",title).put("archived",archived).put("updatedAt",updatedAt).put("revision",revision)
    .put("textFormat",textFormat).put("markdown",markdown).put("titleFormat",titleFormat)
    .put("blocks",JSONArray(blocks.map{JSONObject().put("id",it.id).put("kind",it.kind.name).put("text",it.text).put("checked",it.checked).put("bold",it.bold)}))
internal fun noteGroup(j:JSONObject)=NoteGroup(j.getString("id"),j.getString("name"),j.getBoolean("shared"),Person.valueOf(j.getString("owner")),j.optBoolean("editable",true),j.optBoolean("archived"),j.optLong("updatedAt"))
internal fun noteValue(j:JSONObject)=Note(j.getString("id"),j.getString("groupId"),j.optString("title"),(j.optJSONArray("blocks")?:JSONArray()).let{a->(0 until a.length()).map{a.getJSONObject(it).let{b->NoteBlock(b.getString("id"),NoteBlockKind.valueOf(b.getString("kind")),b.optString("text"),b.optBoolean("checked"),b.optBoolean("bold"))}}},j.optBoolean("archived"),j.optLong("updatedAt"),j.getString("revision"),j.optString("textFormat",PLAIN_TEXT_FORMAT),j.optString("markdown"),j.optString("titleFormat",PLAIN_TEXT_FORMAT))

/** No personal note or archive ever enters Firestore, prefs, backups, or record revisions. */
class NotesStore(context:Context,val viewer:Person,val session:CloudSession?=null) {
    val groups=mutableStateListOf<NoteGroup>()
    val notes=mutableStateListOf<Note>()
    var error by mutableStateOf(""); private set
    var pending by mutableStateOf(false); private set
    var online by mutableStateOf(session==null); private set
    var healthy by mutableStateOf(true); private set
    var localSaving by mutableStateOf(false); private set
    private val key=MessageDigest.getInstance("SHA-256").digest((session?.let{"${it.spaceId}/${it.uid}"} ?: "demo/${viewer.name}").toByteArray()).joinToString(""){"%02x".format(it)}
    internal val file=NoteFile(File(context.noBackupFilesDir,"notes-$key.json"))
    private val writes=linkedMapOf<String,JSONObject>()
    private val moves=linkedMapOf<String,JSONObject>()
    // Permanent server rejections are isolated, retained locally and never retried in a tight loop.
    private val rejected=linkedMapOf<String,JSONObject>()
    private var persistenceGeneration=0L
    private val db by lazy{FirebaseFirestore.getInstance()}
    private val root by lazy{db.collection("spaces").document(session!!.spaceId)}
    private val listeners=mutableListOf<ListenerRegistration>()
    internal var readFailure:Exception?=null; private set
    private var running=false
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val flush=Runnable{drain()}
    private var lastChange=0L
    var lastSyncedAt by mutableStateOf(0L); private set
    private var closed=false
    init {
        file.awaitWrites()
        if(file.baseFile.exists())try {
            val j=JSONObject(String(file.readFully(),Charsets.UTF_8))
            fun array(name:String,read:(JSONObject)->Unit){val a=j.optJSONArray(name)?:JSONArray();for(i in 0 until a.length())read(a.getJSONObject(i))}
            array("groups"){groups.add(noteGroup(it))};array("notes"){notes.add(noteValue(it))}
            array("writes"){writes[it.getString("path")]=it};array("moves"){moves[it.getString("id")]=it}
            array("rejected"){rejected[it.getString("path")]=it}
        } catch(e:Exception){readFailure=e;healthy=false;error="Не удалось прочитать заметки. Исходный файл сохранён; изменения заблокированы."}
        pending=writes.isNotEmpty()||moves.isNotEmpty()
        if(healthy&&rejected.isNotEmpty())error="Есть запись, не принятая сервером. Локальная копия сохранена; остальные заметки синхронизируются."
    }
    private fun persist(durable:Boolean=false):Boolean {
        if(!healthy)return false
        val savedGroups=groups.toList();val savedNotes=notes.toList()
        val savedWrites=writes.values.toList();val savedMoves=moves.values.toList();val savedRejected=rejected.values.toList()
        val generation=++persistenceGeneration
        localSaving=true;pending=true
        val future=file.enqueue {
            JSONObject().put("groups",JSONArray(savedGroups.map{it.json()})).put("notes",JSONArray(savedNotes.map{it.json()}))
                .put("writes",JSONArray(savedWrites)).put("moves",JSONArray(savedMoves)).put("rejected",JSONArray(savedRejected)).toString().toByteArray(Charsets.UTF_8)
        }
        fun complete(failure:Exception?) {
            if(failure!=null){readFailure=failure;healthy=false;localSaving=false;error="Не удалось сохранить на телефоне. Освободи место и перезапусти приложение."}
            else if(generation==persistenceGeneration){localSaving=false;pending=writes.isNotEmpty()||moves.isNotEmpty();if(!closed)drain()}
        }
        if(durable)return try{future.get();complete(null);true}catch(e:Exception){complete(e);false}
        file.afterWrites {failure->handler.post{complete(failure)}}
        return true
    }

    fun canEdit(group:NoteGroup)=healthy && (group.owner==viewer || group.shared && group.editable)
    fun moving(id:String)=id in moves
    fun groupOf(note:Note)=groups.find{it.id==note.groupId}
    private fun queue(path:String,value:JSONObject?,create:Boolean=false) {
        rejected.remove(path)
        val wasCreate=writes[path]?.optBoolean("create")==true
        writes[path]=JSONObject().put("path",path).put("create",create||wasCreate).put("token",newId()).put("value",value ?: JSONObject.NULL)
    }
    private fun changed(){lastChange=android.os.SystemClock.elapsedRealtime();if(persist()){handler.removeCallbacks(flush);handler.postDelayed(flush,500)}}
    fun addGroup(name:String,shared:Boolean,editable:Boolean):String {
        if(!healthy)return ""
        val g=NoteGroup(newId(),name.trim().take(100),shared,viewer,editable)
        groups.add(g);if(shared && session!=null)queue("noteGroups/${g.id}",g.json(),true);changed();return g.id
    }
    fun changeGroup(group:NoteGroup,name:String=group.name,archived:Boolean=group.archived) {
        if(!canEdit(group)||archived && moves.values.any{it.optString("target")==group.id || notes.find{n->n.id==it.optString("id")}?.groupId==group.id})return
        val next=group.copy(name=name.trim().take(100),archived=archived,updatedAt=System.currentTimeMillis())
        groups[groups.indexOfFirst{it.id==group.id}]=next
        if(group.shared&&session!=null)queue("noteGroups/${group.id}",next.json());changed()
    }
    fun addNote(group:NoteGroup):String {
        if(!canEdit(group)||group.archived)return ""
        val n=Note(newId(),group.id);notes.add(n)
        if(group.shared&&session!=null)queue("notes/${n.id}",n.json(),true);changed();return n.id
    }
    fun createFromText(group:NoteGroup,text:String,format:String=PLAIN_TEXT_FORMAT,title:String=""):String {
        if(!canEdit(group)||group.archived)return ""
        val body=if(format==MARKDOWN_TEXT_FORMAT)text else RichTextCodec.escape(text)
        val note=Note(newId(),group.id,title=title.take(200),blocks=emptyList(),markdown=body,textFormat=MARKDOWN_TEXT_FORMAT)
        if(note.json().toString().length>150000){error="Текст слишком большой для одной заметки.";return ""}
        notes.add(note);if(group.shared&&session!=null)queue("notes/${note.id}",note.json(),true);changed();return note.id
    }
    fun edit(note:Note) {
        val old=notes.find{it.id==note.id}?:return
        val group=groupOf(old)?:return
        if(!canEdit(group)||moving(note.id))return
        val n=note.copy(groupId=old.groupId,title=note.title.take(200),blocks=note.blocks.take(200).map{it.copy(text=it.text.take(10000))},revision=newId(),updatedAt=System.currentTimeMillis())
        if(n.json().toString().length>150000){error="Заметка слишком большая. Раздели её на несколько записей.";return}
        notes[notes.indexOfFirst{it.id==n.id}]=n
        if(group.shared&&session!=null)queue("notes/${n.id}",n.json());changed()
    }
    fun archive(note:Note,value:Boolean=true)=edit(note.copy(archived=value))
    fun purge(note:Note) {
        val g=groupOf(note)?:return
        if(!note.archived || !canEdit(g)||moving(note.id))return
        notes.removeAll{it.id==note.id};if(g.shared&&session!=null)queue("notes/${note.id}",null);changed()
    }
    fun move(note:Note,target:NoteGroup) {
        val source=groupOf(note)?:return
        if(!healthy||target.archived||source.id==target.id||moving(note.id))return
        if(!target.shared && target.owner!=viewer)return
        if(session==null || !source.shared && !target.shared) {
            notes[notes.indexOfFirst{it.id==note.id}]=note.copy(groupId=target.id,updatedAt=System.currentTimeMillis(),revision=newId());changed();return
        }
        // Persist intent and a local recovery copy before deleting anything on the server.
        moves[note.id]=JSONObject().put("id",note.id).put("target",target.id).put("sourceShared",source.shared)
            .put("targetShared",target.shared).put("newId",newId()).put("snapshot",note.json())
        changed()
    }
    fun retry(){error="";rejected.forEach{(path,value)->if(path.startsWith("moves/"))moves[path.substringAfter('/')]=value else writes[path]=value};rejected.clear();persist();drain()}
    fun start() {
        if(session==null||!healthy)return
        listOf("noteGroups","notes").forEach{kind->listeners+=root.collection(kind).addSnapshotListener(MetadataChanges.INCLUDE){snapshot,e->
            if(closed)return@addSnapshotListener
            if(e!=null){error="Не удалось загрузить заметки. Проверь подключение и права доступа.";return@addSnapshotListener}
            if(snapshot==null)return@addSnapshotListener
            online=!snapshot.metadata.isFromCache
            try {
                val protected=(writes.keys+rejected.keys).filter{it.startsWith("$kind/")}.map{it.substringAfter('/')}.toSet()+moves.keys+
                    if(kind=="noteGroups")notes.filter{"notes/${it.id}" in rejected}.map{it.groupId} else emptyList()
                if(kind=="noteGroups") {
                    val incoming=snapshot.documents.map{noteGroup(JSONObject(it.data!!))}
                    groups.removeAll{it.shared && it.id !in protected};groups.addAll(incoming.filter{it.id !in protected})
                } else {
                    val privateIds=groups.filter{!it.shared}.map{it.id}.toSet()
                    val incoming=snapshot.documents.map{noteValue(JSONObject(it.data!!))}
                    notes.removeAll{it.groupId !in privateIds && it.id !in protected};notes.addAll(incoming.filter{it.id !in protected && notes.none{n->n.id==it.id}})
                }
                persist();if(online)drain()
            }catch(_:Exception){error="Не удалось прочитать формат общей заметки."}
        }}
        drain()
    }
    private fun failed(e:Exception?) {
        running=false
        error=when((e as? FirebaseFirestoreException)?.code){
            FirebaseFirestoreException.Code.UNAVAILABLE->"Изменения сохранены на телефоне. Перенос ждёт подключения."
            FirebaseFirestoreException.Code.PERMISSION_DENIED->"Доступ изменился. Запись сохранена на телефоне; проверь права группы."
            else->"Не удалось завершить синхронизацию. Нажми «Повторить»; локальная запись сохранена."
        }
    }
    private fun drain() {
        if(session==null||closed||running||!healthy||localSaving)return
        val delay=500-(android.os.SystemClock.elapsedRealtime()-lastChange)
        if(writes.isNotEmpty()&&delay>0){handler.removeCallbacks(flush);handler.postDelayed(flush,delay);return}
        val write=writes.values.firstOrNull()
        if(write!=null) {
            running=true
            val path=write.getString("path");val token=write.getString("token");val ref=root.collection(path.substringBefore('/' )).document(path.substringAfter('/'))
            val value=write.optJSONObject("value")
            val task=if(value==null)db.runTransaction{tx->
                if(tx.get(ref).exists()) {tx.set(root.collection("retiredNotes").document(ref.id),mapOf("retired" to true));tx.delete(ref)}
                true
            } else if(write.getBoolean("create"))ref.set(value.toCloudMap()) else ref.update(value.toCloudMap())
            task.addOnSuccessListener {
                if(closed)return@addOnSuccessListener
                if(writes[path]?.optString("token")==token)writes.remove(path) else writes[path]?.let{writes[path]=JSONObject(it.toString()).put("create",false)}
                running=false;error=if(rejected.isEmpty())"" else "Есть запись, не принятая сервером. Локальная копия сохранена; остальные заметки синхронизируются.";lastSyncedAt=System.currentTimeMillis();persist();drain()
            }.addOnFailureListener{failure->if(!closed){
                failed(failure)
                if((failure as? FirebaseFirestoreException)?.code in listOf(FirebaseFirestoreException.Code.NOT_FOUND,FirebaseFirestoreException.Code.PERMISSION_DENIED,FirebaseFirestoreException.Code.INVALID_ARGUMENT)) {
                    if(writes[path]?.optString("token")==token){rejected[path]=write;writes.remove(path)}
                    error="Одна запись не принята сервером. Локальная копия сохранена; остальные заметки синхронизируются."
                    persist()
                }
            }}
            return
        }
        val move=moves.values.firstOrNull()?:return
        running=true
        val id=move.getString("id");val target=groups.find{it.id==move.getString("target")}
        fun rejectMove(){rejected["moves/$id"]=JSONObject(move.toString()).put("path","moves/$id");moves.remove(id);running=false;persist()}
        if(target==null){error="Группа назначения недоступна. Локальная заметка сохранена; повтори перенос после восстановления группы.";rejectMove();return}
        val original=noteValue(move.getJSONObject("snapshot"))
        val sourceRef=root.collection("notes").document(id)
        fun transfer(staged:Note) {
            moves[id]=JSONObject(move.toString()).put("snapshot",staged.json());if(!persist(durable=true)){running=false;return}
            val outgoing=staged.copy(id=if(move.getBoolean("sourceShared"))id else move.getString("newId"),groupId=target.id,revision=move.getString("newId"),updatedAt=System.currentTimeMillis())
            db.runTransaction {tx->
                val remote=if(move.getBoolean("sourceShared"))tx.get(sourceRef) else null
                val destination=if(target.shared)tx.get(root.collection("noteGroups").document(target.id)) else null
                val published=if(!move.getBoolean("sourceShared"))tx.get(root.collection("notes").document(outgoing.id)) else null
                if(destination!=null)check(destination.exists()&&destination.getBoolean("archived")!=true){"Destination unavailable"}
                if(remote?.exists()==true)check(remote.getString("revision")==staged.revision || remote.getString("revision")==outgoing.revision){"Note changed during transfer"}
                if(target.shared) {
                    if(remote!=null && !remote.exists())error("Source removed")
                    if(published?.exists()!=true)tx.set(root.collection("notes").document(outgoing.id),outgoing.json().toCloudMap())
                } else if(remote?.exists()==true){tx.set(root.collection("retiredNotes").document(id),mapOf("retired" to true));tx.delete(sourceRef)}
                true
            }.addOnSuccessListener {
                if(closed)return@addOnSuccessListener
                notes.removeAll{it.id==id||it.id==outgoing.id};notes.add(outgoing);moves.remove(id)
                running=false;error=if(rejected.isEmpty())"" else "Есть запись, не принятая сервером. Локальная копия сохранена; остальные заметки синхронизируются.";lastSyncedAt=System.currentTimeMillis();persist();drain()
            }.addOnFailureListener{if(!closed){failed(it);if((it as? FirebaseFirestoreException)?.code in listOf(FirebaseFirestoreException.Code.PERMISSION_DENIED,FirebaseFirestoreException.Code.NOT_FOUND,FirebaseFirestoreException.Code.INVALID_ARGUMENT))rejectMove()}}
        }
        if(move.getBoolean("sourceShared"))sourceRef.get(Source.SERVER).addOnSuccessListener{d->
            if(!closed)transfer(if(d.exists())noteValue(JSONObject(d.data!!)) else original)
        }.addOnFailureListener{if(!closed)failed(it)} else transfer(original)
    }
    fun close(){closed=true;handler.removeCallbacks(flush);listeners.forEach{it.remove()};listeners.clear();file.awaitWrites()}
}

/** Atomic replacement on both Android/Linux and the Windows JVM test host; never a partial JSON file. */
internal class NoteFile(val baseFile:File) {
    companion object {
        private val writer=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"notes-storage").apply{isDaemon=true}}
    }
    private val next=File(baseFile.path+".new")
    @Volatile private var lastFailure:Exception?=null
    fun awaitWrites(){writer.submit{}.get()}
    fun enqueue(bytes:()->ByteArray):java.util.concurrent.Future<*> = writer.submit {
        try{writeNow(bytes());lastFailure=null}catch(e:Exception){lastFailure=e;throw e}
    }
    fun afterWrites(done:(Exception?)->Unit){writer.submit{done(lastFailure)}}
    fun readFully():ByteArray {awaitWrites();return baseFile.readBytes()}
    fun write(bytes:ByteArray) {
        enqueue{bytes}.get()
    }
    private fun writeNow(bytes:ByteArray) {
        baseFile.parentFile?.mkdirs()
        java.io.FileOutputStream(next).use{out->out.write(bytes);out.fd.sync()}
        java.nio.file.Files.move(next.toPath(),baseFile.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun delete(){awaitWrites();baseFile.delete();next.delete()}
}
