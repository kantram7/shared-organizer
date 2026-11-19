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

/** Returns a candidate only. The caller commits it with the proposal ID originally shown to the user. */
@Composable fun ProposalEditor(original:Entry,onDismiss:()->Unit,onPropose:(Entry)->Unit){
    val context=LocalContext.current
    val initial=remember(original.id,original.proposal?.id){InviteWorkflow.candidate(original)}
    var title by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.title)}
    var note by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.note)}
    var date by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.date ?: LocalDate.now())}
    var last by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.lastDate ?: initial.date ?: LocalDate.now())}
    var start by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.start.ifBlank{"19:00"})}
    var end by rememberSaveable(original.id,original.proposal?.id){mutableStateOf(initial.end.ifBlank{"20:00"})}
    var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(title,note,date,last,start,end){error=null}
    fun pickDate(value:LocalDate,onPick:(LocalDate)->Unit){
        DatePickerDialog(context,{_,year,month,day->onPick(LocalDate.of(year,month+1,day))},value.year,value.monthValue-1,value.dayOfMonth).show()
    }
    fun pickTime(value:String,onPick:(String)->Unit){
        val time=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.of(19,0))
        TimePickerDialog(context,{_,hour,minute->onPick("%02d:%02d".format(java.util.Locale.ROOT,hour,minute))},time.hour,time.minute,true).show()
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        EditorSurface(Modifier.fillMaxWidth().fillMaxHeight(.94f).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),shape=MaterialTheme.shapes.extraLarge){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("Предложить изменения",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    IconButton(onClick=onDismiss,modifier=Modifier.semantics{contentDescription="Закрыть предложение"}){KotikiIcon(Glyph.CLOSE)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(original.invite==InviteState.ACCEPTED){
                        Text("До подтверждения остаётся прежняя встреча:",style=MaterialTheme.typography.labelLarge)
                        Text(listOfNotNull(original.date?.let(::fullDayLabel),original.start.takeIf{it.isNotEmpty()}?.let{"$it–${original.end}"}).joinToString(" · "))
                    }else Text("Изменения вступят в силу после подтверждения второго участника.")
                    OutlinedTextField(title,{title=it.take(100)},Modifier.fillMaxWidth(),singleLine=true,label={Text("Название")})
                    OutlinedTextField(note,{note=it.take(300)},Modifier.fillMaxWidth(),minLines=2,label={Text("Комментарий к предложению")})
                    EditorLinks(title+"\n"+note)
                    OutlinedButton(onClick={pickDate(date){date=it;if(original.isGathering || last<it)last=it}},modifier=Modifier.fillMaxWidth().semantics{contentDescription="Дата предложения"}){Text(fullDayLabel(date))}
                    if(!original.isGathering)OutlinedButton(onClick={pickDate(last){last=it}},modifier=Modifier.fillMaxWidth().semantics{contentDescription="Окончание предложения"}){Text("До ${dateLabel(last)} включительно")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={pickTime(start){start=it}},modifier=Modifier.weight(1f).semantics{contentDescription="Время начала предложения"}){Text("Начало $start")}
                        OutlinedButton(onClick={pickTime(end){end=it}},modifier=Modifier.weight(1f).semantics{contentDescription="Время окончания предложения"}){Text("Конец $end")}
                    }
                    Text("Участники остаются прежними. Получатель сможет подтвердить, отклонить или предложить свой вариант.",style=MaterialTheme.typography.bodySmall)
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                }
                HorizontalDivider()
                Button(onClick={
                    val candidate=original.copy(title=title.trim(),note=note.trim(),date=date,lastDate=if(original.isGathering)date else last,week=mondayOf(date),start=start,end=end)
                    error=InviteWorkflow.proposalError(original,candidate)
                    if(error==null)onPropose(candidate)
                },modifier=Modifier.fillMaxWidth()){Text("Отправить предложение")}
            }
        }
    }
}
