package team.kotiki.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun NotesScreen(store:NotesStore,manualOrder:LocalOrderStore,requestedNoteId:String?=null,onNoteOpened:()->Unit={},onHelp:(String)->Unit) {
    val createTask=LocalCreateTask.current
    var selectedId by rememberSaveable{mutableStateOf("")}
    var archive by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var opened by rememberSaveable{mutableStateOf<String?>(null)}
    LaunchedEffect(requestedNoteId,store.notes.toList()) {
        requestedNoteId?.let{id->store.notes.find{it.id==id}?.let{opened=id;selectedId=it.groupId;archive=it.archived||store.groupOf(it)?.archived==true;onNoteOpened()}}
    }
    var newGroup by rememberSaveable{mutableStateOf(false)}
    var rename by remember{mutableStateOf<NoteGroup?>(null)}
    var moveId by rememberSaveable{mutableStateOf<String?>(null)}
    var moveTarget by remember{mutableStateOf<NoteGroup?>(null)}
    val groups=store.groups.filter{it.archived==archive}.sortedByDescending{g->maxOf(g.updatedAt,store.notes.filter{it.groupId==g.id}.maxOfOrNull{it.updatedAt}?:0)}
    val selected=groups.find{it.id==selectedId}?:groups.firstOrNull()
    BackHandler(archive&&opened==null){archive=false}
    Column(Modifier.fillMaxSize()) {
        ScreenHeading(if(archive)"Архив заметок" else "Заметки",if(archive)"Назад" else "+ Группа",{if(archive)archive=false else newGroup=true})
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Поиск по всем заметкам")},singleLine=true,
            trailingIcon=if(query.isNotEmpty()){{IconButton({query=""}){KotikiIcon(Glyph.CLOSE)}}}else null)
        if(store.error.isNotEmpty()) {
            Text(store.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            TextButton(store::retry){Text("Повторить")}
        } else Text(if(store.pending)"Сохранено на телефоне · ожидает синхронизации" else "Изменения сохраняются автоматически",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val scroll=rememberScrollState()
        var viewport by remember{mutableStateOf<Rect?>(null)}
        CompositionLocalProvider(LocalReorderViewport provides ReorderViewport({viewport}){scroll.scrollBy(it)}) {
        Column(Modifier.weight(1f).onGloballyPositioned{viewport=it.boundsInWindow()}.verticalScroll(scroll)) {
            if(query.isBlank()) {
                CollectionTopics(groups.map{DemoCollection(it.id,it.name,it.shared,owner=it.owner,updatedAt=it.updatedAt)},selected?.id,true,onHelp,
                    onActions={id->rename=groups.find{it.id==id}}){selectedId=it}
                selected?.let{g->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(g.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        if(!archive)TextButton({opened=store.addNote(g)},enabled=store.canEdit(g)){Text("+ Заметка")}
                        var menu by remember{mutableStateOf(false)}
                        Box {
                            IconButton({menu=true}){KotikiIcon(Glyph.SETTINGS,Modifier.size(18.dp))}
                            DropdownMenu(menu,{menu=false}) {
                                if(store.canEdit(g)) {
                                    DropdownMenuItem(text={Text("Переименовать")},onClick={menu=false;rename=g})
                                    DropdownMenuItem(text={Text(if(archive)"Восстановить группу" else "В архив с заметками")},onClick={menu=false;store.changeGroup(g,archived=!archive)})
                                }
                            }
                        }
                    }
                    if(!g.shared)Text("Только на этом телефоне",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
            val visible=store.notes.filter{n->val g=store.groupOf(n);g!=null && (n.archived||g.archived)==archive && (query.isNotBlank()||archive||n.groupId==selected?.id) && n.matches(query)}.sortedByDescending{it.updatedAt}
            ReorderColumn(manualOrder,"notes/${selected?.id}",visible,{it.id},enabled=query.isBlank() && !archive) {note->
                val group=store.groupOf(note)!!
                var menu by remember{mutableStateOf(false)}
                var purge by remember{mutableStateOf(false)}
                Surface(Modifier.fillMaxWidth().padding(vertical=4.dp).winterIcicles(note.id,if(note.id==manualOrder.sorted("notes/${selected?.id}",visible,{it.id}).lastOrNull()?.id)6.dp else 0.dp).clickable{opened=note.id},shape=RoundedCornerShape(14.dp),color=winterSurface(MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            LinkText(note.title.ifBlank{"Без названия"},format=note.titleFormat,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            LinkText(note.preview.ifBlank{"Пустая заметка"},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            if(query.isNotBlank()||archive)Text(group.name,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            if(store.moving(note.id))Text("Перенос ожидает подтверждения сервера",style=MaterialTheme.typography.labelSmall)
                        }
                        Box {
                            IconButton({menu=true},Modifier.semantics{contentDescription="Действия: ${note.label}"}){KotikiIcon(Glyph.SETTINGS,Modifier.size(18.dp))}
                            DropdownMenu(menu,{menu=false}) {
                                if(createTask!=null)DropdownMenuItem(text={Text("Создать задачу")},onClick={menu=false;createTask(selectionWithSource(richPlainText(note.title,note.titleFormat)+"\n"+richPlainText(note.body,MARKDOWN_TEXT_FORMAT),sourceDeepLink("note",note.id)))})
                                DropdownMenuItem(text={Text("Переместить")},enabled=!store.moving(note.id),onClick={menu=false;moveId=note.id})
                                if(store.canEdit(group)&&!store.moving(note.id)) {
                                    DropdownMenuItem(text={Text(if(archive)"Восстановить" else "В архив")},onClick={menu=false;if(group.archived)store.changeGroup(group,archived=false);store.archive(note,!archive)})
                                    if(note.archived)DropdownMenuItem(text={Text("Удалить навсегда")},onClick={menu=false;purge=true})
                                }
                            }
                        }
                    }
                }
                if(purge)AlertDialog(onDismissRequest={purge=false},title={Text("Удалить заметку навсегда?")},text={Text("Восстановить её будет нельзя.")},
                    confirmButton={TextButton({store.purge(note);purge=false}){Text("Удалить")}},dismissButton={TextButton({purge=false}){Text("Отмена")}})
            }
            if(visible.isEmpty())Text(if(query.isNotBlank())"Ничего не найдено" else if(archive)"Архив пока пуст" else "Создай заметку: текст и списки могут быть в одной записи.",Modifier.padding(vertical=20.dp),style=MaterialTheme.typography.bodyMedium)
        }
        }
        if(!archive)OutlinedButton({archive=true},Modifier.fillMaxWidth()){Text("Архив заметок")}
    }
    if(newGroup)NoteGroupDialog({newGroup=false}){name,shared,editable->selectedId=store.addGroup(name,shared,editable);newGroup=false}
    rename?.let{g->NameDialog("Название группы",false,{rename=null},g.name){name,_->store.changeGroup(g,name);rename=null}}
    opened?.let{id->val note=store.notes.find{it.id==id};if(note!=null)NoteEditor(store,id,{opened=null},{moveId=id})else LaunchedEffect(id){opened=null}}
    moveId?.let{id->val note=store.notes.find{it.id==id};if(note!=null)AlertDialog(onDismissRequest={moveId=null},title={Text("Переместить заметку")},
        text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            store.groups.filter{!it.archived&&it.id!=note.groupId}.forEach{g->TextButton({moveTarget=g}){Text(g.name+if(g.shared)" · для двоих" else " · только на телефоне")}}
            if(store.groups.none{!it.archived&&it.id!=note.groupId})Text("Сначала создай другую группу.")
        }},confirmButton={},dismissButton={TextButton({moveId=null}){Text("Отмена")}})
        moveTarget?.let{target->if(note!=null)AlertDialog(onDismissRequest={moveTarget=null},title={Text("Переместить в «${target.name}»?")},
            text={Text(if(target.shared)"Заметка будет доступна обоим участникам с правами этой группы." else "Заметка останется только на этом телефоне. Если она общая, её содержимое будет удалено с сервера после подключения.")},
            confirmButton={TextButton({store.move(note,target);moveTarget=null;moveId=null}){Text("Переместить")}},dismissButton={TextButton({moveTarget=null}){Text("Отмена")}})}
    }
}

@Composable private fun NoteGroupDialog(onClose:()->Unit,onCreate:(String,Boolean,Boolean)->Unit) {
    var name by rememberSaveable{mutableStateOf("")};var shared by rememberSaveable{mutableStateOf(true)};var editable by rememberSaveable{mutableStateOf(true)}
    AlertDialog(onDismissRequest=onClose,title={Text("Новая группа заметок")},text={Column {
        OutlinedTextField(name,{name=it.take(100)},label={Text("Название")},singleLine=true)
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=shared,role=Role.Checkbox,onValueChange={shared=it}),verticalAlignment=Alignment.CenterVertically){Checkbox(shared,null);Text("Видно всем",Modifier.weight(1f))}
        if(shared)Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=editable,role=Role.Checkbox,onValueChange={editable=it}),verticalAlignment=Alignment.CenterVertically){Checkbox(editable,null);Text("Совместное редактирование",Modifier.weight(1f))}
        else Text("Личные заметки и их архив хранятся только на этом телефоне.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({onCreate(name,shared,editable)},enabled=name.isNotBlank()){Text("Создать")}},dismissButton={TextButton(onClose){Text("Отмена")}})
}

@Composable private fun NoteEditor(store:NotesStore,id:String,onClose:()->Unit,onMove:()->Unit) {
    val note=store.notes.find{it.id==id}?:return
    val group=store.groupOf(note)?:return
    val createTask=LocalCreateTask.current
    val editable=store.canEdit(group)&&!store.moving(id)&&!group.archived&&!note.archived
    var menu by remember{mutableStateOf(false)}
    val initialFocus=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        EditorSurface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.93f).imePadding(),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp).focusRequester(initialFocus).focusable()) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(group.name,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    Box {
                        IconButton({menu=true}){KotikiIcon(Glyph.SETTINGS,Modifier.size(18.dp))}
                        DropdownMenu(menu,{menu=false}) {
                            if(createTask!=null)DropdownMenuItem(text={Text("Создать задачу")},onClick={menu=false;onClose();createTask(selectionWithSource(richPlainText(note.title,note.titleFormat)+"\n"+richPlainText(note.body,MARKDOWN_TEXT_FORMAT),sourceDeepLink("note",note.id)))})
                            DropdownMenuItem(text={Text("Переместить")},enabled=!store.moving(id),onClick={menu=false;onMove()})
                            if(editable)DropdownMenuItem(text={Text("В архив")},onClick={menu=false;store.archive(note);onClose()})
                        }
                    }
                    IconButton(onClose,Modifier.semantics{contentDescription="Закрыть заметку"}){KotikiIcon(Glyph.CLOSE)}
                }
                Text(if(store.error.isNotBlank())store.error else if(store.localSaving)"Сохраняем на телефоне…" else if(!editable && !store.moving(id))"Только просмотр" else if(store.moving(id))"Перенос ожидает сервера" else if(!group.shared)"Сохранено только на телефоне" else if(store.pending)"Сохранено на телефоне · отправляем" else "Сохранено",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                RichTextEditor(note.title,{value->store.notes.find{it.id==id}?.let{store.edit(it.copy(title=value,titleFormat=MARKDOWN_TEXT_FORMAT))}},Modifier.fillMaxWidth(),
                    format=note.titleFormat,titleOnly=true,minLines=1,readOnly=!editable,maxLength=200,sourceLink=sourceDeepLink("note",note.id))
                HorizontalDivider(Modifier.padding(vertical=6.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                RichTextEditor(note.body,{value->store.notes.find{it.id==id}?.let{store.edit(it.copy(markdown=value,textFormat=MARKDOWN_TEXT_FORMAT,blocks=emptyList()))}},Modifier.weight(1f).fillMaxWidth(),
                    format=MARKDOWN_TEXT_FORMAT,readOnly=!editable,minLines=3,blankTapNewLine=true,maxLength=140000,collapsibleChecks=true,sourceLink=sourceDeepLink("note",note.id))
            }
            LaunchedEffect(id){initialFocus.requestFocus();keyboard?.hide()}
        }
    }
}

/** A blank tap reuses a trailing empty line instead of accumulating blank paragraphs. */
internal fun noteBlankLine(text:String)=if(text.isEmpty()||text.endsWith("\n"))text else text+"\n"
