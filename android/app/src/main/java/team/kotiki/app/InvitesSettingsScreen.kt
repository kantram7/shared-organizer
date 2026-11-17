package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
    val all=store.entries.filter { it.invite!=InviteState.NONE && it.visibleTo(store.viewer) }.map { it.copy(incoming=it.owner!=store.viewer).redactFor(store.viewer) }
    fun archived(e:Entry)=e.resolvedDay?.let { ChronoUnit.DAYS.between(it,LocalDate.now())>=store.retentionDays } ?: false
    val direction=all.filter { it.incoming==incoming }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(if(archive)"Архив" else "Приглашения",if(archive)"Назад" else "+ Создать",{if(archive)archive=false else onCreate()})
        Text("Пример приглашений · обмен пока недоступен",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(archive) {
            all.filter(::archived).forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            if(all.none(::archived))Text("Архив пока пуст")
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(incoming,{incoming=true},label={Text("Мне")});FilterChip(!incoming,{incoming=false},label={Text("От меня")})
            }
            direction.filter { it.invite==InviteState.PENDING }.forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            if(direction.none { it.invite==InviteState.PENDING })CatEmpty("Новых приглашений пока нет","Создать пример",onCreate)
            HorizontalDivider(Modifier.padding(vertical=16.dp))
            Text("История",style=MaterialTheme.typography.titleMedium)
            Text("${store.retentionDays} дней после ответа",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            direction.filter { it.invite!=InviteState.PENDING && !archived(it) }.forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick={archive=true},modifier=Modifier.fillMaxWidth()){Text("Архив приглашений")}
        }
    }
}
@Composable private fun InvitationCard(entry:Entry,onOpen:(Entry)->Unit,onAccept:(Entry)->Unit) {
    Surface(onClick={onOpen(entry)},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp),shape=RoundedCornerShape(14.dp),color=if(entry.isGathering)gatheringTint() else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            InvitationAcceptButton(entry,onAccept)
            if(entry.isGathering)GatheringLabel()
            Text(entry.title,style=MaterialTheme.typography.titleSmall)
            Text("${if(entry.incoming)"${personLabel(LocalDemoIdentity.current.viewer.other())} → ${personLabel(LocalDemoIdentity.current.viewer)}" else "${personLabel(LocalDemoIdentity.current.viewer)} → ${personLabel(LocalDemoIdentity.current.viewer.other())}"} · ${entry.date?.let(::dateLabel) ?: "Без дня"}",style=MaterialTheme.typography.labelSmall)
            Text(when(entry.invite){InviteState.ACCEPTED->"Принято";InviteState.DECLINED->"Не получилось";else->if(entry.incoming)"Ждёт твоего ответа" else "Ждёт ответа: ${personLabel(LocalDemoIdentity.current.viewer.other())}"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable fun SettingsScreen(store:DemoStore,onProfile:(Person)->Unit) {
    var retention by rememberSaveable { mutableStateOf(store.retentionDays.toString()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ScreenHeading("Настройки")
        Text("Профиль",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(personLabel(store.viewer),{},Modifier.fillMaxWidth(),readOnly=true,label={Text("Отображаемое имя")})
        if (store.preset != null) {
            Text("Локальный тестовый профиль",style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(Person.USER_1,Person.USER_2).forEach { person ->
                    FilterChip(store.viewer==person,{onProfile(person)},label={Text(personLabel(person))})
                }
            }
            Text("У каждого профиля своё сохранённое демо. Это не вход в аккаунт; обмена данными пока нет.",style=MaterialTheme.typography.bodySmall)
        }
        Text("Оформление",style=MaterialTheme.typography.titleMedium)
        listOf("system" to "Как на телефоне","light" to "Светлая","dark" to "Тёмная").forEach { (value,label)->
            Row(Modifier.fillMaxWidth().selectable(selected=store.theme==value,role=Role.RadioButton,onClick={store.updateTheme(value)}),verticalAlignment=Alignment.CenterVertically){RadioButton(store.theme==value,null);Text(label)}
        }
        HorizontalDivider()
        Text("Вход и уведомления",style=MaterialTheme.typography.titleMedium)
        Text("Будут подключены на следующих этапах. В этой сборке нет блокировки PIN, отпечатка и автоматических напоминаний. Уведомление общего сбора можно проверить в его карточке.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("PIN · будет доступен позже",style=MaterialTheme.typography.bodyMedium)
        Text("Биометрия · будет доступна позже",style=MaterialTheme.typography.bodyMedium)
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
        Text("Kotiki team 0.1.5 · демо",style=MaterialTheme.typography.labelMedium)
        Text("Демонстрационные записи сохраняются локально. Облачного обмена и подключения календарей пока нет.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}
