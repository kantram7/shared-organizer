package team.kotiki.app

import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalTime

private fun Context.reminderActivity(): ComponentActivity? {
    var current=this
    while(current is ContextWrapper) { if(current is ComponentActivity)return current; current=current.baseContext }
    return current as? ComponentActivity
}

@Composable
fun ReminderSettings() {
    val context=LocalContext.current
    var exact by remember { mutableStateOf(LocalReminders.canScheduleExact(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val activity=context.reminderActivity()
        val observer=LifecycleEventObserver { _, event -> if(event==Lifecycle.Event.ON_RESUME) {
            exact=LocalReminders.canScheduleExact(context)
            LocalReminders.reschedulePersisted(context)
        } }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Напоминания",style=MaterialTheme.typography.titleMedium)
        Text(if(exact)"Точные напоминания разрешены." else "Без разрешения Android напоминания могут приходить с задержкой, особенно при экономии батареи.",style=MaterialTheme.typography.bodySmall)
        if(!exact && Build.VERSION.SDK_INT>=31)OutlinedButton(onClick={
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { error="Не удалось открыть доступ. Разреши будильники и напоминания в настройках Android." }
        },modifier=Modifier.fillMaxWidth()) { Text("Разрешить точные напоминания") }
        Text("По умолчанию: за 30 минут до события со временем, в 09:00 для дела на день, без напоминания для дел без дня. Настройка доступна при создании и редактировании записи; в просмотре показано выбранное время.",style=MaterialTheme.typography.bodySmall)
        error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
    }
}

/** Personal preference for an already saved entry. Does not modify shared event data. */
@Composable
fun EntryReminderSettings(entry: Entry, authUid: String, onChanged: () -> Unit = {}) {
    if(authUid.isBlank())return
    key(authUid,entry.id) {
        val context=LocalContext.current
        val pickerContext=rememberPickerContext()
        val preferences=remember { ReminderPreferences(context) }
        var preference by remember { mutableStateOf(preferences.preference(authUid,entry.id)) }
        var minutes by remember { mutableStateOf(preference.minutesBefore.toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        fun save(value: EntryReminderPreference) {
            try { preferences.setPreference(authUid,entry.id,value);preference=value;LocalReminders.reschedulePersisted(context);onChanged();error=null }
            catch(_:Exception) { error="Не удалось сохранить напоминание. Повтори попытку." }
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=preference.enabled && entry.date!=null,onCheckedChange={save(preference.copy(enabled=it))},enabled=entry.date!=null)
                Text("Моё напоминание",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            }
            if(entry.date==null)Text("Выбери день, чтобы настроить напоминание.",style=MaterialTheme.typography.bodySmall)
            else if(preference.enabled) {
                if(entry.start.isNotBlank()) {
                    OutlinedTextField(minutes,{text->
                        if(text.length<=5 && text.all(Char::isDigit)) {
                            minutes=text
                            val value=text.toIntOrNull()
                            if(value!=null && value in 0..10080)save(preference.copy(minutesBefore=value))
                            else error="Укажи от 0 до 10080 минут."
                        }
                    },label={Text("За сколько минут")},supportingText={Text("0 — в момент начала")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                } else OutlinedButton(onClick={
                    val time=LocalTime.parse(preference.dayTime)
                    TimePickerDialog(pickerContext,{_,hour,minute->save(preference.copy(dayTime="%02d:%02d".format(java.util.Locale.ROOT,hour,minute)))},time.hour,time.minute,true).show()
                },modifier=Modifier.fillMaxWidth()) { Text("В день события · ${preference.dayTime}") }
                if(entry.invite==InviteState.PENDING)Text("Напоминание включится после принятия приглашения.",style=MaterialTheme.typography.bodySmall)
                if(entry.done || entry.invite==InviteState.DECLINED)Text("Для выполненной или отменённой записи напоминание не запланировано.",style=MaterialTheme.typography.bodySmall)
                if(!LocalReminders.canScheduleExact(context))Text("Без доступа к точным напоминаниям Android может задержать уведомление. Доступ включается в настройках приложения.",style=MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        }
    }
}
