package team.kotiki.app

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.Locale

@Stable internal class EntryReminderDraft(initial:EntryReminderPreference) {
    var enabled by mutableStateOf(initial.enabled)
    var minutes by mutableStateOf(initial.minutesBefore.toString())
    var dayTime by mutableStateOf(initial.dayTime)
    var expanded by mutableStateOf(false)
    var custom by mutableStateOf(initial.minutesBefore !in listOf(0,5,15,30,60,120,1440))
    var error by mutableStateOf<String?>(null)
    fun preference():EntryReminderPreference? {
        val number=minutes.toIntOrNull()?.takeIf{it in 0..10080} ?: return null
        return EntryReminderPreference(enabled,number,dayTime)
    }
    fun save(context:Context,account:String?,entry:Entry):Boolean {
        if(account==null)return true
        // An unfinished custom field does not block saving when reminders are off or date-only.
        val value=preference() ?: if(!enabled || entry.start.isBlank() || entry.date==null) EntryReminderPreference(enabled,30,dayTime) else null
        if(value==null){error="Укажи от 0 до 10080 минут.";expanded=true;return false}
        return runCatching{ReminderPreferences(context).setPreference(account,entry.id,value)}.fold(
            onSuccess={error=null;true},onFailure={error="Не удалось сохранить напоминание. Повтори попытку.";expanded=true;false})
    }
    companion object {
        val Saver=listSaver<EntryReminderDraft,Any>(save={listOf(it.enabled,it.minutes,it.dayTime,it.expanded,it.custom)},restore={v->
            EntryReminderDraft(EntryReminderPreference(v[0] as Boolean,30,v[2] as String)).apply{
                minutes=v[1] as String;expanded=v[3] as Boolean;custom=v[4] as Boolean
            }
        })
    }
}

@Composable internal fun rememberEntryReminderDraft(account:String?,id:String):EntryReminderDraft {
    val context=LocalContext.current
    return rememberSaveable(account,id,saver=EntryReminderDraft.Saver){
        EntryReminderDraft(account?.let{ReminderPreferences(context).preference(it,id)} ?: EntryReminderPreference())
    }
}

internal fun reminderDescription(entry:Entry,preference:EntryReminderPreference)=when {
    entry.date==null -> "Без даты · выключено"
    !preference.enabled -> "Выключено"
    entry.start.isBlank() -> "В день события · ${preference.dayTime}"
    preference.minutesBefore==0 -> "В момент начала"
    preference.minutesBefore==60 -> "За 1 час"
    preference.minutesBefore==120 -> "За 2 часа"
    preference.minutesBefore==1440 -> "За 1 день"
    else -> "За ${preference.minutesBefore} мин"
}

@Composable internal fun ReminderHeader(label:String,expanded:Boolean?=null,onClick:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick).semantics{contentDescription="Настроить моё напоминание"}else Modifier)
        .padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_reminder_bell),null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("Моё напоминание",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
        }
        if(expanded!=null)KotikiIcon(Glyph.RIGHT,Modifier.size(20.dp).rotate(if(expanded)-90f else 90f))
    }
}

@Composable internal fun EntryReminderEditor(entry:Entry,draft:EntryReminderDraft) {
    val context=LocalContext.current
    Surface(shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=layeredSurface(.65f)) {
        Column {
            ReminderHeader(reminderDescription(entry,draft.preference()?:EntryReminderPreference(draft.enabled,30,draft.dayTime)),draft.expanded){draft.expanded=!draft.expanded}
            if(draft.expanded)Column(Modifier.padding(start=14.dp,end=14.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(entry.date==null)Text("Выбери день, чтобы настроить напоминание.",style=MaterialTheme.typography.bodySmall)
                else {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        if(entry.start.isNotBlank()) {
                            listOf(0,5,15,30,60,120,1440).forEach{minutes->
                                FilterChip(draft.enabled && !draft.custom && draft.minutes==minutes.toString(),{
                                    draft.enabled=true;draft.custom=false;draft.minutes=minutes.toString();draft.error=null
                                },label={Text(reminderDescription(entry,EntryReminderPreference(minutesBefore=minutes)))})
                            }
                            FilterChip(draft.enabled&&draft.custom,{draft.enabled=true;draft.custom=true},label={Text("Другое время")})
                        } else FilterChip(draft.enabled,{draft.enabled=true},label={Text("В день события")})
                        FilterChip(!draft.enabled,{draft.enabled=false;draft.error=null},label={Text("Выключить")})
                    }
                    if(draft.enabled && entry.start.isNotBlank() && draft.custom)OutlinedTextField(
                        draft.minutes,{if(it.length<=5 && it.all(Char::isDigit)){draft.minutes=it;draft.error=if(draft.preference()==null)"Укажи от 0 до 10080 минут." else null}},
                        Modifier.fillMaxWidth(),label={Text("За сколько минут")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=draft.error!=null)
                    if(draft.enabled && entry.start.isBlank())OutlinedButton({
                        val time=LocalTime.parse(draft.dayTime)
                        TimePickerDialog(context,{_,hour,minute->draft.dayTime=String.format(Locale.ROOT,"%02d:%02d",hour,minute)},time.hour,time.minute,true).show()
                    }){Text("В ${draft.dayTime}")}
                    Text("Только для меня. У второго участника своё напоминание.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(entry.invite==InviteState.PENDING)Text("Напоминание включится после принятия приглашения.",style=MaterialTheme.typography.bodySmall)
                }
                draft.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
}
