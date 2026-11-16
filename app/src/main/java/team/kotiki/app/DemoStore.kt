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

/** Local demonstration store. It deliberately has no account or network access. */
class DemoStore(context: Context) {
    private val prefs = context.getSharedPreferences("kotiki_demo_v1", Context.MODE_PRIVATE)
    private val roleAliases: Map<String, String> = runCatching {
        val json = context.assets.open("legacy-roles.json").bufferedReader().use { JSONObject(it.readText()) }
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())
    val entries = mutableStateListOf<Entry>()
    val plans = mutableStateListOf<DemoCollection>()
    val lists = mutableStateListOf<DemoCollection>()
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    var retentionDays by mutableStateOf(prefs.getInt("retention", 7))
        private set
    var storageWarning by mutableStateOf(false)
        private set

    init {
        val data = prefs.getString("content", null)
        val loaded = data != null && runCatching {
            val json = JSONObject(data)
            entries.addAll(json.getJSONArray("entries").objects().map { readEntry(it, roleAliases) })
            plans.addAll(json.getJSONArray("plans").objects().map(::readCollection))
            lists.addAll(json.getJSONArray("lists").objects().map(::readCollection))
        }.isSuccess
        if (!loaded) {
            storageWarning = data != null
            entries.clear(); plans.clear(); lists.clear()
            entries.addAll(demoEntries(LocalDate.now()))
            plans.addAll(listOf(
                collection("weekends", "Выходные", true, "Найти уютное кафе", "Выбраться за город"),
                collection("travel", "Путешествия", true, "Выбрать маршрут на отпуск"),
                collection("personal", "Мои идеи", false, "Подготовить сюрприз"),
            ))
            lists.addAll(listOf(
                collection("places", "Места для нас", true, "Кафе с котиками", "Новая выставка"),
                collection("films", "Посмотреть вместе", true, "Выбрать фильм на пятницу"),
                collection("gifts", "Идеи подарков", false, "Книга"),
            ))
        }
    }
    fun upsert(entry: Entry) {
        val i = entries.indexOfFirst { it.id == entry.id }
        if (i >= 0) entries[i] = entry else entries.add(entry)
        save()
    }
    fun toggleDone(entry: Entry) {
        if (entry.kind != Kind.TASK || !entry.canEdit()) return
        upsert(entry.copy(done = !entry.done))
    }
    fun updateTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun updateRetention(value: Int) { retentionDays = value.coerceIn(1, 365); prefs.edit().putInt("retention", retentionDays).apply() }
    fun addCollection(isPlans: Boolean, name: String, shared: Boolean) {
        (if (isPlans) plans else lists).add(DemoCollection(newId(), name.trim(), shared)); save()
    }
    fun addItem(isPlans: Boolean, id: String, title: String) {
        val target = if (isPlans) plans else lists
        val index = target.indexOfFirst { it.id == id }
        if (index < 0) return
        target[index] = target[index].copy(items = target[index].items + CollectionItem(newId(), title.trim()))
        save()
    }
    fun toggleItem(listId: String, itemId: String) {
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return
        lists[index] = lists[index].copy(items = lists[index].items.map { if (it.id == itemId) it.copy(checked = !it.checked) else it })
        save()
    }
    private fun save() {
        val data = JSONObject().put("entries", JSONArray(entries.map(::writeEntry)))
            .put("plans", JSONArray(plans.map(::writeCollection)))
            .put("lists", JSONArray(lists.map(::writeCollection)))
        prefs.edit().putString("content", data.toString()).apply()
    }
}
fun newId(): String = UUID.randomUUID().toString()
private fun collection(id: String, name: String, shared: Boolean, vararg titles: String) =
    DemoCollection(id, name, shared, titles.mapIndexed { index, title -> CollectionItem("$id-$index", title) })
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.date(key: String): LocalDate? = optString(key).takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
private fun writeEntry(e: Entry) = JSONObject().apply {
    put("id", e.id); put("title", e.title); put("date", e.date?.toString() ?: ""); put("week", e.week.toString())
    put("lastDate", e.lastDate?.toString() ?: ""); put("start", e.start); put("end", e.end); put("kind", e.kind.name)
    put("who", e.who.name); put("owner", e.owner.name); put("visibility", e.visibility.name); put("editable", e.editable)
    put("done", e.done); put("note", e.note); put("invite", e.invite.name); put("incoming", e.incoming)
    put("resolvedDay", e.resolvedDay?.toString() ?: "")
}
internal fun decodePerson(value: String, aliases: Map<String, String> = emptyMap()): Person =
    Person.valueOf(aliases[value] ?: value)
private fun readEntry(j: JSONObject, roleAliases: Map<String, String>) = Entry(
    id = j.getString("id"), title = j.getString("title"), date = j.date("date"), week = LocalDate.parse(j.getString("week")),
    lastDate = j.date("lastDate"), start = j.optString("start"), end = j.optString("end"), kind = Kind.valueOf(j.getString("kind")),
    who = decodePerson(j.getString("who"), roleAliases), owner = decodePerson(j.getString("owner"), roleAliases), visibility = Visibility.valueOf(j.getString("visibility")),
    editable = j.optBoolean("editable", true), done = j.optBoolean("done"), note = j.optString("note"),
    invite = InviteState.valueOf(j.optString("invite", "NONE")), incoming = j.optBoolean("incoming"), resolvedDay = j.date("resolvedDay"),
)
private fun writeCollection(c: DemoCollection) = JSONObject().put("id", c.id).put("name", c.name).put("shared", c.shared)
    .put("items", JSONArray(c.items.map { JSONObject().put("id", it.id).put("title", it.title).put("checked", it.checked) }))
private fun readCollection(j: JSONObject) = DemoCollection(j.getString("id"), j.getString("name"), j.getBoolean("shared"),
    j.getJSONArray("items").objects().map { CollectionItem(it.getString("id"), it.getString("title"), it.optBoolean("checked")) })
