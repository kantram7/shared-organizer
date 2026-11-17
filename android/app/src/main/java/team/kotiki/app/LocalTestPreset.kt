package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject
import java.time.LocalDate

data class DemoIdentity(val viewer: Person = Person.USER_1, val names: Map<Person,String> = emptyMap(), val personal: Boolean = false)
val LocalDemoIdentity = staticCompositionLocalOf { DemoIdentity() }
@Composable fun personLabel(person: Person): String = LocalDemoIdentity.current.names[person] ?: person.label

/** Optional fixture bundled only by a developer's local debug build. Not authentication. */
class LocalTestPreset(private val data: JSONObject,private val gallery:JSONObject?=null) {
    val galleryRevision:String? = gallery?.optString("revision")?.takeIf {it.isNotBlank()}
    fun galleryCollections(key:String):List<DemoCollection> = gallery?.let {readCollections(it,key)} ?: emptyList()
    val id: String = data.getString("id").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
    val defaultUser: Person = Person.valueOf(data.optString("defaultUser", "USER_1")).also { require(it != Person.BOTH) }
    val names = listOf(Person.USER_1,Person.USER_2).associateWith { data.getJSONObject("names").getString(it.name) }
    val theme: String = data.optString("theme","light").also { require(it in listOf("light","dark","system")) }
    val retentionDays: Int = data.optInt("retentionDays",7).coerceIn(1,365)
    fun entries(today: LocalDate): List<Entry> {
        val entries = data.getJSONArray("entries")
        return (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val day = if (item.isNull("dayOffset")) null else today.plusDays(item.getInt("dayOffset").toLong())
            Entry(id=item.getString("id"),title=item.getString("title"),date=day,week=mondayOf(day ?: today),
                lastDate=day?.plusDays(item.optInt("durationDays",1).toLong()-1),
                start=item.optString("start"),end=item.optString("end"),kind=Kind.valueOf(item.optString("kind","TASK")),
                who=Person.valueOf(item.optString("who","BOTH")),owner=Person.valueOf(item.optString("owner","USER_1")),
                visibility=Visibility.valueOf(item.optString("visibility","SHARED")),editable=item.optBoolean("editable",true),
                done=item.optBoolean("done"),note=item.optString("note"),invite=InviteState.valueOf(item.optString("invite","NONE")),isGathering=item.optBoolean("isGathering",false))
        }
    }
    fun collections(key:String):List<DemoCollection> = readCollections(data,key)
    private fun readCollections(source:JSONObject,key: String): List<DemoCollection> {
        val groups = source.getJSONArray(key)
        return (0 until groups.length()).map { index ->
            val group=groups.getJSONObject(index)
            val id=group.getString("id")
            val items=group.getJSONArray("items")
            DemoCollection(id,group.getString("name"),group.optBoolean("shared",true),
                (0 until items.length()).map { CollectionItem("$id-$it",items.getString(it)) },
                Person.valueOf(group.optString("owner","USER_1")))
        }
    }
    companion object {
        fun load(context: Context): LocalTestPreset? {
            if (context.assets.list("")?.contains("local-test-preset.json") != true) return null
            val gallery=if(context.assets.list("")?.contains("collection-gallery.json")==true)context.assets.open("collection-gallery.json").bufferedReader().use{JSONObject(it.readText())} else null
            return LocalTestPreset(context.assets.open("local-test-preset.json").bufferedReader().use { JSONObject(it.readText()) },gallery)
        }
    }
}
