package team.kotiki.app

import com.google.firebase.firestore.*
import org.json.JSONObject
import java.time.LocalDate

/** Every write includes an immutable version. Last server-accepted write wins, even after offline edits. */
class CloudSync(private val store:DemoStore,private val session:CloudSession,
    private val db:FirebaseFirestore=FirebaseFirestore.getInstance()) {
    private val root=db.collection("spaces").document(session.spaceId)
    private val records=linkedMapOf<String,CloudRecord>()
    private val busy=linkedMapOf<String,Entry>()
    private val listeners=mutableListOf<ListenerRegistration>()
    private var loaded=false
    private var closed=false
    private var inFlight=0
    private var fromCache=true
    private var busyLoaded=false
    private var recordsPending=false
    private var busyPending=false
    fun start() {
        store.onCloudSave=::publish
        listeners+=root.collection("records").whereArrayContains("readers",session.uid)
            .addSnapshotListener(MetadataChanges.INCLUDE){snapshot,error->
                if(closed)return@addSnapshotListener
                if(error!=null){fail(error);return@addSnapshotListener}
                if(snapshot==null)return@addSnapshotListener
                records.clear()
                try {snapshot.documents.forEach{d->records[d.id]=cloudRecord(d.id,d.data!!) }}
                catch(_:Exception){store.cloudStatus="Не удалось прочитать облачные данные";return@addSnapshotListener}
                loaded=true;fromCache=snapshot.metadata.isFromCache;recordsPending=snapshot.metadata.hasPendingWrites()
                render();status()
            }
        listeners+=root.collection("busy").whereEqualTo("active",true)
            .addSnapshotListener(MetadataChanges.INCLUDE){snapshot,error->
                if(closed)return@addSnapshotListener
                if(error!=null){fail(error);return@addSnapshotListener}
                if(snapshot==null)return@addSnapshotListener
                busy.clear()
                try {snapshot.documents.forEach { d->
                    val owner=session.roles[d.getString("owner")] ?: return@forEach
                    if(owner!=session.viewer) {
                        fun date(key:String)=d.getString(key)?.takeIf{it.isNotEmpty()}?.let(LocalDate::parse)
                        busy[d.id]=Entry(d.id.removePrefix("entry_"),"Занято",date("date"),date("week")!!,date("lastDate"),
                            d.getString("start") ?: "",d.getString("end") ?: "",Kind.valueOf(d.getString("kind")!!),
                            Person.valueOf(d.getString("who")!!),owner,Visibility.BUSY,false)
                    }
                }} catch(_:Exception){store.cloudStatus="Не удалось прочитать занятость";return@addSnapshotListener}
                busyLoaded=true;busyPending=snapshot.metadata.hasPendingWrites();render();status()
            }
    }
    private fun status() {
        store.cloudPending=inFlight>0 || recordsPending || busyPending
        store.cloudReady=loaded && busyLoaded
        if(store.cloudError.isNotEmpty())return
        store.cloudStatus=when { !store.cloudReady->"Загружаем записи…";store.cloudPending->"Изменения ожидают синхронизации";
            fromCache->"Локальная копия · ожидаем соединение";else->"Синхронизировано" }
    }
    private fun render() {
        if(!loaded)return
        val active=records.values.filterNot{it.deleted}
        val entries=active.filter{it.type=="entry"}.mapNotNull{runCatching{readEntry(JSONObject(it.data),emptyMap())}.getOrNull()}
        val plans=active.filter{it.type=="plan"}.mapNotNull{runCatching{readCollection(JSONObject(it.data))}.getOrNull()}
        val lists=active.filter{it.type=="list"}.mapNotNull{runCatching{readCollection(JSONObject(it.data))}.getOrNull()}
        store.receiveCloud(StoreContent(entries+busy.values.filter{b->entries.none{it.id==b.id}},plans,lists))
    }
    private fun publish(content:StoreContent) {
        if(closed || !store.cloudReady)return
        val next=content.records(session).filterNot{it.data["visibility"]=="BUSY" && it.owner!=session.uid}.associateBy{it.id}
        val changes=next.values.filter{it!=records[it.id]}.toMutableList()
        records.values.filter{!it.deleted && it.id !in next}.forEach{changes+=it.copy(deleted=true)}
        changes.forEach{record->
            val revision=newId()
            val payload=record.envelope(session,revision,FieldValue.serverTimestamp())
            val ref=root.collection("records").document(record.id)
            val batch=db.batch().set(ref,payload).set(ref.collection("revisions").document(revision),payload)
            if(record.type=="entry")batch.set(root.collection("busy").document(record.id),record.projection(revision))
            records[record.id]=record
            inFlight++;store.cloudError="";status()
            batch.commit().addOnCompleteListener{task->
                if(closed)return@addOnCompleteListener
                inFlight--;if(!task.isSuccessful){store.keepFailedCloudChange(record);fail(task.exception)}
                status()
            }
        }
    }
    fun history(id:String,onResult:(List<String>)->Unit) {
        root.collection("records").document(id).collection("revisions").whereArrayContains("readers",session.uid)
            .get().addOnSuccessListener{snapshot->
                val rows=snapshot.documents.sortedByDescending{it.getTimestamp("updatedAt")?.seconds ?: 0}.take(30).map{d->
                    val data=d.get("data") as? Map<*,*> ?: emptyMap<Any,Any>()
                    val actor=session.actorRoles[d.getString("actor")]?.let{session.names[it]} ?: "Участник"
                    val time=d.getTimestamp("updatedAt")?.toDate()?.let{java.text.SimpleDateFormat("dd.MM HH:mm:ss",java.util.Locale.getDefault()).format(it)} ?: "Ожидает отправки"
                    "$time · $actor\n${data["title"] ?: data["name"] ?: "Запись"}${if(d.getBoolean("deleted")==true)" · удалено" else ""}\n${data["date"] ?: ""} ${data["start"] ?: ""} ${data["note"] ?: ""}"
                };onResult(rows)
            }.addOnFailureListener{onResult(listOf("История сейчас недоступна"))}
    }
    private fun fail(error:Exception?) {
        store.cloudError=when((error as? FirebaseFirestoreException)?.code){
            FirebaseFirestoreException.Code.PERMISSION_DENIED->"Нет доступа к записи. Проверь подключение пространства и права участника."
            FirebaseFirestoreException.Code.UNAVAILABLE->"Нет связи с облаком. Изменения будут отправлены после подключения."
            else->"Не удалось синхронизировать запись. Проверь соединение и настройки Firebase."
        }
        store.cloudStatus=store.cloudError
    }
    fun close(){closed=true;listeners.forEach{it.remove()};store.onCloudSave=null}
}
