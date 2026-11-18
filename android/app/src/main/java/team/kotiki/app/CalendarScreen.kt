package team.kotiki.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable fun CalendarScreen(store:DemoStore,onOpen:(Entry)->Unit,onAdd:(Entry)->Unit,onHelp:(String)->Unit) {
    val today=LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    var filter by rememberSaveable { mutableStateOf(OwnerFilter.ALL) }
    val month=YearMonth.parse(monthText)
    val selected=LocalDate.parse(dayText)
    val records=visibleEntries(store.entries,filter,store.viewer)
    val start=mondayOf(month.atDay(1))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Календарь","+ Событие",{onAdd(Entry(newId(),"",selected,mondayOf(selected),kind=Kind.EVENT,owner=store.viewer))})
        ParticipantFilter(filter){filter=it}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={monthText=month.minusMonths(1).toString()},modifier=Modifier.semantics{contentDescription="Предыдущий месяц"}){KotikiIcon(Glyph.LEFT)}
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",russian)),Modifier.weight(1f),fontSize=14.sp)
            IconButton(onClick={monthText=month.plusMonths(1).toString()},modifier=Modifier.semantics{contentDescription="Следующий месяц"}){KotikiIcon(Glyph.RIGHT)}
        }
        TextButton({monthText=YearMonth.from(today).toString();dayText=today.toString()}) {Text("Сегодня")}
        Row { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { Text(it,Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        repeat(6) { row ->
            val first=start.plusDays(row*7L)
            val last=first.plusDays(6)
            Column(Modifier.padding(vertical=4.dp)) {
                Row {
                    repeat(7) { index ->
                        val date=first.plusDays(index.toLong())
                        val entries=records.filter { it.occursOn(date) && !it.isMultiDay }
                        Column(Modifier.weight(1f).height(49.dp).clip(RoundedCornerShape(9.dp))
                            .background(if(date==selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                            .clickable { dayText=date.toString() }.alpha(if(YearMonth.from(date)==month)1f else .4f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            Text(date.dayOfMonth.toString(),fontSize=12.sp,color=if(date==today)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                            Row(Modifier.height(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                                entries.sortedByDescending{it.isGathering}.distinctBy { Triple(it.isDayTask,it.isTentative,it.isGathering) }.take(3).forEach { entry ->
                                    if(entry.isGathering)KotikiIcon(Glyph.MEETING,Modifier.size(12.dp).alpha(if(entry.isTentative).6f else 1f).semantics{contentDescription="Общий сбор"},gatheringAccent())
                                    else Box(Modifier.padding(top=3.dp).size(width=if(entry.isDayTask)7.dp else 4.dp,height=3.dp).alpha(if(entry.isTentative).4f else 1f).background(if(entry.isDayTask)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,CircleShape))
                                }
                            }
                        }
                    }
                }
                records.filter { it.isMultiDay && it.date!!<=last && it.lastDate!!>=first }.forEach { entry ->
                    val from=ChronoUnit.DAYS.between(first,entry.date!!).toInt().coerceIn(0,6)
                    val to=(ChronoUnit.DAYS.between(first,entry.lastDate!!).toInt()+1).coerceIn(1,7)
                    Row(Modifier.fillMaxWidth().padding(top=2.dp)) {
                        if(from>0)Spacer(Modifier.weight(from.toFloat()))
                        Box(Modifier.weight((to-from).toFloat()).height(5.dp).alpha(if(entry.isTentative).5f else 1f)
                            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                        if(to<7)Spacer(Modifier.weight((7-to).toFloat()))
                    }
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        }
        FlowRow(Modifier.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary,CircleShape))
                Text("События",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width=8.dp,height=3.dp).background(MaterialTheme.colorScheme.secondary,CircleShape))
                Text("Дела на день",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(fullDayLabel(selected),Modifier.padding(bottom=10.dp),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            records.filter { it.occursOn(selected) }.forEach { RecordCard(it,onOpen,store::toggleDone,onHelp,store::acceptInvitation) }
            if(records.none { it.occursOn(selected) })Text("На этот день пока ничего нет",style=MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}
