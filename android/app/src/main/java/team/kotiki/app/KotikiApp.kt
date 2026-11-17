package team.kotiki.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun KotikiApp(store:DemoStore,notificationTarget:GatheringNotificationTarget?=null,onNotificationHandled:()->Unit={},onSignOut:()->Unit={},chats:CloudChats?=null,onProfile:(Person)->Unit={}) {
    var page by rememberSaveable {mutableStateOf(Section.BOARD)}
    var editor by rememberSaveable {mutableStateOf<Entry?>(null)}
    var meeting by rememberSaveable {mutableStateOf<Entry?>(null)}
    var detail by rememberSaveable {mutableStateOf<Entry?>(null)}
    var proposalEditor by rememberSaveable {mutableStateOf<Entry?>(null)}
    var planChat by rememberSaveable {mutableStateOf<ChatTarget?>(null)}
    var history by remember {mutableStateOf<List<String>?>(null)}
    var help by rememberSaveable {mutableStateOf<String?>(null)}
    val viewedEntry=detail ?: editor?.takeIf{candidate->store.entries.any{it.id==candidate.id}}
    NotificationReadEffect(store.cloudSession?.authUid,viewedEntry?.let{NotificationReadState.entry(it.id)})
    val pages=rememberSaveableStateHolder()
    val colors=MaterialTheme.colorScheme
    val adaptive=LocalConfiguration.current.fontScale>=1.3f
    val navigationWidth=62.dp
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(notificationTarget) {
        notificationTarget?.let {target->
            if(target.authUid.isNotBlank() && target.authUid!=store.cloudSession?.authUid){onNotificationHandled();return@let}
            if(target.chatId.isNotBlank()) {
                if(editor!=null || meeting!=null){onNotificationHandled();scope.launch{snackbar.showSnackbar("Закрой редактор, затем открой чат")};return@let}
                page=when { target.chatId=="general" -> Section.CHAT; target.collectionId.isNotBlank() -> Section.PLANS; else -> Section.BOARD }
                val chatTitle=if(target.collectionId.isNotBlank())store.plans.find{it.id==target.collectionId}?.items?.find{it.id==target.itemId}?.title
                    else store.entries.find{it.id==target.itemId || "entry_"+it.id==target.chatId}?.title
                planChat=if(target.chatId=="general")null else ChatTarget(target.chatId,chatTitle ?: "Обсуждение",target.collectionId,target.itemId)
                onNotificationHandled();return@let
            }
            val entry=store.entries.find{it.id==target.id && it.visibleTo(store.viewer)}
            val message=when {
                target.profile.isNotBlank() && target.profile!=store.viewer.name -> "Приглашение относится к другому локальному профилю"
                entry==null -> "Приглашение уже удалено или недоступно"
                editor!=null || meeting!=null -> "Закрой редактор, чтобы открыть приглашение из раздела «Зовут»"
                else -> null
            }
            if(message==null){page=if(entry?.invite!=InviteState.NONE)Section.INVITES else Section.BOARD;detail=entry}
            onNotificationHandled()
            if(message!=null)scope.launch{snackbar.showSnackbar(message)}
        }
    }
    val undo=store.undoAction
    LaunchedEffect(undo) {
        if(undo!=null) {
            val result=snackbar.showSnackbar(undo.message,"Отменить",withDismissAction=true,duration=SnackbarDuration.Long)
            if(result==SnackbarResult.ActionPerformed)store.restore(undo) else store.dismissUndo(undo)
        }
    }
    fun open(entry:Entry){if(entry.canEdit(store.viewer))editor=entry else detail=entry}
    fun gatherCats() {
        meeting=newGathering(store.viewer)
        scope.launch{drawer.close()}
    }
    fun navigate(section:Section){planChat=null;page=section;scope.launch{drawer.close()}}
    BackHandler(enabled=drawer.isOpen){scope.launch{drawer.close()}}
    BackHandler(enabled=page!=Section.BOARD && editor==null && meeting==null && detail==null && help==null && !drawer.isOpen){page=Section.BOARD}
    BackHandler(enabled=planChat!=null){planChat=null}
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=adaptive,drawerContent={
        if(adaptive)ModalDrawerSheet(Modifier.widthIn(max=320.dp).fillMaxHeight()) {
            Text("Kotiki team",Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge)
            AppNavigation(page,true,::navigate,::gatherCats)
        }
    }) {
        Surface(Modifier.fillMaxSize(),color=colors.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().background(colors.surface).heightIn(min=52.dp).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(adaptive)IconButton({scope.launch{drawer.open()}},Modifier.semantics{contentDescription="Открыть меню"}){KotikiIcon(Glyph.MENU)}
                        Box(Modifier.width(navigationWidth).height(44.dp),contentAlignment=Alignment.Center) {
                            BrandMark(Modifier.requiredSize(62.dp))
                        }
                        Text("Kotiki team · ${personLabel(store.viewer)}",Modifier.weight(1f).padding(start=13.dp,end=12.dp),style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
                        if(store.cloudSession!=null)SyncStatus(store)
                    }
                    HorizontalDivider(color=colors.outlineVariant)
                    Row(Modifier.weight(1f)) {
                        if(!adaptive){Box(Modifier.width(navigationWidth).fillMaxHeight().background(colors.surface)){AppNavigation(page,false,::navigate,::gatherCats)};VerticalDivider(color=colors.outlineVariant)}
                        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=12.dp)) {
                            if(store.storageWarning)Text("Не удалось прочитать сохранённое демо",color=colors.error,style=MaterialTheme.typography.labelSmall)
                            pages.SaveableStateProvider(page.name) {
                                if(planChat!=null)ChatScreen(planChat!!,chats,onBack={planChat=null})
                                else when(page) {
                                    Section.BOARD->BoardScreen(store,::open,{editor=it},{help=it})
                                    Section.PLANS->CollectionsScreen(store,true,onPlanChat={store.preparePlanChat();planChat=it}){help=it}
                                    Section.CALENDAR->CalendarScreen(store,::open,{editor=it},{help=it})
                                    Section.LISTS->CollectionsScreen(store,false){help=it}
                                    Section.INVITES->InvitationsScreen(store,{detail=it},{val today=LocalDate.now();editor=Entry(newId(),"",today,mondayOf(today),kind=Kind.EVENT,owner=store.viewer,invite=InviteState.PENDING)})
                                    Section.CHAT->ChatScreen(ChatTarget("general","Общий чат"),chats)
                                    Section.SETTINGS->SettingsScreen(store,onProfile,onSignOut)
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
            }
        }
    }
    proposalEditor?.let {original -> ProposalEditor(original,onDismiss={proposalEditor=null},onPropose={store.proposeInvitation(original,it);proposalEditor=null}) }
    meeting?.let {entry->EntryEditor(entry,onDismiss={meeting=null},onSave={store.upsert(it);meeting=null})}
    editor?.let {entry->EntryEditor(entry,onDismiss={editor=null},onSave={store.upsert(it);editor=null},
        isNew=store.entries.none{it.id==entry.id},
        onDelete=if(store.entries.any{it.id==entry.id} && store.canDelete(entry)){{store.deleteEntry(entry);editor=null}} else null,
        onHistory=if(store.cloudSession!=null && store.entries.any{it.id==entry.id}){{history=listOf("Загружаем историю…");store.cloudHistory?.invoke("entry_${entry.id}"){history=it}}}else null,
        calendarActions=if(store.entries.any{it.id==entry.id} && store.cloudSession!=null) {{
            CalendarExportActions(entry,store.cloudSession.authUid)
            if(entry.who==store.viewer || entry.who==Person.BOTH)EntryReminderSettings(entry,store.cloudSession.authUid)
            if(entry.visibility==Visibility.SHARED)TextButton({editor=null;planChat=ChatTarget("entry_${entry.id}",entry.title,itemId=entry.id)}){Text("Обсудить в чате")}
        }} else null)}
    detail?.let {opened->
        val entry=store.entries.find{it.id==opened.id}?.redactFor(store.viewer) ?: opened
        AlertDialog(onDismissRequest={detail=null},title={Text(entry.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(entry.invite!=InviteState.NONE) {
            Text("Отправитель: ${personLabel(entry.owner)}")
            Text("Получатель: ${personLabel(entry.owner.other())}")
        }
        if(entry.isGathering) {
            Text("Общий сбор · событие-приглашение",color=gatheringAccent())
            Text("Участники — мы оба")
        }
        Text(entry.date?.let(::fullDayLabel) ?: "На неделе, без дня")
        if(entry.start.isNotEmpty()){Text("${entry.start}–${entry.end}")}
        if(entry.note.isNotBlank()){Text(entry.note)}
        entry.proposal?.let { p ->
            HorizontalDivider()
            Text("Предложение: ${personLabel(p.proposedBy)}",style=MaterialTheme.typography.titleSmall)
            Text("${fullDayLabel(p.date)} · ${p.start}–${p.end}")
            if(p.title!=entry.title)Text(p.title)
            if(p.note.isNotBlank())Text(p.note)
            Text("До подтверждения действует прежнее время.",style=MaterialTheme.typography.bodySmall)
        }
        InvitationAcceptButton(entry,{store.acceptInvitation(it);detail=null})
        if(entry.canAccept(store.viewer))TextButton({store.respondInvitation(entry,false);detail=null}){
            Text(if(entry.proposal!=null)"Отклонить изменения" else "Отказаться")
        }
        if(entry.invite in listOf(InviteState.PENDING,InviteState.ACCEPTED) && entry.visibility==Visibility.SHARED)
            TextButton({detail=null;proposalEditor=entry}){Text("Предложить изменения")}

        store.cloudSession?.let {
            if(entry.visibility!=Visibility.BUSY || entry.owner==store.viewer)CalendarExportActions(entry,it.authUid)
            if((entry.who==store.viewer || entry.who==Person.BOTH) && (entry.visibility!=Visibility.BUSY || entry.owner==store.viewer))EntryReminderSettings(entry,it.authUid)
        }
        if(store.cloudSession!=null && entry.visibility==Visibility.SHARED)TextButton({detail=null;planChat=ChatTarget("entry_${entry.id}",entry.title,itemId=entry.id)}){Text("Обсудить в чате")}

        if(entry.invite!=InviteState.NONE && store.cloudSession==null)Text("Пример приглашения · обмен пока недоступен",color=colors.onSurfaceVariant)
        if(store.cloudSession!=null)TextButton({
            history=listOf("Загружаем историю…")
            store.cloudHistory?.invoke("entry_${entry.id}"){history=it}
        }){Text("История изменений")}
        if(entry.invite==InviteState.ACCEPTED)Text(if(entry.proposal==null)"Приглашение принято" else "Принято прежнее время · изменения ожидают ответа",color=colors.primary)
        if(entry.isGathering && entry.invite==InviteState.PENDING && entry.gatheringError()==null)GatheringNotificationPreview(entry)
    }},confirmButton={TextButton({detail=null}){Text("Понятно")}},dismissButton={
        if(store.canDelete(entry))TextButton({store.deleteEntry(entry);detail=null}){Text("Удалить",color=colors.error)}
    })}
    history?.let {rows->AlertDialog(onDismissRequest={history=null},title={Text("История изменений")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(rows.isEmpty())Text("Пока нет сохранённых версий") else rows.forEach{Text(it)}
        }},confirmButton={TextButton({history=null}){Text("Закрыть")}})}
    help?.let {text->AlertDialog(onDismissRequest={help=null},title={Text("Видимость")},text={Text(text)},confirmButton={TextButton({help=null}){Text("Понятно")}})}
}

@Composable private fun AppNavigation(page:Section,expanded:Boolean,onSelect:(Section)->Unit,onGather:()->Unit) {
    Column(Modifier.fillMaxSize().padding(vertical=8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section.entries.filter{it!=Section.SETTINGS}.forEach{NavigationItem(it,page==it,expanded){onSelect(it)}}
            GatherCatsButton(expanded,onGather)
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        NavigationItem(Section.SETTINGS,page==Section.SETTINGS,expanded){onSelect(Section.SETTINGS)}
    }
}
@Composable private fun GatherCatsButton(expanded:Boolean,onClick:()->Unit) {
    val label="Общий сбор"
    val modifier=Modifier.padding(horizontal=3.dp,vertical=3.dp).fillMaxWidth()
        .clip(RoundedCornerShape(12.dp)).clickable(onClickLabel=label,onClick=onClick).padding(vertical=8.dp)
    if(expanded)Row(modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        KotikiIcon(Glyph.MEETING,Modifier.size(24.dp))
        Text(label,Modifier.weight(1f))
    } else Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        KotikiIcon(Glyph.MEETING,Modifier.size(24.dp))
        Text(label,fontSize=9.sp,lineHeight=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun NavigationItem(section:Section,selected:Boolean,expanded:Boolean,onClick:()->Unit) {
    val glyph=when(section){Section.BOARD->Glyph.BOARD;Section.PLANS->Glyph.PLANS;Section.CALENDAR->Glyph.CALENDAR;Section.LISTS->Glyph.LIST;Section.INVITES->Glyph.MAIL;Section.CHAT->Glyph.CHAT;Section.SETTINGS->Glyph.SETTINGS}
    val color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val modifier=Modifier.padding(horizontal=3.dp,vertical=3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick=onClick).padding(vertical=11.dp)
    if(expanded)Row(modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        KotikiIcon(glyph);Text(section.label)
    } else Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        KotikiIcon(glyph);Text(section.label,fontSize=9.sp,maxLines=1,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun ScreenHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(action) }
    }
}
@Composable fun ParticipantFilter(selected:OwnerFilter,onSelect:(OwnerFilter)->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(3.dp)) {
        OwnerFilter.entries.forEach {filter->
            Box(Modifier.weight(if(filter==OwnerFilter.USER_2)1.6f else 1f).heightIn(min=38.dp).clip(RoundedCornerShape(12.dp))
                .background(if(selected==filter)MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(selected==filter,role=Role.Tab,onClick={onSelect(filter)}).padding(horizontal=6.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                Text(if(filter==OwnerFilter.USER_2)personLabel(LocalDemoIdentity.current.viewer.other()) else filter.label,
                    style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
@Composable fun AccessIcon(entry: Entry, onHelp: (String) -> Unit) {
    val who = if (entry.who == Person.BOTH) "${personLabel(Person.USER_1)} и ${personLabel(Person.USER_2)}" else personLabel(entry.who)
    val info = when(entry.visibility) {
        Visibility.PRIVATE -> "$who. Видно только владельцу. Другой участник не видит запись."
        Visibility.BUSY -> "$who. Подробности видны только владельцу. Другому видна занятость."
        Visibility.SHARED -> "$who. Видно вам обоим."
    }
    val glyph = when(entry.visibility) {
        Visibility.PRIVATE -> Glyph.PRIVATE_LOCK
        Visibility.BUSY -> Glyph.EYE_OFF
        Visibility.SHARED -> when(entry.who){Person.BOTH->Glyph.PAIR;Person.USER_2->Glyph.CAT_GIRL;Person.USER_1->Glyph.CAT}
    }
    IconButton(onClick = { onHelp(info) }, modifier = Modifier.size(width = if (glyph == Glyph.PAIR) 56.dp else 44.dp, height = 44.dp).semantics { contentDescription = info }) {
        KotikiIcon(glyph)
    }
}
@Composable fun RecordCard(entry: Entry, onOpen: (Entry) -> Unit, onDone: (Entry) -> Unit, onHelp: (String) -> Unit, onAccept:(Entry)->Unit) {
    val viewer = LocalDemoIdentity.current.viewer
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (entry.isDayTask) 10.dp else 15.dp),
        border = if(entry.isGathering)androidx.compose.foundation.BorderStroke(1.dp,gatheringAccent().copy(alpha=.55f)) else null,
        color = if(entry.isGathering)gatheringTint() else participantTint(entry.who, entry.isDayTask)) {
        Column {
        Column(Modifier.alpha(if(entry.isTentative).58f else if(entry.done).55f else 1f)) {
        if (entry.isDayTask) {
            Row(Modifier.padding(start = 2.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.canEdit(viewer)) Checkbox(checked = entry.done, onCheckedChange = { onDone(entry) }, modifier = Modifier.size(42.dp))
                Text(entry.title, Modifier.weight(1f).clickable { onOpen(entry) }.padding(vertical = 9.dp), fontSize = 13.sp,
                    textDecoration = if(entry.done) TextDecoration.LineThrough else null)
                AccessIcon(entry, onHelp)
            }
        } else {
            Column(Modifier.padding(10.dp)) {
                if(entry.isGathering)GatheringLabel()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when { entry.isMultiDay -> "${dateLabel(entry.date!!)} — ${dateLabel(entry.lastDate!!)}"; entry.start.isNotBlank() -> "${entry.start}–${entry.end}"; else -> "Весь день" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    AccessIcon(entry, onHelp)
                }
                Text(entry.title, Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(vertical = 5.dp), fontWeight = FontWeight.Medium)
                if(entry.isTentative || entry.proposal!=null) Text(if(entry.canAccept(viewer)) "Ждёт твоего ответа" else "Ждёт ответа: ${personLabel(viewer.other())}", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                if(entry.proposal!=null)Text("Предложено другое время",style=MaterialTheme.typography.labelSmall,color=colors.primary)
                if(entry.kind == Kind.TASK && entry.canEdit(viewer)) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(entry.done, { onDone(entry) }); Text("Выполнено", style=MaterialTheme.typography.labelSmall) }
            }
        }
        }
        InvitationAcceptButton(entry,onAccept,Modifier.padding(horizontal=10.dp))
        if(entry.invite==InviteState.ACCEPTED)Text("Принято",Modifier.padding(start=10.dp,bottom=8.dp),style=MaterialTheme.typography.labelSmall,color=colors.primary)
        }
    }
}

@Composable fun InvitationAcceptButton(entry:Entry,onAccept:(Entry)->Unit,modifier:Modifier=Modifier) {
    if(entry.canAccept(LocalDemoIdentity.current.viewer)) {
        TextButton({onAccept(entry)},modifier.semantics{contentDescription="Принять приглашение «${entry.title}»"}){Text(if(entry.proposal!=null)"Подтвердить изменения" else "Принять")}
    }
}
