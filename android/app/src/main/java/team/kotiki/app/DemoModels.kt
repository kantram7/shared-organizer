package team.kotiki.app

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class Person(val label: String) { USER_1("Пользователь 1"), USER_2("Пользователь 2"), BOTH("Мы оба") }
enum class Kind { TASK, EVENT }
enum class Visibility { SHARED, PRIVATE, BUSY }
enum class OwnerFilter(val label: String) { MINE("Мои"), USER_2("Пользователь 2"), ALL("Все") }
enum class InviteState { NONE, PENDING, ACCEPTED, DECLINED }
enum class Section(val label: String) {
    BOARD("Доска"), PLANS("Планы"), CALENDAR("Календарь"), LISTS("Списки"), INVITES("Зовут"), CHAT("Чат"), SETTINGS("Настройки")
}
data class Entry(
    val id: String,
    val title: String,
    val date: LocalDate?,
    val week: LocalDate,
    val lastDate: LocalDate? = date,
    val start: String = "",
    val end: String = "",
    val kind: Kind = Kind.TASK,
    val who: Person = Person.BOTH,
    val owner: Person = Person.USER_1,
    val visibility: Visibility = Visibility.SHARED,
    val editable: Boolean = true,
    val done: Boolean = false,
    val note: String = "",
    val invite: InviteState = InviteState.NONE,
    val incoming: Boolean = false,
    val resolvedDay: LocalDate? = null,
    val isGathering: Boolean = false,
    val proposal: InviteProposal? = null,
    val proposalResponseId: String = "",
) : java.io.Serializable {
    val isTentative: Boolean get() = invite == InviteState.PENDING
    val isDayTask: Boolean get() = kind == Kind.TASK && start.isEmpty()
    val isMultiDay: Boolean get() = date != null && lastDate != null && lastDate > date
    fun occursOn(day: LocalDate): Boolean = date != null && day >= date && day <= (lastDate ?: date)
    fun visibleTo(viewer: Person): Boolean = visibility != Visibility.PRIVATE || owner == viewer
    fun matches(filter: OwnerFilter, viewer: Person = Person.USER_1): Boolean = filter == OwnerFilter.ALL || who == Person.BOTH ||
        who == if (filter == OwnerFilter.MINE) viewer else viewer.other()
    fun redactFor(viewer: Person): Entry = if (owner != viewer && visibility == Visibility.BUSY)
        copy(title = "Занято", note = "", editable = false, proposal = null) else this
    fun canAccept(viewer:Person):Boolean = InviteWorkflow.responder(this)==viewer && visibleTo(viewer)
    fun canEdit(viewer: Person = Person.USER_1): Boolean = (owner == viewer || (visibility == Visibility.SHARED && editable)) && invite == InviteState.NONE
}
data class CollectionItem(val id: String, val title: String, val checked: Boolean = false)
data class DemoCollection(val id: String, val name: String, val shared: Boolean = true, val items: List<CollectionItem> = emptyList(), val owner: Person = Person.USER_1, val updatedAt: Long = 0L)

fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)
val russian: Locale = Locale.forLanguageTag("ru")
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM", russian))
fun fullDayLabel(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, russian)
    .replaceFirstChar { it.titlecase(russian) } + ", " + dateLabel(date)
fun Person.other(): Person = if (this == Person.USER_1) Person.USER_2 else Person.USER_1
fun visibleEntries(entries: List<Entry>, filter: OwnerFilter, viewer: Person = Person.USER_1): List<Entry> = entries
    .filter { it.invite != InviteState.DECLINED && it.visibleTo(viewer) && it.matches(filter, viewer) }
    .map { it.redactFor(viewer).let { visible -> if (visible.invite == InviteState.NONE) visible else visible.copy(incoming = visible.owner != viewer) } }

fun demoEntries(today: LocalDate): List<Entry> {
    val week = mondayOf(today)
    return listOf(
        Entry("coffee", "Заказать кофе", today, week, who = Person.USER_1),
        Entry("movie", "Выбрать фильм", today, week),
        Entry("gift", "Выбрать подарок", today, week, who = Person.USER_1, visibility = Visibility.PRIVATE),
        Entry("dinner", "Вечер вдвоём", today, week, start = "19:00", end = "21:00", kind = Kind.EVENT, note = "Ужин и прогулка"),
        Entry("busy", "Занято", today.plusDays(1), week, start = "15:00", end = "16:00", kind = Kind.EVENT, who = Person.USER_2, owner = Person.USER_2, visibility = Visibility.BUSY, editable = false),
        Entry("trip", "За город", week.plusDays(5), week, lastDate = week.plusDays(7), kind = Kind.EVENT),
        Entry("package", "Забрать заказ", today, week, who = Person.USER_1, done = true),
        Entry("photos", "Разобрать фотографии", null, week),
        Entry("weekend", "Выбрать место для выходных", null, week),
        Entry("invite-in", "Выставка и кофе", today.plusDays(2), week, start = "18:00", end = "20:00", kind = Kind.EVENT, owner = Person.USER_2, note = "Давай сходим вместе?", invite = InviteState.PENDING, incoming = true),
        Entry("invite-out", "Прогулка после работы", today.plusDays(1), week, start = "19:00", end = "20:00", kind = Kind.EVENT, invite = InviteState.PENDING),
        Entry("invite-history", "Завтрак вдвоём", today.minusDays(1), mondayOf(today.minusDays(1)), kind = Kind.EVENT, invite = InviteState.ACCEPTED, incoming = true, resolvedDay = today.minusDays(1)),
        Entry("invite-archive", "Поход на рынок", today.minusDays(15), mondayOf(today.minusDays(15)), kind = Kind.EVENT, invite = InviteState.DECLINED, resolvedDay = today.minusDays(10)),
    )
}

fun newGathering(owner:Person,today:LocalDate=LocalDate.now()) = Entry(newId(),"Собрание котиков",null,mondayOf(today),
    kind=Kind.EVENT,who=Person.BOTH,owner=owner,invite=InviteState.PENDING,isGathering=true)

fun Entry.gatheringError():String? {
    if(title.isBlank())return "Укажи название"
    if(date==null)return "Выбери дату"
    val from=runCatching{java.time.LocalTime.parse(start)}.getOrNull() ?: return "Выбери время начала"
    val to=runCatching{java.time.LocalTime.parse(end)}.getOrNull() ?: return "Выбери время окончания"
    if(to<=from)return "Время окончания должно быть позже начала"
    if(kind!=Kind.EVENT || who!=Person.BOTH || visibility!=Visibility.SHARED || invite==InviteState.NONE || lastDate!=date)
        return "Общий сбор — событие-приглашение для вас обоих на одну дату"
    return null
}
