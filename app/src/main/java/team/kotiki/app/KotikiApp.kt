package team.kotiki.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable fun KotikiApp(store: DemoStore) {
    var page by rememberSaveable { mutableStateOf(Section.BOARD) }
    var editor by remember { mutableStateOf<Entry?>(null) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var help by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val today = LocalDate.now()
    fun open(entry: Entry) { if (entry.canEdit()) editor = entry else detail = entry }
    BackHandler(enabled = page != Section.BOARD && editor == null && detail == null && help == null) { page = Section.BOARD }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.primaryContainer) {
                    KotikiIcon(Glyph.CAT, Modifier.padding(10.dp).size(25.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column { Text("Kotiki team", fontSize = 21.sp, fontWeight = FontWeight.SemiBold); Text("Демо · данные на этом телефоне", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(62.dp).fillMaxHeight().background(colors.surface).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Section.entries.forEach { section ->
                        val glyph = when(section) {
                            Section.BOARD -> Glyph.BOARD; Section.PLANS -> Glyph.PLANS; Section.CALENDAR -> Glyph.CALENDAR
                            Section.LISTS -> Glyph.LIST; Section.INVITES -> Glyph.MAIL; Section.SETTINGS -> Glyph.SETTINGS
                        }
                        Column(Modifier.padding(horizontal = 3.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (page == section) colors.primaryContainer else colors.surface)
                            .clickable { page = section }.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            KotikiIcon(glyph)
                            Text(section.label, fontSize = 9.sp, maxLines = 1, color = if (page == section) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
                VerticalDivider(color = colors.outlineVariant)
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                    if (store.storageWarning) Text("Не удалось прочитать сохранённое демо", color = colors.error, style = MaterialTheme.typography.labelSmall)
                    when(page) {
                        Section.BOARD -> BoardScreen(store, ::open, { editor = it }, { help = it })
                        Section.PLANS -> CollectionsScreen(store, true, { help = it })
                        Section.CALENDAR -> CalendarScreen(store, ::open, { editor = it }, { help = it })
                        Section.LISTS -> CollectionsScreen(store, false, { help = it })
                        Section.INVITES -> InvitationsScreen(store, { detail = it }, {
                            editor = Entry(newId(), "", today, mondayOf(today), kind = Kind.EVENT, invite = InviteState.PENDING)
                        })
                        Section.SETTINGS -> SettingsScreen(store)
                    }
                }
            }
        }
    }
    editor?.let { entry -> EntryEditor(entry, onDismiss = { editor = null }, onSave = { store.upsert(it); editor = null }) }
    detail?.let { entry ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(entry.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.date?.let(::fullDayLabel) ?: "На неделе, без дня")
                if (entry.start.isNotEmpty()) Text("${entry.start}–${entry.end}")
                if (entry.note.isNotBlank()) Text(entry.note)
                if (entry.invite != InviteState.NONE) Text("Это пример приглашения. Отправка и ответы подключаются вместе с аккаунтами.", color = colors.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Понятно") } })
    }
    help?.let { text -> AlertDialog(onDismissRequest = { help = null }, title = { Text("Видимость") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { help = null }) { Text("Понятно") } }) }
}

@Composable fun ScreenHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(action) }
    }
}
@Composable fun ParticipantFilter(selected: OwnerFilter, onSelect: (OwnerFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(3.dp)) {
        OwnerFilter.entries.forEach { filter ->
            TextButton(onClick = { onSelect(filter) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = if (filter == selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)) { Text(filter.label) }
        }
    }
}
@Composable fun AccessIcon(entry: Entry, onHelp: (String) -> Unit) {
    val who = if (entry.who == Person.BOTH) "Пользователь 1 и Пользователь 2" else entry.who.label
    val info = when(entry.visibility) {
        Visibility.PRIVATE -> "$who. Видно только владельцу. Другой участник не видит запись."
        Visibility.BUSY -> "$who. Подробности видны только владельцу. Другому видна занятость."
        Visibility.SHARED -> "$who. Видно вам обоим."
    }
    val glyph = when(entry.visibility) {
        Visibility.PRIVATE -> Glyph.SHIELD_CAT
        Visibility.BUSY -> Glyph.EYE_OFF
        Visibility.SHARED -> if (entry.who == Person.BOTH) Glyph.PAIR else Glyph.CAT
    }
    IconButton(onClick = { onHelp(info) }, modifier = Modifier.size(44.dp).semantics { contentDescription = info }) {
        KotikiIcon(glyph, Modifier.size(if (glyph == Glyph.SHIELD_CAT) 32.dp else 24.dp))
    }
}
@Composable fun RecordCard(entry: Entry, onOpen: (Entry) -> Unit, onDone: (Entry) -> Unit, onHelp: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().alpha(if (entry.isTentative) .58f else if (entry.done) .55f else 1f),
        shape = RoundedCornerShape(if (entry.isDayTask) 10.dp else 15.dp),
        color = if (entry.isDayTask) colors.secondaryContainer else colors.surface) {
        if (entry.isDayTask) {
            Row(Modifier.padding(start = 2.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.canEdit()) Checkbox(checked = entry.done, onCheckedChange = { onDone(entry) }, modifier = Modifier.size(42.dp))
                Text(entry.title, Modifier.weight(1f).clickable { onOpen(entry) }.padding(vertical = 9.dp), fontSize = 13.sp,
                    textDecoration = if(entry.done) TextDecoration.LineThrough else null)
                AccessIcon(entry, onHelp)
            }
        } else {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when { entry.isMultiDay -> "${dateLabel(entry.date!!)} — ${dateLabel(entry.lastDate!!)}"; entry.start.isNotBlank() -> "${entry.start}–${entry.end}"; else -> "Весь день" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    AccessIcon(entry, onHelp)
                }
                Text(entry.title, Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(vertical = 5.dp), fontWeight = FontWeight.Medium)
                if(entry.isTentative) Text(if(entry.incoming) "Ждёт твоего ответа" else "Ждёт ответа Пользователя 2", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                if(entry.kind == Kind.TASK && entry.canEdit()) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(entry.done, { onDone(entry) }); Text("Выполнено", style=MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
