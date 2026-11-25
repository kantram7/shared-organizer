package team.kotiki.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

@Composable fun CalendarScreen(store:DemoStore,onOpen:(Entry)->Unit,onAdd:(Entry)->Unit,onHelp:(String)->Unit) {
    val today=LocalDate.now()
    val epoch=YearMonth.of(1900,1)
    val currentMonthIndex=ChronoUnit.MONTHS.between(epoch,YearMonth.from(today)).toInt()
    val pager=rememberPagerState(initialPage=currentMonthIndex,pageCount={2400})
    val scope=rememberCoroutineScope()
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    var filter by rememberSaveable { mutableStateOf(OwnerFilter.ALL) }
    val newState=LocalEntryNewState.current
    LaunchedEffect(dayText,filter){newState?.refresh()}
    val selected=LocalDate.parse(dayText)
    val records=visibleEntries(store.entries,filter,store.viewer)
    val taskColor=if(winterDesignEnabled())Color(0xFFAE771F)else MaterialTheme.colorScheme.secondary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Календарь","+ Событие",{onAdd(Entry(newId(),"",selected,mondayOf(selected),kind=Kind.EVENT,owner=store.viewer))})
        ParticipantFilter(filter){filter=it}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={scope.launch{pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}},modifier=Modifier.semantics{contentDescription="Предыдущий месяц"}){KotikiIcon(Glyph.LEFT)}
            Text(epoch.plusMonths(pager.currentPage.toLong()).format(DateTimeFormatter.ofPattern("LLLL yyyy",russian)),Modifier.weight(1f),fontSize=14.sp)
            IconButton(onClick={scope.launch{pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(pager.pageCount-1))}},modifier=Modifier.semantics{contentDescription="Следующий месяц"}){KotikiIcon(Glyph.RIGHT)}
        }
        TextButton({dayText=today.toString();scope.launch{pager.animateScrollToPage(currentMonthIndex)}}){Text("Сегодня")}
        Row(Modifier.padding(horizontal=2.dp)) {listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{Text(it,Modifier.weight(1f),fontSize=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        // Fixed-height pages prevent events or multi-day spans from stretching a week.
        HorizontalPager(pager,Modifier.fillMaxWidth().height(276.dp),key={it}){page->
            val month=epoch.plusMonths(page.toLong())
            val start=mondayOf(month.atDay(1))
            Column(Modifier.fillMaxWidth()) {
                repeat(6){row->CalendarWeek(start.plusDays(row*7L),month,selected,today,records,taskColor,row){dayText=it.toString()}}
            }
        }
        FlowRow(Modifier.padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary,CircleShape))
                Text("События",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(3.dp).background(taskColor,CircleShape))
                Text("Дела на день",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(fullDayLabel(selected),Modifier.padding(bottom=10.dp),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val dayEntries=records.filter{it.occursOn(selected)}.sortedWith(compareBy<Entry>{it.start.isNotBlank()}.thenBy{it.start})
            dayEntries.forEachIndexed {index,entry->RecordCard(entry,onOpen,store::toggleDone,onHelp,store::acceptInvitation,compact=true,iceSpace=if(index==dayEntries.lastIndex)12.dp else 0.dp)}
            if(dayEntries.isEmpty())Text("На этот день пока ничего нет",style=MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun CalendarWeek(first:LocalDate,month:YearMonth,selected:LocalDate,today:LocalDate,records:List<Entry>,taskColor:Color,row:Int,onSelect:(LocalDate)->Unit) {
    val last=first.plusDays(6)
    val shape=RoundedCornerShape(14.dp)
    BoxWithConstraints(Modifier.padding(vertical=4.dp).fillMaxWidth().height(38.dp).testTag("calendar-week-$row")
        .clip(shape).background(layeredSurface(if(winterDesignEnabled()).32f else .72f))
        .border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f),shape)) {
        Row(Modifier.fillMaxSize().padding(horizontal=2.dp)) {
            repeat(7){index->
                val date=first.plusDays(index.toLong())
                val dayEntries=records.filter{it.occursOn(date)}
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(if(date==selected)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.85f)else Color.Transparent)
                    .clickable{onSelect(date)}.alpha(if(YearMonth.from(date)==month)1f else .4f)
                    .semantics{contentDescription=fullDayLabel(date)},contentAlignment=Alignment.Center) {
                    // Decorations are absolute siblings: the number stays vertically centered.
                    Text(date.dayOfMonth.toString(),fontSize=if(date==today)14.sp else 12.sp,fontWeight=if(date==today)FontWeight.Bold else FontWeight.Normal,
                        color=if(date==today)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                    val gatherings=dayEntries.filter{it.isGathering}
                    if(gatherings.isNotEmpty())Icon(painterResource(R.drawable.ic_calendar_heart),"Общий сбор",Modifier.align(Alignment.TopCenter).padding(top=2.dp).size(7.dp).alpha(if(gatherings.all{it.isTentative}).5f else 1f),tint=gatheringAccent())
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        dayEntries.filter{!it.isMultiDay && !it.isGathering}.distinctBy{it.isDayTask to it.isTentative}.take(3).forEach{entry->
                            Box(Modifier.size(3.dp).alpha(if(entry.isTentative).4f else 1f).background(if(entry.isDayTask)taskColor else MaterialTheme.colorScheme.primary,CircleShape))
                        }
                    }
                }
            }
        }
        // Span lines deliberately share the dot baseline and cover dots underneath.
        val cell=(maxWidth-4.dp)/7
        records.filter{it.isMultiDay && it.date!!<=last && it.lastDate!!>=first}.forEach{entry->
            val from=ChronoUnit.DAYS.between(first,entry.date!!).toInt().coerceIn(0,6)
            val to=(ChronoUnit.DAYS.between(first,entry.lastDate!!).toInt()+1).coerceIn(1,7)
            Box(Modifier.align(Alignment.BottomStart).offset(x=2.dp+cell*from,y=(-4).dp)
                .width(cell*(to-from)).height(3.dp).alpha(if(entry.isTentative).5f else 1f).background(MaterialTheme.colorScheme.primary,CircleShape))
        }
    }
}
