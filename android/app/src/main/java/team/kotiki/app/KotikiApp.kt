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

@Composable fun KotikiApp(store:DemoStore,notificationTarget:GatheringNotificationTarget?=null,onNotificationHandled:()->Unit={},onSignOut:()->Unit={},chats:CloudChats?=null,localChatPreview:(@Composable ()->Unit)?=null,onProfile:(Person)->Unit={}) {
    var sharedDestination by remember {mutableStateOf<Pair<ShareDestination,SharedContent>?>(null)}
    var requestedNote by rememberSaveable{mutableStateOf<String?>(null)}
    var chatAnchor by rememberSaveable{mutableStateOf<String?>(null)}
    val welcomeVisible=LocalWelcomeVisible.current
    var moveWeek by remember{mutableStateOf<Entry?>(null)}
    var showHistory by remember {mutableStateOf(false)}
    var historyTarget by remember {mutableStateOf<GatheringNotificationTarget?>(null)}
    val effectiveTarget=historyTarget ?: notificationTarget
    var shareDismissed by remember {mutableStateOf<String?>(null)}
    var page by rememberSaveable {mutableStateOf(Section.BOARD)}
    var editor by rememberSaveable {mutableStateOf<Entry?>(null)}
    var meeting by rememberSaveable {mutableStateOf<Entry?>(null)}
    var detail by rememberSaveable {mutableStateOf<Entry?>(null)}
    var proposalEditor by rememberSaveable {mutableStateOf<Entry?>(null)}
    var planChat by rememberSaveable {mutableStateOf<ChatTarget?>(null)}
    var planEditing by rememberSaveable {mutableStateOf(false)}
    var history by remember {mutableStateOf<List<String>?>(null)}
    var help by rememberSaveable {mutableStateOf<String?>(null)}
    val reminderAccount=store.cloudSession?.authUid ?: "demo/${store.viewer.name}"
    val viewedEntry=detail ?: editor?.takeIf{candidate->store.entries.any{it.id==candidate.id}}
    NotificationReadEffect(store.cloudSession?.authUid,viewedEntry?.let{NotificationReadState.entry(it.id)})
    val invitationReads=remember(store){InvitationReads(store.appContext,localProfileKey(store.cloudSession,store.viewer),store.viewer)}
    val unread=remember(chats){chats?.let{ChatUnreadState(store.appContext,it.session.uid,localProfileKey(it.session,it.session.viewer))}}
    DisposableEffect(chats,unread,unread?.since){
        val stop=if(chats!=null && unread!=null)chats.observe(ChatTarget("general","Общий чат"),since=unread.since,onState=unread::receive)else null
        onDispose{stop?.invoke()}
    }
    val entryReads=remember(chats){chats?.let{EntryChatReads(store.appContext,it.session.uid,localProfileKey(it.session,it.session.viewer))}}
    ObserveEntryChats(chats,entryReads,store.entries,store.viewer)
    val entryNew=remember(store){EntryNewState(store.appContext,store.viewer,store.cloudSession)}
    DisposableEffect(entryNew){val stop=entryNew.observe();onDispose{stop()}}
    LaunchedEffect(page){entryNew.refresh()}
    LaunchedEffect(detail?.id){detail?.let{entryNew.seen(it,true)}}

    val pages=rememberSaveableStateHolder()
    val colors=MaterialTheme.colorScheme
    val adaptive=LocalConfiguration.current.fontScale>=1.3f
    val navigationWidth=62.dp
    val drawer=rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(adaptive){if(!adaptive && drawer.isOpen)drawer.close()}
    SideEffect{entryNew.active=!welcomeVisible && detail==null && editor==null && meeting==null && planChat==null && help==null && proposalEditor==null && history==null && moveWeek==null && !showHistory && sharedDestination==null && (SharedContentInbox.pending==null || SharedContentInbox.pending?.id==shareDismissed) && !drawer.isOpen}
    val scope=rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(effectiveTarget,store.entries.toList(),store.cloudReady) {
        effectiveTarget?.let {target->
            if(target.authUid.isNotBlank() && target.authUid!=store.cloudSession?.authUid){historyTarget=null;onNotificationHandled();return@let}
            val eventChatId=eventIdFromChat(target)
            if(eventChatId!=null) {
                val event=store.entries.find{it.id==eventChatId && it.visibility==Visibility.SHARED && it.visibleTo(store.viewer)}
                if(event==null && store.cloudSession!=null && !store.cloudReady)return@let
                if(editor!=null || meeting!=null){scope.launch{snackbar.showSnackbar("Закрой редактор, затем открой событие")}}
                else if(event!=null){page=Section.BOARD;planChat=null;detail=event}
                else scope.launch{snackbar.showSnackbar("Событие удалено или недоступно")}
                historyTarget=null;onNotificationHandled();return@let
            }
            if(target.chatId.isNotBlank()) {
                if(editor!=null || meeting!=null){historyTarget=null;onNotificationHandled();scope.launch{snackbar.showSnackbar("Закрой редактор, затем открой чат")};return@let}
                page=when { target.chatId=="general" -> Section.CHAT; target.collectionId.isNotBlank() -> Section.PLANS; else -> Section.BOARD }
                val chatTitle=if(target.collectionId.isNotBlank())store.plans.find{it.id==target.collectionId}?.items?.find{it.id==target.itemId}?.title
                    else store.entries.find{it.id==target.itemId || "entry_"+it.id==target.chatId}?.title
                planChat=if(target.chatId=="general")null else ChatTarget(target.chatId,chatTitle ?: "Обсуждение",target.collectionId,target.itemId)
                historyTarget=null;onNotificationHandled();return@let
            }
            val entry=store.entries.find{it.id==target.id && it.visibleTo(store.viewer)}
            val message=when {
                target.profile.isNotBlank() && target.profile!=store.viewer.name -> "Приглашение относится к другому локальному профилю"
                entry==null -> "Приглашение уже удалено или недоступно"
                editor!=null || meeting!=null -> "Закрой редактор, чтобы открыть приглашение из раздела «Зовут»"
                else -> null
            }
            if(message==null){page=if(entry?.invite!=InviteState.NONE)Section.INVITES else Section.BOARD;detail=entry}
            historyTarget=null;onNotificationHandled()
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
    fun open(entry:Entry){entryNew.seen(entry,true);detail=entry}
    fun gatherCats() {
        meeting=newGathering(store.viewer)
        scope.launch{drawer.close()}
    }
    fun navigate(section:Section){planChat=null;page=section;scope.launch{drawer.close()}}
    BackHandler(enabled=drawer.isOpen){scope.launch{drawer.close()}}
    BackHandler(enabled=page!=Section.BOARD && editor==null && meeting==null && detail==null && help==null && !drawer.isOpen){page=Section.BOARD}
    BackHandler(enabled=planChat!=null){planChat=null}
    CompositionLocalProvider(LocalMoveRecordWeek provides {moveWeek=it},LocalCreateNote provides {text->sharedDestination=ShareDestination.NOTE to SharedContent(newId(),text,"")},LocalOpenInternalLink provides {url->
        val uri=android.net.Uri.parse(url);val parts=uri.pathSegments
        when(uri.host){
            "note"->parts.firstOrNull()?.let{id->if(store.notes.notes.any{it.id==id}){detail=null;editor=null;planChat=null;requestedNote=id;page=Section.LISTS}else help="Заметка удалена или недоступна"}
            "entry"->parts.firstOrNull()?.let{id->store.entries.find{it.id==id && it.visibleTo(store.viewer)}?.let{open(it)}?:run{help="Запись удалена или недоступна"}}
            "plan"->if(parts.size==2){val group=store.plans.find{it.id==parts[0]};val plan=group?.items?.find{it.id==parts[1]};if(plan!=null){detail=null;editor=null;planChat=ChatTarget("plan_${group.id}_${plan.id}",plan.title,group.id,plan.id);chatAnchor=null;page=Section.PLANS}else help="План удалён или недоступен"}
            "chat"->if(parts.isNotEmpty()){
                val id=parts[0];chatAnchor=parts.getOrNull(2)?.takeIf{parts.getOrNull(1)=="message"}
                when {id=="general"->{page=Section.CHAT;planChat=null;detail=null;editor=null};id.startsWith("entry_")->{val event=store.entries.find{it.id==id.removePrefix("entry_") && it.visibility==Visibility.SHARED};if(event!=null){detail=null;editor=null;planChat=ChatTarget(id,event.title,itemId=event.id)}}
                    else->{val pair=store.plans.flatMap{g->g.items.map{g to it}}.find{(g,p)->"plan_${g.id}_${p.id}"==id};pair?.let{(g,p)->detail=null;editor=null;planChat=ChatTarget(id,p.title,g.id,p.id)}}}
            }
        }
    },LocalEntryNewState provides entryNew,LocalCreateTask provides {text->
        if(text.length>10000)help="Для задачи выбери фрагмент не длиннее 10 000 символов"
        else {
            val privateSource=taskSourcePrivate(text,
                notePrivate={id->store.notes.notes.find{it.id==id}?.let{store.notes.groupOf(it)?.let{group->!group.shared}}},
                entryVisibility={id->store.entries.find{it.id==id}?.visibility})
            val day=LocalDate.now();detail=null;editor=Entry(newId(),text.lineSequence().firstOrNull().orEmpty().take(100),day,mondayOf(day),owner=store.viewer,who=store.viewer,note=text,visibility=if(privateSource)Visibility.PRIVATE else Visibility.SHARED)
        }
    },LocalEntryChatReads provides entryReads,LocalInvitationReads provides invitationReads,LocalChatUnread provides unread,LocalRecordDelete provides store::deleteEntry,LocalRecordCanDelete provides store::canDelete) {
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=adaptive,drawerContent={
        if(adaptive)ModalDrawerSheet(Modifier.widthIn(max=320.dp).fillMaxHeight(),drawerContainerColor=MaterialTheme.colorScheme.surface) {
            Text("Kotiki team",Modifier.padding(20.dp),style=MaterialTheme.typography.titleLarge)
            AppNavigation(page,true,::navigate,::gatherCats,unread?.count?:0,invitationReads.count(store.entries))
        }
    }) {
        Surface(Modifier.fillMaxSize(),color=colors.background) {
            Box(Modifier.fillMaxSize()) {
                WinterBackdrop(Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.fillMaxWidth().background(winterSurface(if(winterDesignEnabled())colors.surfaceVariant else colors.surface,.80f)).padding(vertical=4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(adaptive)IconButton({scope.launch{drawer.open()}},Modifier.semantics{contentDescription="Открыть меню"}){KotikiIcon(Glyph.MENU)}
                            Box(Modifier.width(if(adaptive)44.dp else navigationWidth).height(44.dp),contentAlignment=Alignment.Center) {
                                BrandMark(Modifier.requiredSize(if(adaptive)48.dp else 62.dp))
                            }
                            if(adaptive)Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
                                Text("Kotiki team",style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant,maxLines=1)
                                Text(personLabel(store.viewer),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            } else Text("Kotiki team · ${personLabel(store.viewer)}",Modifier.weight(1f).padding(start=13.dp,end=12.dp),style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if(store.cloudSession!=null) {
                                if(!adaptive)SyncStatus(store)
                                IconButton({showHistory=true},Modifier.padding(end=6.dp).size(if(adaptive)48.dp else 34.dp).semantics{contentDescription="История уведомлений"}){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_reminder_bell),null,Modifier.size(20.dp))}
                            }
                        }
                        if(adaptive && store.cloudSession!=null)Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.CenterEnd){SyncStatus(store)}
                    }
                    HorizontalDivider(color=colors.outlineVariant)
                    Row(Modifier.weight(1f)) {
                        if(!adaptive){Box(Modifier.width(navigationWidth).fillMaxHeight().background(winterSurface(colors.surface,.72f))){AppNavigation(page,false,::navigate,::gatherCats,unread?.count?:0,invitationReads.count(store.entries))};VerticalDivider(color=colors.outlineVariant)}
                        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=12.dp)) {
                            if(store.storageWarning)Text("Не удалось прочитать сохранённое демо",color=colors.error,style=MaterialTheme.typography.labelSmall)
                            pages.SaveableStateProvider(page.name) {
                                if(planChat!=null) {
                                    val target=planChat!!
                                    val plan=store.plans.find{it.id==target.collectionId}?.items?.find{it.id==target.itemId}
                                    if(target.collectionId.isNotBlank() && plan==null) {
                                        Text("План удалён или больше недоступен")
                                        TextButton({planChat=null}){Text("Назад к планам")}
                                    } else ChatScreen(target.copy(title=plan?.title ?: target.title),chats,onBack={planChat=null},header=if(plan!=null){{
                                        val actions=rememberItemMenu()
                                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                                            Column(Modifier.weight(1f)) {
                                                var expanded by rememberSaveable(plan.id){mutableStateOf(false)}
                                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Описание",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);TextButton({expanded=!expanded}){Text(if(expanded)"Свернуть"else"Развернуть")}}
                                                if(expanded)LinkText(plan.description.ifBlank{"Описание пока не добавлено"},style=MaterialTheme.typography.bodyMedium,format=plan.descriptionFormat)
                                            }
                                            ItemActions(plan.title,{store.deleteItem(true,target.collectionId,target.itemId);planChat=null},actions)
                                        }
                                        TextButton({planEditing=true}){Text("Редактировать")}
                                        Text("Комментарии",style=MaterialTheme.typography.titleSmall)
                                    }}else null,initialMessageId=chatAnchor)
                                }
                                else when(page) {
                                    Section.BOARD->BoardScreen(store,::open,{editor=it},{help=it})
                                    Section.PLANS->CollectionsScreen(store,true,onPlanChat={store.preparePlanChat();planChat=it}){help=it}
                                    Section.CALENDAR->CalendarScreen(store,::open,{editor=it},{help=it})
                                    Section.LISTS->NotesScreen(store.notes,store.manualOrder,requestedNoteId=requestedNote,onNoteOpened={requestedNote=null}){help=it}
                                    Section.INVITES->InvitationsScreen(store,{detail=it},{val today=LocalDate.now();editor=Entry(newId(),"",today,mondayOf(today),kind=Kind.EVENT,owner=store.viewer,invite=InviteState.PENDING)})
                                    Section.CHAT->if(BuildConfig.WINTER_PREVIEW && localChatPreview!=null)localChatPreview() else ChatScreen(ChatTarget("general","Общий чат"),chats,initialMessageId=chatAnchor)
                                    Section.SETTINGS->SettingsScreen(store,onProfile,onSignOut)
                                }
                            }
                        }
                    }
                }
                WinterSnow(Modifier.fillMaxSize().safeDrawingPadding())
                SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
            }
        }
    }
    moveWeek?.let{entry->
        var week by remember(entry.id){mutableStateOf(entry.week)}
        AlertDialog(onDismissRequest={moveWeek=null},title={Text("Перенести на неделю")},text={Row(verticalAlignment=Alignment.CenterVertically){IconButton({week=week.minusWeeks(1)}){KotikiIcon(Glyph.LEFT)};Text("${dateLabel(week)} — ${dateLabel(week.plusDays(6))}",Modifier.weight(1f));IconButton({week=week.plusWeeks(1)}){KotikiIcon(Glyph.RIGHT)}}},confirmButton={TextButton({store.upsert(entry.copy(week=week));moveWeek=null}){Text("Перенести")}},dismissButton={TextButton({moveWeek=null}){Text("Отмена")}})
    }
    if(showHistory)store.cloudSession?.let{NotificationHistoryDialog(it,onDismiss={showHistory=false},onOpen={target->showHistory=false;historyTarget=target},store=store,chats=chats)}
    SharedContentInbox.pending?.takeIf{it.id!=shareDismissed && sharedDestination==null}?.let{incoming->SharedContentPicker(incoming,onChoose={destination,content->sharedDestination=destination to content},onDismiss={shareDismissed=incoming.id})}
    sharedDestination?.let{(destination,content)->TextDestinationDialog(content,destination,store,chats,onTask={editor=it},onDone={SharedContentInbox.clear(store.appContext,content.id);sharedDestination=null},onDismiss={sharedDestination=null;shareDismissed=content.id})}
    if(planEditing)planChat?.let{target->
        val item=store.plans.find{it.id==target.collectionId}?.items?.find{it.id==target.itemId}
        if(item!=null)PlanEditor(item,onDismiss={planEditing=false},sourceLink=sourceDeepLink("plan",target.collectionId,target.itemId)){title,description->
            store.updateItem(true,target.collectionId,target.itemId,title,description);planEditing=false
        }
    }
    proposalEditor?.let {original -> ProposalEditor(original,onDismiss={proposalEditor=null},onPropose={store.proposeInvitation(original,it);proposalEditor=null}) }
    meeting?.let {entry->EntryEditor(entry,onDismiss={meeting=null},onSave={store.upsert(it);meeting=null},isNew=store.entries.none{it.id==entry.id},reminderAccount=reminderAccount)}
    editor?.let {entry->EntryEditor(entry,onDismiss={editor=null},onSave={store.upsert(it);editor=null},
        isNew=store.entries.none{it.id==entry.id},reminderAccount=reminderAccount,
        onDelete=if(store.entries.any{it.id==entry.id} && store.canDelete(entry)){{store.deleteEntry(entry);editor=null}} else null,
        onHistory=if(store.cloudSession!=null && store.entries.any{it.id==entry.id}){{history=listOf("Загружаем историю…");store.cloudHistory?.invoke("entry_${entry.id}"){history=it}}}else null,
        calendarActions=if(store.entries.any{it.id==entry.id} && store.cloudSession!=null) {{
            CalendarExportActions(entry,store.cloudSession.authUid)
            if(entry.visibility==Visibility.SHARED)TextButton({editor=null;planChat=ChatTarget("entry_${entry.id}",entry.title,itemId=entry.id)}){Text("Обсудить в чате")}
        }} else null)}
    detail?.let {opened->
        val entry=store.entries.find{it.id==opened.id}?.redactFor(store.viewer) ?: opened
        val actions=rememberItemMenu()
        InvitationReadEffect(entry,state=invitationReads)
        EntryDetailsDialog(entry,onClose={detail=null},onEdit=if(entry.canEdit(store.viewer)){{detail=null;editor=entry}}else null,
            actions={ItemActions(entry.title,if(store.canDelete(entry)){{store.deleteEntry(entry);detail=null}}else null,actions)}) {
        if(entry.invite!=InviteState.NONE) {
            Text("Отправитель: ${personLabel(entry.owner)}",style=MaterialTheme.typography.bodyMedium)
            Text("Получатель: ${personLabel(entry.owner.other())}",style=MaterialTheme.typography.bodyMedium)
        }
        entry.proposal?.let { p ->
            HorizontalDivider()
            Text("Предложение: ${personLabel(p.proposedBy)}",style=MaterialTheme.typography.titleSmall)
            Text("${fullDayLabel(p.date)} · ${p.start}–${p.end}")
            if(p.title!=entry.title)LinkText(p.title,format=p.titleFormat)
            if(p.note.isNotBlank())LinkText(p.note,format=p.descriptionFormat)
            Text("До подтверждения действует прежнее время.",style=MaterialTheme.typography.bodySmall)
        }
        InvitationResponses(entry,store.viewer,onAccept={store.acceptInvitation(entry);detail=null},
            onDecline={store.respondInvitation(entry,false);detail=null},onPropose={detail=null;proposalEditor=entry})

        store.cloudSession?.let {
            if(entry.visibility!=Visibility.BUSY || entry.owner==store.viewer)CalendarExportActions(entry,it.authUid)
        }
        if((entry.who==store.viewer || entry.who==Person.BOTH) && (entry.visibility!=Visibility.BUSY || entry.owner==store.viewer))EntryReminderSummary(entry,reminderAccount)
        if(store.cloudSession!=null && entry.visibility==Visibility.SHARED)TextButton({detail=null;planChat=ChatTarget("entry_${entry.id}",entry.title,itemId=entry.id)}){
            Text("Обсудить в чате");Spacer(Modifier.width(8.dp));ChatCountBadge(entryReads?.count(entry.id) ?: 0)
        }

        if(entry.invite!=InviteState.NONE && store.cloudSession==null)Text("Пример приглашения · обмен пока недоступен",color=colors.onSurfaceVariant)
        if(store.cloudSession!=null)TextButton({
            history=listOf("Загружаем историю…")
            store.cloudHistory?.invoke("entry_${entry.id}"){history=it}
        }){Text("История изменений")}
        if(entry.invite==InviteState.ACCEPTED)Text(if(entry.proposal==null)"Приглашение принято" else "Принято прежнее время · изменения ожидают ответа",color=colors.primary)
        if(entry.isGathering && entry.invite==InviteState.PENDING && entry.gatheringError()==null)GatheringNotificationPreview(entry)
    }}
    history?.let {rows->AlertDialog(onDismissRequest={history=null},title={Text("История изменений")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(rows.isEmpty())Text("Пока нет сохранённых версий") else rows.forEach{Text(it)}
        }},confirmButton={QuietCloseButton({history=null})})}
    help?.let {text->AlertDialog(onDismissRequest={help=null},title={Text("Видимость")},text={Text(text)},confirmButton={TextButton({help=null}){Text("Понятно")}})}
    }
}

@Composable private fun AppNavigation(page:Section,expanded:Boolean,onSelect:(Section)->Unit,onGather:()->Unit,unread:Int=0,unreadInvites:Int=0) {
    Column(Modifier.fillMaxSize().padding(vertical=8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section.entries.filter{it!=Section.SETTINGS}.forEach{NavigationItem(it,page==it,expanded,when(it){Section.CHAT->unread;Section.INVITES->unreadInvites;else->0}){onSelect(it)}}
            GatherCatsButton(expanded,onGather)
        }

        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        NavigationItem(Section.SETTINGS,page==Section.SETTINGS,expanded){onSelect(Section.SETTINGS)}
    }
}
@Composable internal fun GatherCatsButton(expanded:Boolean,onClick:()->Unit) {
    NavigationCell("Собрать\nкотиков",Glyph.MEETING,false,expanded,0,"",onClick)
}
@Composable internal fun NavigationItem(section:Section,selected:Boolean,expanded:Boolean,unread:Int=0,onClick:()->Unit) {
    val glyph=when(section){Section.BOARD->Glyph.BOARD;Section.PLANS->Glyph.PLANS;Section.CALENDAR->Glyph.CALENDAR;Section.LISTS->Glyph.LIST;Section.INVITES->Glyph.MAIL;Section.CHAT->Glyph.CHAT;Section.SETTINGS->Glyph.SETTINGS}
    NavigationCell(section.label,glyph,selected,expanded,unread,
        if(section==Section.INVITES)"Непрочитанных приглашений" else "Непрочитанных сообщений",onClick)
}
@Composable private fun NavigationCell(label:String,glyph:Glyph,selected:Boolean,expanded:Boolean,unread:Int,badgeLabel:String,onClick:()->Unit) {
    val color=if(selected)winterSurface(MaterialTheme.colorScheme.primaryContainer) else if(winterDesignEnabled())Color.Transparent else MaterialTheme.colorScheme.surface
    val modifier=Modifier.padding(horizontal=3.dp,vertical=3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(color).clickable(onClickLabel=label,onClick=onClick)
    if(expanded)Row(modifier.heightIn(min=48.dp).padding(horizontal=16.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        NavigationGlyph(glyph,unread,badgeLabel);Text(label,Modifier.weight(1f),textAlign=if(glyph==Glyph.MEETING)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
    } else Column(modifier.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        NavigationGlyph(glyph,unread,badgeLabel)
        Text(label,fontSize=9.sp,lineHeight=11.sp,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun NavigationGlyph(glyph:Glyph,unread:Int,label:String="Непрочитанных сообщений") {
    BadgedBox(modifier=Modifier.size(24.dp),badge={if(unread>0)Badge(Modifier.offset(x=2.dp,y=(-2).dp).heightIn(min=14.dp).semantics{contentDescription="$label: ${if(unread>99)"больше 99" else unread}"},containerColor=MaterialTheme.colorScheme.primary.copy(alpha=.88f),contentColor=MaterialTheme.colorScheme.onPrimary){Text(if(unread>99)"99+" else unread.toString(),fontSize=9.sp,lineHeight=11.sp)}}){KotikiIcon(glyph)}
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
@Composable fun RecordCard(entry: Entry, onOpen: (Entry) -> Unit, onDone: (Entry) -> Unit, onHelp: (String) -> Unit, onAccept:(Entry)->Unit,compact:Boolean=false,iceSpace:androidx.compose.ui.unit.Dp=0.dp) {
    val viewer = LocalDemoIdentity.current.viewer
    val colors = MaterialTheme.colorScheme
    val actions=rememberItemMenu()
    Surface(Modifier.fillMaxWidth().markNewEntry(entry).winterIcicles(entry.id,iceSpace).clickable{onOpen(entry)},
        shape = RoundedCornerShape(if (entry.isDayTask) 10.dp else 15.dp),
        border = if(entry.isGathering)androidx.compose.foundation.BorderStroke(1.dp,gatheringAccent().copy(alpha=.55f)) else null,
        color = if(entry.isGathering)gatheringTint() else participantTint(entry.who, entry.isDayTask)) {
        Column {
        Column(Modifier.alpha(if(entry.isTentative).58f else if(entry.done).55f else 1f)) {
        if (entry.isDayTask) {
            Row(Modifier.padding(start = 2.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.canEdit(viewer)) Checkbox(checked = entry.done, onCheckedChange = { onDone(entry) }, modifier = Modifier.size(42.dp))
                LinkText(entry.title,format=entry.titleFormat, modifier=Modifier.weight(1f).padding(vertical = if(compact)4.5.dp else 9.dp), fontSize = 13.sp,
                    textDecoration = if(entry.done) TextDecoration.LineThrough else null)
                EntryChatBadge(entry){onOpen(entry)}
                AccessIcon(entry, onHelp)
                RecordActions(entry,actions,onOpen)
            }
        } else {
            Column(Modifier.padding(horizontal=10.dp,vertical=if(compact)5.dp else 10.dp)) {
                if(entry.isGathering)GatheringLabel()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when { entry.isMultiDay -> "${dateLabel(entry.date!!)} — ${dateLabel(entry.lastDate!!)}"; entry.start.isNotBlank() -> "${entry.start}–${entry.end}"; else -> "Весь день" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    AccessIcon(entry, onHelp)
                RecordActions(entry,actions,onOpen)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    LinkText(entry.title,format=entry.titleFormat, modifier=Modifier.weight(1f).padding(vertical = if(compact)2.5.dp else 5.dp), fontWeight = FontWeight.Medium)
                    EntryChatBadge(entry){onOpen(entry)}
                }
                if(entry.isTentative || entry.proposal!=null) Text(if(entry.canAccept(viewer)) "Ждёт твоего ответа" else "Ждёт ответа: ${personLabel(viewer.other())}", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                if(entry.proposal!=null)Text("Предложено другое время",style=MaterialTheme.typography.labelSmall,color=colors.primary)
                if(entry.kind == Kind.TASK && entry.canEdit(viewer)) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(entry.done, { onDone(entry) }); Text("Выполнено", style=MaterialTheme.typography.labelSmall) }
            }
        }
        }
        InvitationAcceptButton(entry,onAccept,Modifier.padding(horizontal=10.dp))
        if(entry.invite==InviteState.ACCEPTED)Text("Принято",Modifier.padding(start=10.dp,bottom=if(compact)4.dp else 8.dp),style=MaterialTheme.typography.labelSmall,color=colors.primary)
        }
    }
}

@Composable fun InvitationAcceptButton(entry:Entry,onAccept:(Entry)->Unit,modifier:Modifier=Modifier) {
    if(entry.canAccept(LocalDemoIdentity.current.viewer)) {
        Button({onAccept(entry)},modifier.semantics{contentDescription="Принять приглашение «${entry.title}»"}){Text(if(entry.proposal!=null)"Подтвердить изменения" else "Принять")}
    }
}
