package team.kotiki.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.LocalTime

@Composable fun EntryEditor(original:Entry,onDismiss:()->Unit,onSave:(Entry)->Unit,onDelete:(()->Unit)?=null) {
    if(original.isGathering){GatheringEditor(original,onDismiss,onSave);return}
    val context=LocalContext.current
    val viewer=LocalDemoIdentity.current.viewer
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var note by rememberSaveable(original.id) { mutableStateOf(original.note) }
    var kind by rememberSaveable(original.id) { mutableStateOf(original.kind) }
    var date by rememberSaveable(original.id) { mutableStateOf(original.date) }
    var last by rememberSaveable(original.id) { mutableStateOf(original.lastDate ?: original.date) }
    var timed by rememberSaveable(original.id) { mutableStateOf(original.start.isNotEmpty()) }
    var start by rememberSaveable(original.id) { mutableStateOf(original.start.ifEmpty { "19:00" }) }
    var end by rememberSaveable(original.id) { mutableStateOf(original.end.ifEmpty { "20:00" }) }
    var who by rememberSaveable(original.id) { mutableStateOf(original.who) }
    var shared by rememberSaveable(original.id) { mutableStateOf(original.visibility==Visibility.SHARED) }
    var busy by rememberSaveable(original.id) { mutableStateOf(original.visibility!=Visibility.PRIVATE) }
    var editable by rememberSaveable(original.id) { mutableStateOf(original.editable) }
    var error by rememberSaveable(original.id) { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    val titleTarget=remember {BringIntoViewRequester()}
    val dateTarget=remember {BringIntoViewRequester()}
    LaunchedEffect(title,date,last,timed,start,end,kind) {error=""}
    fun pickDate(current:LocalDate?,onPick:(LocalDate)->Unit) {
        val day=current ?: LocalDate.now()
        DatePickerDialog(context,{_,y,m,d->onPick(LocalDate.of(y,m+1,d))},day.year,day.monthValue-1,day.dayOfMonth).show()
    }
    fun pickTime(current:String,onPick:(String)->Unit) {
        val time=LocalTime.parse(current)
        TimePickerDialog(context,{_,h,m->onPick("%02d:%02d".format(h,m))},time.hour,time.minute,true).show()
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(original.invite==InviteState.PENDING)"Приглашение · демо" else if(original.title.isEmpty())"Новая запись" else "Редактирование",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    if(onDelete!=null)ItemActions(original.title,onDelete)
                    IconButton(onClick=onDismiss,modifier=Modifier.semantics{contentDescription="Закрыть редактирование"}){KotikiIcon(Glyph.CLOSE)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title,{title=it.take(100)},Modifier.fillMaxWidth().bringIntoViewRequester(titleTarget),label={Text("Название")},singleLine=true,
                    isError=error=="Укажи название",supportingText={if(error=="Укажи название")Text(error)})
                OutlinedTextField(note,{note=it.take(300)},Modifier.fillMaxWidth(),label={Text("Комментарий")},minLines=2)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind==Kind.EVENT,{kind=Kind.EVENT;if(date==null)date=LocalDate.now()},label={Text("Событие")})
                    FilterChip(kind==Kind.TASK,{kind=Kind.TASK},label={Text("Дело")})
                }
                Text("Участники",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    Person.entries.forEach { p->FilterChip(who==p,{who=p},enabled=shared || p==viewer,label={Text(personLabel(p))}) }
                }
                Column(Modifier.bringIntoViewRequester(dateTarget)) {
                OutlinedButton(onClick={pickDate(date){date=it;if(last==null || last!!<it)last=it}},modifier=Modifier.fillMaxWidth()) { Text(date?.let(::fullDayLabel) ?: "Выбрать день") }
                if(kind==Kind.TASK) Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(date==null,{if(it){date=null;last=null;timed=false}else{date=LocalDate.now();last=date}})
                    Text("На неделе, без дня")
                }
                if(kind==Kind.EVENT)OutlinedButton(onClick={pickDate(last ?: date){last=it}},modifier=Modifier.fillMaxWidth()) { Text("До ${dateLabel(last ?: date ?: LocalDate.now())} включительно") }
                if(date!=null) {
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(timed,{timed=it});Text("Есть точное время")}
                    if(timed)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={pickTime(start){start=it}},modifier=Modifier.weight(1f)){Text(start)}
                        OutlinedButton(onClick={pickTime(end){end=it}},modifier=Modifier.weight(1f)){Text(end)}
                    }
                }
                if(error.isNotEmpty() && error!="Укажи название")Text(error,color=MaterialTheme.colorScheme.error)
                }
                if(original.invite==InviteState.NONE) {
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(shared,{shared=it;if(!it){who=viewer;editable=false}});Text("Видно всем")}
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(editable,{editable=it},enabled=shared);Text("Совместное редактирование")}
                    if(!shared)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(busy,{busy=it});Text("Показывать занятость")}
                } else Text("Сохранится только пример на этом телефоне. Другой участник не получит приглашение.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Button(onClick={
                    val finalLast=if(kind==Kind.TASK)date else last ?: date
                    error=when {
                        title.isBlank()->"Укажи название"
                        kind==Kind.EVENT && date==null->"Выбери дату"
                        date!=null && finalLast!=null && finalLast<date->"Окончание не может быть раньше начала"
                        timed && date!=null && finalLast==date && end<=start->"Время окончания должно быть позже начала"
                        else->""
                    }
                    if(error.isNotEmpty())scope.launch {if(error=="Укажи название")titleTarget.bringIntoView() else dateTarget.bringIntoView()}
                    if(error.isEmpty())onSave(original.copy(title=title.trim(),note=note.trim(),kind=kind,date=date,lastDate=finalLast,
                        week=date?.let(::mondayOf) ?: original.week,start=if(timed && date!=null)start else "",end=if(timed && date!=null)end else "",
                        who=if(shared)who else viewer,visibility=if(shared)Visibility.SHARED else if(busy)Visibility.BUSY else Visibility.PRIVATE,editable=shared && editable))
                },modifier=Modifier.fillMaxWidth()){Text(if(original.invite==InviteState.NONE)"Сохранить" else "Сохранить пример")}
            }
        }
    }
}
