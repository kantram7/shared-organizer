package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable fun InvitationsScreen(store:DemoStore,onOpen:(Entry)->Unit,onCreate:()->Unit) {
    var incoming by rememberSaveable { mutableStateOf(true) }
    var archive by rememberSaveable { mutableStateOf(false) }
    val all=store.entries.filter { it.invite!=InviteState.NONE }
    fun archived(e:Entry)=e.resolvedDay?.let { ChronoUnit.DAYS.between(it,LocalDate.now())>=store.retentionDays } ?: false
    val direction=all.filter { it.incoming==incoming }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(if(archive)"Архив" else "Приглашения",if(archive)"Назад" else "+ Создать",{if(archive)archive=false else onCreate()})
        if(archive) {
            all.filter(::archived).forEach { InvitationCard(it,onOpen) }
            if(all.none(::archived))Text("Архив пока пуст")
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(incoming,{incoming=true},label={Text("Мне")});FilterChip(!incoming,{incoming=false},label={Text("От меня")})
            }
            direction.filter { it.invite==InviteState.PENDING }.forEach { InvitationCard(it,onOpen) }
            if(direction.none { it.invite==InviteState.PENDING })Text("Нет приглашений, ожидающих ответа",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical=16.dp))
            Text("История",style=MaterialTheme.typography.titleMedium)
            Text("${store.retentionDays} дней после ответа",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            direction.filter { it.invite!=InviteState.PENDING && !archived(it) }.forEach { InvitationCard(it,onOpen) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick={archive=true},modifier=Modifier.fillMaxWidth()){Text("Архив приглашений")}
        }
    }
}
@Composable private fun InvitationCard(entry:Entry,onOpen:(Entry)->Unit) {
    Surface(onClick={onOpen(entry)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(entry.title,style=MaterialTheme.typography.titleSmall)
            Text("${if(entry.incoming)"Пользователь 2 → Пользователь 1" else "Пользователь 1 → Пользователь 2"} · ${entry.date?.let(::dateLabel) ?: "Без дня"}",style=MaterialTheme.typography.labelSmall)
            Text(when(entry.invite){InviteState.ACCEPTED->"Принято";InviteState.DECLINED->"Не получилось";else->if(entry.incoming)"Ждёт твоего ответа" else "Ждёт ответа Пользователя 2"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable fun SettingsScreen(store:DemoStore) {
    var retention by rememberSaveable { mutableStateOf(store.retentionDays.toString()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ScreenHeading("Настройки")
        Text("Профиль",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField("Пользователь 1",{},Modifier.fillMaxWidth(),readOnly=true,label={Text("Отображаемое имя")})
        Text("Оформление",style=MaterialTheme.typography.titleMedium)
        listOf("system" to "Как на телефоне","light" to "Светлая","dark" to "Тёмная").forEach { (value,label)->
            Row(verticalAlignment=Alignment.CenterVertically){RadioButton(store.theme==value,{store.updateTheme(value)});Text(label)}
        }
        HorizontalDivider()
        Text("Вход и уведомления",style=MaterialTheme.typography.titleMedium)
        Text("Будут подключены на следующих этапах. В этой сборке нет блокировки PIN, отпечатка и системных напоминаний.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment=Alignment.CenterVertically){Switch(true,null,enabled=false);Spacer(Modifier.width(8.dp));Text("PIN · по умолчанию включён")}
        Row(verticalAlignment=Alignment.CenterVertically){Switch(true,null,enabled=false);Spacer(Modifier.width(8.dp));Text("Биометрия · включена")}
        HorizontalDivider()
        Text("Приглашения",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(retention,{retention=it.filter(Char::isDigit).take(3)},Modifier.fillMaxWidth(),label={Text("Дней до архива")},singleLine=true)
        Button(onClick={
            val number=retention.toIntOrNull()
            if(number==null || number !in 1..365)message="Укажи от 1 до 365 дней"
            else{store.updateRetention(number);message="Сохранено на этом телефоне"}
        }){Text("Сохранить")}
        if(message.isNotEmpty())Text(message,style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Kotiki team 0.1.0 · демо",style=MaterialTheme.typography.labelMedium)
        Text("Демонстрационные записи сохраняются локально. Облачного обмена и подключения календарей пока нет.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}
