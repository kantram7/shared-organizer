package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
    val direction=all.filter { (if(it.proposal!=null)it.canAccept(store.viewer) else it.incoming)==incoming }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(if(archive)"Архив" else "Приглашения",if(archive)"Назад" else "+ Создать",{if(archive)archive=false else onCreate()})
        Text(if(store.cloudSession!=null)"Приглашения вашего пространства" else "Пример приглашений · обмен пока недоступен",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(archive) {
            all.filter(::archived).forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            if(all.none(::archived))Text("Архив пока пуст")
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(incoming,{incoming=true},label={Text("Мне")});FilterChip(!incoming,{incoming=false},label={Text("От меня")})
            }
            direction.filter { it.invite==InviteState.PENDING || it.proposal!=null }.forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            if(direction.none { it.invite==InviteState.PENDING || it.proposal!=null })CatEmpty("Новых приглашений пока нет",if(store.cloudSession==null)"Создать пример" else "Создать приглашение",onCreate)
            HorizontalDivider(Modifier.padding(vertical=16.dp))
            Text("История",style=MaterialTheme.typography.titleMedium)
            Text("${store.retentionDays} дней после ответа",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            direction.filter { it.invite!=InviteState.PENDING && it.proposal==null && !archived(it) }.forEach { InvitationCard(it,onOpen,store::acceptInvitation) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick={archive=true},modifier=Modifier.fillMaxWidth()){Text("Архив приглашений")}
        }
    }
}
@Composable private fun InvitationCard(entry:Entry,onOpen:(Entry)->Unit,onAccept:(Entry)->Unit) {
    val actions=rememberItemMenu()
    var visible by remember(entry.id){mutableStateOf(false)}
    InvitationReadEffect(entry,visible)
    Surface(modifier=Modifier.fillMaxWidth().padding(vertical=6.dp).onGloballyPositioned{visible=it.boundsInWindow().height>=it.size.height*.5f && it.size.height>0}.winterIcicles(entry.id).clickable{onOpen(entry)},shape=RoundedCornerShape(14.dp),color=if(entry.isGathering)gatheringTint() else winterSurface(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(entry.isGathering)GatheringLabel()
                    LinkText(entry.title,style=MaterialTheme.typography.titleSmall)
                }
                RecordActions(entry,actions,onOpen)
            }
            Text("${if(entry.incoming)"${personLabel(LocalDemoIdentity.current.viewer.other())} → ${personLabel(LocalDemoIdentity.current.viewer)}" else "${personLabel(LocalDemoIdentity.current.viewer)} → ${personLabel(LocalDemoIdentity.current.viewer.other())}"} · ${entry.date?.let(::dateLabel) ?: "Без дня"}",style=MaterialTheme.typography.labelSmall)
            if(entry.proposal!=null)Text("Изменения ожидают подтверждения",color=MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(when {entry.proposal!=null || entry.invite==InviteState.PENDING->if(entry.canAccept(LocalDemoIdentity.current.viewer))"Ждёт твоего ответа" else "Ждёт ответа: ${personLabel(LocalDemoIdentity.current.viewer.other())}";entry.invite==InviteState.ACCEPTED->"Принято";entry.invite==InviteState.DECLINED->"Не получилось";else->""},modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                InvitationAcceptButton(entry,onAccept)
            }
        }
    }
}
@Composable fun SettingsScreen(store:DemoStore,onProfile:(Person)->Unit,onSignOut:()->Unit={}) {
    var retention by rememberSaveable { mutableStateOf(store.retentionDays.toString()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ScreenHeading("Настройки")
        Text("Профиль",style=MaterialTheme.typography.titleMedium)
        var name by rememberSaveable(store.identity.names){mutableStateOf(store.identity.names[store.viewer] ?: store.viewer.label)}
        ProfileNameRow(name,{name=it.take(40)},store.profilePending,
            name.isNotBlank()&&!store.profilePending&&name.trim()!=personLabel(store.viewer)){store.updateName(name)}
        if(store.profileError.isNotBlank())Text(store.profileError,color=MaterialTheme.colorScheme.error)
        if(store.cloudSession!=null) {
            Text(store.cloudStatus,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onSignOut,enabled=!store.cloudPending && !store.chatPending && !store.notes.pending && !store.profilePending){Text("Выйти из аккаунта")}
            if(store.cloudPending || store.chatPending || store.notes.pending || store.profilePending)Text("Выход станет доступен после отправки изменений.",style=MaterialTheme.typography.bodySmall)
        }
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
        val winterPreview=BuildConfig.DEBUG && BuildConfig.WINTER_PREVIEW
        if(!winterDesignEnabled() || winterPreview) {
            val themes=listOf("system" to "Как на телефоне","light" to "Светлая","dark" to "Тёмная") +
                if(winterPreview)listOf("winter" to "Зима вместе")else emptyList()
            Column(Modifier.selectableGroup()) {
                themes.forEach { (value,label)->
                    Row(Modifier.fillMaxWidth().selectable(selected=store.theme==value,role=Role.RadioButton,onClick={store.updateTheme(value)}),verticalAlignment=Alignment.CenterVertically){RadioButton(store.theme==value,null);Text(label)}
                }
            }
        } else Text("Зима вместе",style=MaterialTheme.typography.bodyMedium)
        LocalWinterDesign.current?.takeIf{it.enabled}?.let { winter -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Падающий снег",Modifier.weight(1f));Switch(winter.snow,{winter.snow=it})} }
        HorizontalDivider()
        Text("Вход и уведомления",style=MaterialTheme.typography.titleMedium)
        NotificationPermissionSettings()
        if(store.cloudSession!=null)Text(AndroidPush.registrationStatus,style=MaterialTheme.typography.bodySmall)
        ReminderSettings()
        Text("Блокировка защищает открытие приложения на этом телефоне.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        store.cloudSession?.let { AppLockSettings(it.authUid)
            HorizontalDivider()
            CalendarConnectionSettings(it.authUid)
        }
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
        Text("Kotiki team ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelMedium)
        Text("Издатель: Kantram",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(store.cloudSession!=null)"Записи синхронизируются с Firebase. Копии во внешних календарях обновляются по кнопке в записи." else "Демонстрационные записи сохраняются локально. Облачного обмена и подключения календарей пока нет.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}
