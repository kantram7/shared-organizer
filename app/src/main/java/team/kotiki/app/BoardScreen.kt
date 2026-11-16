package team.kotiki.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable fun BoardScreen(store: DemoStore, onOpen: (Entry) -> Unit, onAdd: (Entry) -> Unit, onHelp: (String) -> Unit) {
    val today = LocalDate.now()
    var weekString by rememberSaveable { mutableStateOf(mondayOf(today).toString()) }
    val week = LocalDate.parse(weekString)
    var filter by rememberSaveable { mutableStateOf(OwnerFilter.ALL) }
    val initialDay = if (week == mondayOf(today)) today.dayOfWeek.value - 1 else 0
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = initialDay)
    LaunchedEffect(weekString) { strip.scrollToItem(initialDay) }
    val records = visibleEntries(store.entries, filter)
    val dates = (0..6).map { week.plusDays(it.toLong()) }
    fun add(date: LocalDate?) = onAdd(Entry(newId(), "", date, week, kind = if(date == null) Kind.TASK else Kind.EVENT))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Наша неделя", "+ Добавить", { add(dates[strip.firstVisibleItemIndex.coerceIn(0, 6)]) })
        ParticipantFilter(filter) { filter = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { weekString = week.minusWeeks(1).toString() }, modifier=Modifier.semantics { contentDescription="Предыдущая неделя" }) { KotikiIcon(Glyph.LEFT) }
            Text("${dateLabel(week)} — ${dateLabel(week.plusDays(6))}", Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { weekString = week.plusWeeks(1).toString() }, modifier=Modifier.semantics { contentDescription="Следующая неделя" }) { KotikiIcon(Glyph.RIGHT) }
        }
        if(week != mondayOf(today)) TextButton(onClick={weekString=mondayOf(today).toString()}) { Text("Эта неделя") }
        // Observe a stable scalar, not a fresh LazyListLayoutInfo on every measure pass.
        // Reading layoutInfo directly in composition feeds measurement back into recomposition.
        val visibleCount by remember { derivedStateOf { strip.layoutInfo.visibleItemsInfo.size } }
        val progress = strip.firstVisibleItemIndex / 7f
        val visible = (visibleCount.coerceAtLeast(1) / 7f).coerceAtMost(1f - progress)
        BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp).padding(vertical=4.dp).semantics { contentDescription="Положение в ленте дней" }) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.offset(x=maxWidth*progress).width(maxWidth*visible).height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth - 18.dp).coerceAtMost(270.dp)
            LazyRow(state=strip, horizontalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(end=(maxWidth-columnWidth).coerceAtLeast(0.dp))) {
                itemsIndexed(dates, key={_,day->day.toString()}) { _, day ->
                    val entries = records.filter { it.occursOn(day) }
                    val active = entries.filterNot { it.done }.sortedWith(compareBy<Entry> { !it.isDayTask }.thenBy { it.start })
                    val done = entries.filter { it.done }
                    Column(Modifier.width(columnWidth), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(fullDayLabel(day), fontSize=14.sp, color=if(day==today)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        active.forEach { RecordCard(it,onOpen,store::toggleDone,onHelp) }
                        if(entries.isEmpty()) Text("Без планов", color=MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(vertical=12.dp))
                        OutlinedButton(onClick={add(day)},modifier=Modifier.fillMaxWidth()) { Text("+") }
                        CompletedGroup(done,onOpen,store::toggleDone,onHelp)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top=16.dp,bottom=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment=Alignment.CenterVertically) { Text("На неделе, без дня",Modifier.weight(1f),fontSize=15.sp);TextButton(onClick={add(null)}) { Text("+ Дело") } }
        val undated = records.filter { it.date==null && it.week==week }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            undated.filterNot { it.done }.forEach { RecordCard(it,onOpen,store::toggleDone,onHelp) }
            CompletedGroup(undated.filter { it.done },onOpen,store::toggleDone,onHelp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun CompletedGroup(entries:List<Entry>,onOpen:(Entry)->Unit,onDone:(Entry)->Unit,onHelp:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Text("Выполнено · ${entries.size}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEach { RecordCard(it,onOpen,onDone,onHelp) }
        if(entries.isEmpty()) Text("Пока пусто",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
