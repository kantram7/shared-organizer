package team.kotiki.app

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable fun BoardScreen(store: DemoStore,onOpen:(Entry)->Unit,onAdd:(Entry)->Unit,onHelp:(String)->Unit) {
    val today=LocalDate.now()
    var weekString by rememberSaveable { mutableStateOf(mondayOf(today).toString()) }
    var filter by rememberSaveable { mutableStateOf(OwnerFilter.ALL) }
    val week=LocalDate.parse(weekString)
    val dates=(0..6).map { week.plusDays(it.toLong()) }
    val strip=rememberLazyListState(if(week==mondayOf(today))today.dayOfWeek.value-1 else 0)
    var pendingDay by rememberSaveable { mutableStateOf<Int?>(null) }
    val overviewList=rememberLazyListState(if(week==mondayOf(today))today.dayOfWeek.value-1 else 0)
    var pendingOverviewDay by rememberSaveable {mutableStateOf<Int?>(null)}
    val daysScroll=rememberScrollState()
    val overview=store.boardOverview
    LaunchedEffect(overview,pendingDay) {
        if(!overview && pendingDay!=null) { strip.scrollToItem(pendingDay!!); daysScroll.scrollTo(0); pendingDay=null }
    }
    LaunchedEffect(overview,pendingOverviewDay) {
        if(overview && pendingOverviewDay!=null){overviewList.scrollToItem(pendingOverviewDay!!);pendingOverviewDay=null}
    }
    fun changeWeek(day:LocalDate) {weekString=day.toString();pendingDay=if(day==mondayOf(today))today.dayOfWeek.value-1 else 0;pendingOverviewDay=pendingDay}
    fun add(day:LocalDate?)=onAdd(Entry(newId(),"",day,week,kind=if(day==null)Kind.TASK else Kind.EVENT,owner=store.viewer))
    val records=visibleEntries(store.entries,filter,store.viewer)
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Наша неделя","+ Добавить",{add(if(overview) { if(today in dates)today else week } else dates[strip.firstVisibleItemIndex.coerceIn(0,6)])})
        ParticipantFilter(filter){filter=it}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(overview,{store.updateBoardOverview(true)},label={Text("Обзор недели")})
            FilterChip(!overview,{store.updateBoardOverview(false)},label={Text("Дни")})
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton({changeWeek(week.minusWeeks(1))},Modifier.semantics{contentDescription="Предыдущая неделя"}){KotikiIcon(Glyph.LEFT)}
            Text("Неделя ${dateLabel(week)} — ${dateLabel(week.plusDays(6))}",Modifier.weight(1f),fontSize=14.sp)
            IconButton({changeWeek(week.plusWeeks(1))},Modifier.semantics{contentDescription="Следующая неделя"}){KotikiIcon(Glyph.RIGHT)}
        }
        if(week!=mondayOf(today))TextButton({changeWeek(mondayOf(today))}){Text("Эта неделя")}
        if(overview) {
            LazyColumn(state=overviewList,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=8.dp,bottom=24.dp)) {
            itemsIndexed(dates,key={_,day->day.toString()}) {index,day->
            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
                border=BorderStroke(if(day==today)2.dp else 1.dp,MaterialTheme.colorScheme.primary.copy(alpha=if(day==today).8f else .32f))) {
            Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)) {
                val entries=records.filter { it.occursOn(day) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(fullDayLabel(day),Modifier.weight(1f).clickable {pendingDay=index;store.updateBoardOverview(false)}.padding(vertical=12.dp),
                        fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=if(day==today)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton({add(day)},Modifier.semantics {contentDescription="Добавить: ${fullDayLabel(day)}"}) {KotikiIcon(Glyph.PLUS)}
                }
                entries.filterNot {it.done}.sortedWith(compareBy<Entry>{!it.isDayTask}.thenBy{it.start}).forEach {entry ->
                    OverviewRecord(entry,onOpen,store::toggleDone,onHelp,store::acceptInvitation)
                    Spacer(Modifier.height(5.dp))
                }
                CompletedGroup(entries.filter{it.done},onOpen,store::toggleDone,onHelp,store::acceptInvitation,compact=true)
            } }
            }
            item(key="undated"){UndatedBoardGroup(store,records,week,onOpen,onHelp,::add,true)}
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(daysScroll)) {
            val visibleCount by remember {derivedStateOf {strip.layoutInfo.visibleItemsInfo.size}}
            val progress=strip.firstVisibleItemIndex/7f
            val visible=(visibleCount.coerceAtLeast(1)/7f).coerceAtMost(1f-progress)
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp).padding(vertical=4.dp).semantics{contentDescription="Положение в ленте дней"}) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.offset(x=maxWidth*progress).width(maxWidth*visible).height(4.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width=(maxWidth-18.dp).coerceAtMost(270.dp)
                LazyRow(state=strip,horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(end=(maxWidth-width).coerceAtLeast(0.dp))) {
                    itemsIndexed(dates,key={_,day->day.toString()}) {_,day->
                        val entries=records.filter{it.occursOn(day)}
                        Column(Modifier.width(width),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(fullDayLabel(day),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=if(day==today)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            entries.filterNot{it.done}.sortedWith(compareBy<Entry>{!it.isDayTask}.thenBy{it.start}).forEach {RecordCard(it,onOpen,store::toggleDone,onHelp,store::acceptInvitation)}
                            if(entries.isEmpty())Text("Без планов",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton({add(day)},Modifier.fillMaxWidth()){Text("+")}
                            CompletedGroup(entries.filter{it.done},onOpen,store::toggleDone,onHelp,store::acceptInvitation)
                        }
                    }
                }
            }
                    UndatedBoardGroup(store,records,week,onOpen,onHelp,::add,false)
            }
        }
    }
}
@Composable private fun UndatedBoardGroup(store:DemoStore,records:List<Entry>,week:LocalDate,onOpen:(Entry)->Unit,onHelp:(String)->Unit,add:(LocalDate?)->Unit,overview:Boolean) {
    Column {
        HorizontalDivider(Modifier.padding(top=16.dp,bottom=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("На неделе, без дня",Modifier.weight(1f),fontSize=15.sp)
            TextButton({add(null)}){Text("+ Дело")}
        }
        val undated=records.filter{it.date==null && it.week==week}
        undated.filterNot{it.done}.forEach {
            if(overview)OverviewRecord(it,onOpen,store::toggleDone,onHelp,store::acceptInvitation) else RecordCard(it,onOpen,store::toggleDone,onHelp,store::acceptInvitation)
            Spacer(Modifier.height(5.dp))
        }
        key(week.toString()) {CompletedGroup(undated.filter{it.done},onOpen,store::toggleDone,onHelp,store::acceptInvitation,compact=overview)}
        Spacer(Modifier.height(24.dp))
    }
}
@Composable private fun OverviewRecord(entry:Entry,onOpen:(Entry)->Unit,onDone:(Entry)->Unit,onHelp:(String)->Unit,onAccept:(Entry)->Unit) {
    Surface(Modifier.fillMaxWidth(),
        shape=RoundedCornerShape(10.dp),color=if(entry.isGathering)gatheringTint() else participantTint(entry.who,entry.isDayTask)) {
        Column {
        Row(Modifier.alpha(if(entry.isTentative).65f else if(entry.done).55f else 1f).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            if(entry.kind==Kind.TASK && entry.canEdit(LocalDemoIdentity.current.viewer))Checkbox(entry.done,{onDone(entry)})
            Column(Modifier.weight(1f).clickable{onOpen(entry)}.padding(horizontal=6.dp,vertical=8.dp)) {
                if(!entry.isDayTask)Text(when {
                    entry.isMultiDay->"${dateLabel(entry.date!!)} — ${dateLabel(entry.lastDate!!)}"
                    entry.start.isNotBlank()->"${entry.start}–${entry.end}"
                    else->"Весь день"
                },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.title,fontSize=13.sp,textDecoration=if(entry.done)TextDecoration.LineThrough else null)
                if(entry.isGathering)GatheringLabel()
                if(entry.isTentative)Text(if(entry.owner!=LocalDemoIdentity.current.viewer)"Ждёт твоего ответа" else "Ждёт ответа: ${personLabel(LocalDemoIdentity.current.viewer.other())}",style=MaterialTheme.typography.labelSmall)
                if(entry.invite==InviteState.ACCEPTED)Text("Принято",style=MaterialTheme.typography.labelSmall)
            }
            AccessIcon(entry,onHelp)
        }
        InvitationAcceptButton(entry,onAccept,Modifier.padding(horizontal=10.dp))
        }
    }
}
@Composable private fun CompletedGroup(entries:List<Entry>,onOpen:(Entry)->Unit,onDone:(Entry)->Unit,onHelp:(String)->Unit,onAccept:(Entry)->Unit,compact:Boolean=false) {
    var expanded by rememberSaveable {mutableStateOf(false)}
    if(entries.isNotEmpty() || !compact) {
        TextButton({expanded=!expanded},enabled=entries.isNotEmpty(),contentPadding=PaddingValues(0.dp)) {
            Text("Выполнено · ${entries.size}${if(entries.isEmpty())"" else if(expanded)" ▴" else " ▾"}",style=MaterialTheme.typography.labelSmall)
        }
        if(expanded)entries.forEach {
            if(compact)OverviewRecord(it,onOpen,onDone,onHelp,onAccept) else RecordCard(it,onOpen,onDone,onHelp,onAccept)
        }
    }
}
