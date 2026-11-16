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
    BOARD("Доска"), PLANS("Планы"), CALENDAR("Календарь"), LISTS("Списки"), INVITES("Зовут"), SETTINGS("Настройки")
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
) {
    val isTentative: Boolean get() = invite == InviteState.PENDING
    val isDayTask: Boolean get() = kind == Kind.TASK && start.isEmpty()
    val isMultiDay: Boolean get() = date != null && lastDate != null && lastDate > date
    fun occursOn(day: LocalDate): Boolean = date != null && day >= date && day <= (lastDate ?: date)
    fun visibleToUser1(): Boolean = visibility != Visibility.PRIVATE || owner == Person.USER_1
    fun matches(filter: OwnerFilter): Boolean = filter == OwnerFilter.ALL || who == Person.BOTH ||
        who == if (filter == OwnerFilter.MINE) Person.USER_1 else Person.USER_2
    fun redactForUser1(): Entry = if (owner == Person.USER_2 && visibility == Visibility.BUSY)
        copy(title = "Занято", note = "", editable = false) else this
    fun canEdit(): Boolean = (owner == Person.USER_1 || (visibility == Visibility.SHARED && editable)) && invite == InviteState.NONE
}
data class CollectionItem(val id: String, val title: String, val checked: Boolean = false)
data class DemoCollection(val id: String, val name: String, val shared: Boolean = true, val items: List<CollectionItem> = emptyList())

fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)
val russian: Locale = Locale.forLanguageTag("ru")
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM", russian))
fun fullDayLabel(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, russian)
    .replaceFirstChar { it.titlecase(russian) } + ", " + dateLabel(date)
fun visibleEntries(entries: List<Entry>, filter: OwnerFilter): List<Entry> = entries
    .filter { it.invite != InviteState.DECLINED && it.visibleToUser1() && it.matches(filter) }
    .map { it.redactForUser1() }

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
