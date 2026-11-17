package team.kotiki.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** A gathering is always a timed, shared invitation for both participants. */
@Composable fun GatheringEditor(original:Entry,onDismiss:()->Unit,onSave:(Entry)->Unit,calendarActions:(@Composable ()->Unit)?=null) {
    val context=LocalContext.current
    var title by rememberSaveable(original.id){mutableStateOf(original.title)}
    var note by rememberSaveable(original.id){mutableStateOf(original.note)}
    var date by rememberSaveable(original.id){mutableStateOf(original.date?.toString())}
    var start by rememberSaveable(original.id){mutableStateOf(original.start)}
    var end by rememberSaveable(original.id){mutableStateOf(original.end)}
    var error by rememberSaveable(original.id){mutableStateOf<String?>(null)}
    LaunchedEffect(title,date,start,end){error=null}
    fun pickTime(value:String,fallback:String,onPick:(String)->Unit) {
        val time=LocalTime.parse(value.ifEmpty{fallback})
        TimePickerDialog(context,{_,h,m->onPick("%02d:%02d".format(java.util.Locale.ROOT,h,m))},time.hour,time.minute,true).show()
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
            shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Собрать собрание котиков",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconButton(onDismiss,Modifier.semantics{contentDescription="Закрыть редактирование"}){KotikiIcon(Glyph.CLOSE)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title,{title=it.take(100)},Modifier.fillMaxWidth(),label={Text("Название")},singleLine=true)
                    OutlinedTextField(note,{note=it.take(300)},Modifier.fillMaxWidth(),label={Text("Комментарий")},minLines=2)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        KotikiIcon(Glyph.MEETING,Modifier.size(24.dp),gatheringAccent())
                        Text("Событие-приглашение",style=MaterialTheme.typography.labelLarge)
                    }
                    Text("Участники — мы оба",style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick={
                        val day=date?.let(LocalDate::parse) ?: LocalDate.now()
                        DatePickerDialog(context,{_,y,m,d->date=LocalDate.of(y,m+1,d).toString()},day.year,day.monthValue-1,day.dayOfMonth).show()
                    },modifier=Modifier.fillMaxWidth()) {Text(date?.let{fullDayLabel(LocalDate.parse(it))} ?: "Выбрать дату")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({pickTime(start,"19:00"){start=it}},Modifier.weight(1f)){Text(if(start.isEmpty())"Время начала" else "Начало $start")}
                        OutlinedButton({pickTime(end,start.ifEmpty{"20:00"}){end=it}},Modifier.weight(1f)){Text(if(end.isEmpty())"Время окончания" else "Конец $end")}
                    }
                    Text("Выбери дату, время начала и окончания. Приглашение будет ждать подтверждения второго участника.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(LocalCloudMode.current)"Приглашение появится у второго участника после синхронизации. Уведомление придёт, если они разрешены на его телефоне." else "В демоверсии сохраняется только на этом телефоне. Отправка второму участнику пока недоступна.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(calendarActions!=null) {
                        HorizontalDivider()
                        val calendarDirty=title.trim()!=original.title || note.trim()!=original.note || date!=original.date?.toString() || start!=original.start || end!=original.end
                        if(calendarDirty)Text("Сначала сохрани изменения, затем обнови копию в календаре.",style=MaterialTheme.typography.bodySmall)
                        else calendarActions()
                    }
                }
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                HorizontalDivider()
                Button(onClick={
                    val day=date?.let(LocalDate::parse)
                    val result=original.copy(title=title.trim(),note=note.trim(),date=day,lastDate=day,
                        week=day?.let(::mondayOf) ?: original.week,start=start,end=end,kind=Kind.EVENT,who=Person.BOTH,
                        visibility=Visibility.SHARED,isGathering=true,invite=InviteState.PENDING,done=false)
                    error=result.gatheringError()
                    if(error==null)onSave(result)
                },modifier=Modifier.fillMaxWidth()){Text("Сохранить приглашение")}
            }
        }
    }
}
