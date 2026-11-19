package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** UI store: isolated local demo or authenticated cloud-backed content. */
class DemoStore(context: Context, selectedUser: Person? = null, val cloudSession:CloudSession?=null) {
    val appContext=context.applicationContext
    val preset = if(cloudSession==null)LocalTestPreset.load(context) else null
    val viewer = cloudSession?.viewer ?: preset?.let { fixture ->
        selectedUser?.also { require(it != Person.BOTH) } ?: runCatching {
            Person.valueOf(context.getSharedPreferences("local_test_profiles", Context.MODE_PRIVATE)
                .getString("selected", fixture.defaultUser.name)!!).also { require(it != Person.BOTH) }
        }.getOrDefault(fixture.defaultUser)
    } ?: Person.USER_1
    var identity by mutableStateOf(cloudSession?.identity ?: DemoIdentity(viewer, preset?.names ?: emptyMap(), preset != null))
        private set
    val manualOrder=LocalOrderStore(context,localProfileKey(cloudSession,viewer))
    val notes=NotesStore(context,viewer,cloudSession)
    var profileError by mutableStateOf("")
    var profilePending by mutableStateOf(false)
    fun receiveIdentity(value:DemoIdentity){identity=value}
    fun updateName(value:String) {
        val name=value.trim().take(40)
        if(name.isBlank())return
        if(cloudSession==null){identity=identity.copy(names=identity.names+(viewer to name));prefs.edit().putString("displayName",name).apply();return}
        profilePending=true;profileError=""
        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("spaces").document(cloudSession.spaceId)
            .update("names.${viewer.name}",name).addOnCompleteListener {task->
                profilePending=false
                if(task.isSuccessful)identity=identity.copy(names=identity.names+(viewer to name)) else profileError="Не удалось изменить имя. Проверь подключение."
            }
    }
    private val prefs = context.getSharedPreferences(
        cloudSession?.let { "cloud_${it.authUid}" } ?: preset?.let { "local_preset_${it.id}_${viewer.name}" } ?: "kotiki_demo_v1", Context.MODE_PRIVATE)
    private val roleAliases: Map<String, String> = runCatching {
        val json = context.assets.open("legacy-roles.json").bufferedReader().use { JSONObject(it.readText()) }
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())
    val entries = mutableStateListOf<Entry>()
    val plans = mutableStateListOf<DemoCollection>()
    val lists = mutableStateListOf<DemoCollection>()
    var theme by mutableStateOf(prefs.getString("theme", preset?.theme ?: "light") ?: "light")
        private set
    var retentionDays by mutableStateOf(prefs.getInt("retention", preset?.retentionDays ?: 7))
        private set
    var boardOverview by mutableStateOf(prefs.getBoolean("boardOverview", true))
        private set
    var undoAction by mutableStateOf<UndoAction?>(null)
        private set
    var storageWarning by mutableStateOf(false)
        private set

    var cloudStatus by mutableStateOf("Подключаемся…")
    var cloudOnline by mutableStateOf(false)
    var lastSyncedAt by mutableStateOf(prefs.getLong("lastSyncedAt",0))
        private set
    fun markSynced(){lastSyncedAt=System.currentTimeMillis();prefs.edit().putLong("lastSyncedAt",lastSyncedAt).apply()}
    var cloudError by mutableStateOf("")
    var cloudPending by mutableStateOf(false)
    var chatPending by mutableStateOf(false)
    fun preparePlanChat(){save()}
    var cloudReady by mutableStateOf(false)
    var onCloudSave:((StoreContent)->Unit)?=null
    var cloudHistory:((String,(List<String>)->Unit)->Unit)?=null
    fun receiveCloud(content:StoreContent) {
        entries.clear();entries.addAll(content.entries)
        plans.clear();plans.addAll(content.plans)
        lists.clear();lists.addAll(content.lists)
    }
    fun keepFailedCloudChange(record:CloudRecord) {
        prefs.edit().putString("failed_${record.id}",JSONObject(record.data).toString()).apply()
    }
    init {
        if(cloudSession==null)prefs.getString("displayName",null)?.let{identity=identity.copy(names=identity.names+(viewer to it))}
        if(!prefs.getBoolean("notesMigration033",false)){
            prefs.edit().apply{prefs.all.keys.filter{it.startsWith("failed_list_")}.forEach{remove(it)};putBoolean("notesMigration033",true)}.apply()
        }
        val data = if(cloudSession==null)prefs.getString("content", null) else null
        val loaded = data != null && runCatching {
            val json = JSONObject(data)
            entries.addAll(json.getJSONArray("entries").objects().map { readEntry(it, roleAliases) })
            plans.addAll(json.getJSONArray("plans").objects().map(::readCollection))
            lists.addAll(json.getJSONArray("lists").objects().map(::readCollection))
        }.isSuccess
        if (!loaded && cloudSession==null) {
            storageWarning = data != null
            entries.clear(); plans.clear(); lists.clear()
            entries.addAll(preset?.entries(LocalDate.now()) ?: demoEntries(LocalDate.now()))
            plans.addAll(preset?.collections("plans") ?: listOf(
                collection("weekends", "Выходные", true, "Найти уютное кафе", "Выбраться за город"),
                collection("travel", "Путешествия", true, "Выбрать маршрут на отпуск"),
                collection("personal", "Мои идеи", false, "Подготовить сюрприз"),
            ))
            lists.addAll(preset?.collections("lists") ?: listOf(
                collection("places", "Места для нас", true, "Кафе с котиками", "Новая выставка"),
                collection("films", "Посмотреть вместе", true, "Выбрать фильм на пятницу"),
                collection("gifts", "Идеи подарков", false, "Книга"),
            ))
        }
        lists.clear()
        // Shared-only plans: remove private demo themes, including old local presets.
        if (plans.removeAll { !it.shared }) save()
        // Optional local-only gallery: seed once without resurrecting later deletions.
        preset?.galleryRevision?.let {revision->
            if(prefs.getString("galleryRevision",null)!=revision) {
                plans.addAll(preset.galleryCollections("plans").filter {candidate->candidate.shared && plans.none{it.id==candidate.id}})
                // Old list gallery is retired; notes start empty.
                save();prefs.edit().putString("galleryRevision",revision).apply()
            }
        }
        if(cloudSession==null)save()
    }
    fun updateBoardOverview(value: Boolean) { boardOverview=value; prefs.edit().putBoolean("boardOverview",value).apply() }
    fun dismissUndo(action: UndoAction) { if(undoAction === action) undoAction=null }
    fun restore(action: UndoAction) { action.restore(); dismissUndo(action); save() }
    fun canDelete(entry: Entry): Boolean = (cloudSession==null || entry.invite==InviteState.NONE || entry.owner==viewer) && entry.visibleTo(viewer) && (entry.owner==viewer || (entry.visibility==Visibility.SHARED && entry.editable))
    fun deleteEntry(entry: Entry) {
        val original=entries.find { it.id==entry.id } ?: return
        if(!canDelete(original))return
        val index=entries.indexOf(original)
        entries.remove(original); save()
        undoAction=UndoAction("Запись удалена") { if(entries.none { it.id==original.id }) entries.add(index.coerceAtMost(entries.size),original) }
    }
    fun deleteCollection(isPlans: Boolean,id: String) {
        val target=if(isPlans)plans else lists
        val original=target.find { it.id==id && (it.shared || it.owner==viewer) } ?: return
        val index=target.indexOf(original)
        target.remove(original); save()
        undoAction=UndoAction(if(isPlans)"Тема удалена" else "Список удалён") { if(target.none { it.id==id }) target.add(index.coerceAtMost(target.size),original) }
    }
    fun deleteItem(isPlans: Boolean,id: String,itemId: String) {
        val target=if(isPlans)plans else lists
        val i=target.indexOfFirst { it.id==id && (it.shared || it.owner==viewer) }
        if(i<0)return
        val original=target[i].items.find { it.id==itemId } ?: return
        val itemIndex=target[i].items.indexOf(original)
        target[i]=target[i].copy(items=target[i].items.filterNot { it.id==itemId },updatedAt=nextEdit())
        save()
        undoAction=UndoAction(if(isPlans)"План удалён" else "Пункт удалён") {
            val current=target.indexOfFirst { it.id==id }
            if(current>=0 && target[current].items.none { it.id==itemId }) {
                val items=target[current].items.toMutableList(); items.add(itemIndex.coerceAtMost(items.size),original)
                target[current]=target[current].copy(items=items,updatedAt=nextEdit())
            }
        }
    }
    fun renameCollection(isPlans: Boolean,id: String,name: String) {
        val target=if(isPlans)plans else lists
        val i=target.indexOfFirst { it.id==id && (it.shared || it.owner==viewer) }
        if(i<0 || name.isBlank())return
        target[i]=target[i].copy(name=name.trim(),updatedAt=nextEdit());save()
    }
    private fun nextEdit() = maxOf(System.currentTimeMillis(),(plans+lists).maxOfOrNull { it.updatedAt }?.plus(1) ?: 0)
    fun upsert(entry: Entry) {
        if(entry.isGathering)require(entry.gatheringError()==null){entry.gatheringError() ?: "Некорректный общий сбор"}
        val i = entries.indexOfFirst { it.id == entry.id }
        if (i >= 0) entries[i] = entry.copy(visibility=entries[i].visibility) else entries.add(entry)
        save()
    }
    fun acceptInvitation(entry:Entry) { respondInvitation(entry,true) }
    fun respondInvitation(entry:Entry,accept:Boolean) {
        val index=entries.indexOfFirst{it.id==entry.id}
        if(index<0)return
        val original=entries[index]
        val updated=InviteWorkflow.respond(original,viewer,accept,entry.proposal?.id,LocalDate.now()) ?: return
        entries[index]=updated;save();undoAction=null
        if(cloudSession==null)undoAction=UndoAction("Ответ сохранён на этом устройстве") {
            val current=entries.indexOfFirst{it.id==updated.id}
            if(current>=0 && entries[current]==updated)entries[current]=original
        }
    }
    fun proposeInvitation(original:Entry,candidate:Entry) {
        val index=entries.indexOfFirst{it.id==original.id}
        if(index<0)return
        val updated=InviteWorkflow.propose(entries[index],candidate,viewer,newId(),original.proposal?.id) ?: return
        entries[index]=updated;save()
    }
    fun toggleDone(entry: Entry) {
        if (entry.kind != Kind.TASK || !entry.canEdit(viewer)) return
        upsert(entry.copy(done = !entry.done))
    }
    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun updateRetention(value: Int) { retentionDays = value.coerceIn(1, 365); prefs.edit().putInt("retention", retentionDays).apply() }
    fun addCollection(isPlans: Boolean, name: String, shared: Boolean) {
        (if (isPlans) plans else lists).add(DemoCollection(newId(), name.trim(), isPlans || shared, owner = viewer, updatedAt=nextEdit())); save()
    }
    fun addItem(isPlans: Boolean, id: String, title: String, description:String="") {
        val target = if (isPlans) plans else lists
        val index = target.indexOfFirst { it.id == id }
        if (index < 0) return
        target[index] = target[index].copy(items = target[index].items + CollectionItem(newId(), title.trim(), description=description.trim().take(4000)),updatedAt=nextEdit())
        save()
    }
    fun updateItem(isPlans:Boolean,collectionId:String,itemId:String,title:String,description:String="") {
        val target=if(isPlans)plans else lists
        val index=target.indexOfFirst{it.id==collectionId && (it.shared || it.owner==viewer)}
        if(index<0 || title.isBlank())return
        target[index]=target[index].copy(items=target[index].items.map {
            if(it.id==itemId)it.copy(title=title.trim().take(100),description=description.trim().take(4000)) else it
        },updatedAt=nextEdit());save()
    }
    fun toggleItem(listId: String, itemId: String) {
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return
        lists[index] = lists[index].copy(items = lists[index].items.map { if (it.id == itemId) it.copy(checked = !it.checked) else it },updatedAt=nextEdit())
        save()
    }
    private fun save() {
        if(cloudSession!=null){onCloudSave?.invoke(StoreContent(entries.toList(),plans.toList(),lists.toList()));return}
        val data = JSONObject().put("entries", JSONArray(entries.map(::writeEntry)))
            .put("plans", JSONArray(plans.map(::writeCollection)))
            .put("lists", JSONArray(lists.map(::writeCollection)))
        prefs.edit().putString("content", data.toString()).apply()
    }
}
class UndoAction(val message: String, val restore: () -> Unit)
fun newId(): String = UUID.randomUUID().toString()
private fun collection(id: String, name: String, shared: Boolean, vararg titles: String) =
    DemoCollection(id, name, shared, titles.mapIndexed { index, title -> CollectionItem("$id-$index", title) })
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.date(key: String): LocalDate? = optString(key).takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
internal fun writeEntry(e: Entry) = JSONObject().apply {
    put("id", e.id); put("title", e.title); put("date", e.date?.toString() ?: ""); put("week", e.week.toString())
    put("lastDate", e.lastDate?.toString() ?: ""); put("start", e.start); put("end", e.end); put("kind", e.kind.name)
    put("who", e.who.name); put("owner", e.owner.name); put("visibility", e.visibility.name); put("editable", e.editable)
    put("done", e.done); put("note", e.note); put("invite", e.invite.name); put("incoming", e.incoming)
    put("resolvedDay", e.resolvedDay?.toString() ?: ""); put("isGathering", e.isGathering)
    if(e.proposalResponseId.isNotEmpty())put("proposalResponseId",e.proposalResponseId)
    e.proposal?.let { p -> put("proposal", JSONObject().put("id",p.id).put("proposedBy",p.proposedBy.name)
        .put("title",p.title).put("date",p.date.toString()).put("lastDate",p.lastDate.toString())
        .put("start",p.start).put("end",p.end).put("note",p.note)) }
}
internal fun decodePerson(value: String, aliases: Map<String, String> = emptyMap()): Person =
    Person.valueOf(aliases[value] ?: value)
internal fun readEntry(j: JSONObject, roleAliases: Map<String, String>) = Entry(
    id = j.getString("id"), title = j.getString("title"), date = j.date("date"), week = LocalDate.parse(j.getString("week")),
    lastDate = j.date("lastDate"), start = j.optString("start"), end = j.optString("end"), kind = Kind.valueOf(j.getString("kind")),
    who = decodePerson(j.getString("who"), roleAliases), owner = decodePerson(j.getString("owner"), roleAliases), visibility = Visibility.valueOf(j.getString("visibility")),
    editable = j.optBoolean("editable", true), done = j.optBoolean("done"), note = j.optString("note"),
    invite = InviteState.valueOf(j.optString("invite", "NONE")), incoming = j.optBoolean("incoming"), resolvedDay = j.date("resolvedDay"), isGathering = j.optBoolean("isGathering",false), proposalResponseId = j.optString("proposalResponseId"),
    proposal = j.optJSONObject("proposal")?.let { p -> InviteProposal(p.getString("id"),Person.valueOf(p.getString("proposedBy")),
        p.getString("title"),LocalDate.parse(p.getString("date")),LocalDate.parse(p.getString("lastDate")),p.getString("start"),p.getString("end"),p.getString("note")) },
)
internal fun writeCollection(c: DemoCollection) = JSONObject().put("id", c.id).put("name", c.name).put("shared", c.shared).put("owner", c.owner.name).put("updatedAt", c.updatedAt).put("itemIds",JSONArray(c.items.map{it.id}))
    .put("items", JSONArray(c.items.map { JSONObject().put("id", it.id).put("title", it.title).put("checked", it.checked).put("description",it.description) }))
internal fun readCollection(j: JSONObject) = DemoCollection(j.getString("id"), j.getString("name"), j.getBoolean("shared"),
    j.getJSONArray("items").objects().map { CollectionItem(it.getString("id"), it.getString("title"), it.optBoolean("checked"),it.optString("description","")) }, Person.valueOf(j.optString("owner", "USER_1")),j.optLong("updatedAt",0))
