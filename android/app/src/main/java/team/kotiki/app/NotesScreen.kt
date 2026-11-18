package team.kotiki.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun NotesScreen(store:NotesStore,onHelp:(String)->Unit) {
    var selectedId by rememberSaveable{mutableStateOf("")}
    var archive by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var opened by rememberSaveable{mutableStateOf<String?>(null)}
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
            visible.forEach{note->key(note.id){
                val group=store.groupOf(note)!!
                var menu by remember{mutableStateOf(false)}
                var purge by remember{mutableStateOf(false)}
                Surface(Modifier.fillMaxWidth().padding(vertical=4.dp).combinedClickable(onClick={opened=note.id},onLongClick={menu=true}),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(start=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(note.label,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(note.blocks.joinToString(" "){it.text}.ifBlank{"Пустая заметка"},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            if(query.isNotBlank()||archive)Text(group.name,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            if(store.moving(note.id))Text("Перенос ожидает подтверждения сервера",style=MaterialTheme.typography.labelSmall)
                        }
                        Box {
                            IconButton({menu=true},Modifier.semantics{contentDescription="Действия: ${note.label}"}){KotikiIcon(Glyph.SETTINGS,Modifier.size(18.dp))}
                            DropdownMenu(menu,{menu=false}) {
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
            }}
            if(visible.isEmpty())Text(if(query.isNotBlank())"Ничего не найдено" else if(archive)"Архив пока пуст" else "Создай заметку: текст и списки могут быть в одной записи.",Modifier.padding(vertical=20.dp),style=MaterialTheme.typography.bodyMedium)
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
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(shared,{shared=it});Text("Видно всем")}
        if(shared)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(editable,{editable=it});Text("Совместное редактирование")}
        else Text("Личные заметки и их архив хранятся только на этом телефоне.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({onCreate(name,shared,editable)},enabled=name.isNotBlank()){Text("Создать")}},dismissButton={TextButton(onClose){Text("Отмена")}})
}

@Composable private fun NoteEditor(store:NotesStore,id:String,onClose:()->Unit,onMove:()->Unit) {
    val note=store.notes.find{it.id==id}?:return
    val group=store.groupOf(note)?:return
    val editable=store.canEdit(group)&&!store.moving(id)&&!group.archived&&!note.archived
    var menu by remember{mutableStateOf(false)}
    var folded by rememberSaveable{mutableStateOf(true)}
    val focus=remember{FocusRequester()};val keyboard=LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.93f).imePadding(),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp).focusRequester(focus).focusable()) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(group.name,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    Box {
                        IconButton({menu=true}){KotikiIcon(Glyph.SETTINGS,Modifier.size(18.dp))}
                        DropdownMenu(menu,{menu=false}) {
                            DropdownMenuItem(text={Text("Переместить")},enabled=!store.moving(id),onClick={menu=false;onMove()})
                            if(editable)DropdownMenuItem(text={Text("В архив")},onClick={menu=false;store.archive(note);onClose()})
                        }
                    }
                    IconButton(onClose,Modifier.semantics{contentDescription="Закрыть заметку"}){KotikiIcon(Glyph.CLOSE)}
                }
                Text(if(store.error.isNotBlank())store.error else if(!editable && !store.moving(id))"Только просмотр" else if(store.moving(id))"Перенос ожидает сервера" else if(!group.shared)"Сохранено только на телефоне" else if(store.pending)"Сохранено на телефоне · отправляем" else "Сохранено",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(note.title,{store.edit(note.copy(title=it))},Modifier.fillMaxWidth().padding(vertical=8.dp),placeholder={Text("Название")},readOnly=!editable,textStyle=MaterialTheme.typography.titleLarge)
                    var number=0
                    note.blocks.filterNot{folded&&it.kind==NoteBlockKind.CHECK&&it.checked}.forEach{block->key(block.id){
                        if(block.kind==NoteBlockKind.NUMBERED)number++ else number=0
                        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.Top) {
                            when(block.kind) {
                                NoteBlockKind.CHECK->Checkbox(block.checked,{v->store.edit(note.copy(blocks=note.blocks.map{if(it.id==block.id)it.copy(checked=v)else it}))},enabled=editable)
                                NoteBlockKind.BULLET->Text("•",Modifier.padding(end=8.dp,top=8.dp))
                                NoteBlockKind.NUMBERED->Text("$number.",Modifier.padding(end=8.dp,top=8.dp))
                                else->Unit
                            }
                            BasicTextField(block.text,{value->store.edit(note.copy(blocks=note.blocks.map{if(it.id==block.id)it.copy(text=value)else it}))},
                                Modifier.weight(1f).heightIn(min=40.dp).padding(vertical=8.dp).semantics{contentDescription="Текст блока"},readOnly=!editable,
                                textStyle=(if(block.kind==NoteBlockKind.HEADING)MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge).copy(color=MaterialTheme.colorScheme.onSurface,fontWeight=if(block.bold)FontWeight.Bold else if(block.kind==NoteBlockKind.HEADING)FontWeight.SemiBold else FontWeight.Normal,textDecoration=if(block.checked)TextDecoration.LineThrough else TextDecoration.None),
                                decorationBox={inner->Box{if(block.text.isEmpty())Text(if(block.kind==NoteBlockKind.TEXT)"Текст, ссылка…" else "Пункт…",color=MaterialTheme.colorScheme.onSurfaceVariant);inner()}})
                            if(editable) {
                                var blockMenu by remember{mutableStateOf(false)}
                                Box {
                                    IconButton({blockMenu=true},Modifier.size(40.dp).semantics{contentDescription="Формат блока"}){KotikiIcon(Glyph.SETTINGS,Modifier.size(14.dp))}
                                    DropdownMenu(blockMenu,{blockMenu=false}) {
                                        NoteBlockKind.entries.forEach{k->DropdownMenuItem(text={Text(blockKindLabel(k))},onClick={blockMenu=false;store.edit(note.copy(blocks=note.blocks.map{if(it.id==block.id)it.copy(kind=k,checked=false)else it}))})}
                                        DropdownMenuItem(text={Text(if(block.bold)"Обычный шрифт" else "Жирный шрифт")},onClick={blockMenu=false;store.edit(note.copy(blocks=note.blocks.map{if(it.id==block.id)it.copy(bold=!it.bold)else it}))})
                                        DropdownMenuItem(text={Text("Удалить блок")},onClick={blockMenu=false;store.edit(note.copy(blocks=note.blocks.filterNot{it.id==block.id}))})
                                    }
                                }
                            }
                        }
                    }}
                    val done=note.blocks.count{it.kind==NoteBlockKind.CHECK&&it.checked}
                    if(done>0)TextButton({folded=!folded}){Text(if(folded)"Показать выполненные ($done)" else "Свернуть выполненные ($done)")}
                }
                if(editable)FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    NoteBlockKind.entries.forEach{k->TextButton({store.edit(note.copy(blocks=note.blocks+NoteBlock(kind=k)))},enabled=note.blocks.size<200){Text("+ "+blockKindLabel(k),style=MaterialTheme.typography.labelSmall)}}
                }
            }
            LaunchedEffect(id){focus.requestFocus();keyboard?.hide()}
        }
    }
}
private fun blockKindLabel(kind:NoteBlockKind)=when(kind){NoteBlockKind.TEXT->"Текст";NoteBlockKind.HEADING->"Заголовок";NoteBlockKind.BULLET->"Список";NoteBlockKind.NUMBERED->"Нумерация";NoteBlockKind.CHECK->"Чек-лист"}
