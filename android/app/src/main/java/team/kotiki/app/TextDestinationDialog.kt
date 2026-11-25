package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun TextDestinationDialog(content:SharedContent,destination:ShareDestination,store:DemoStore,chats:CloudChats?,onDone:()->Unit,onDismiss:()->Unit,onTask:(Entry)->Unit){
    var text by remember(content.id){mutableStateOf(content.text)}
    var format by remember(content.id){mutableStateOf(content.textFormat)}
    var title by remember(content.id){mutableStateOf(content.subject.ifBlank{richPlainText(content.text,content.textFormat).lineSequence().firstOrNull().orEmpty().take(100)})}
    var chosen by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    var working by remember{mutableStateOf(false)}
    val groups=when(destination){ShareDestination.NOTE->store.notes.groups.filter{!it.archived && store.notes.canEdit(it)}.map{it.id to (it.name+if(!it.shared)" · Только мне"else "")};ShareDestination.PLAN->store.plans.map{it.id to it.name};else->emptyList()}
    val targets=remember(store.plans.toList(),store.entries.toList()){listOf(ChatTarget("general","Общий чат"))+store.plans.flatMap{group->group.items.map{ChatTarget("plan_${group.id}_${it.id}",it.title,group.id,it.id)}}+store.entries.filter{it.visibility==Visibility.SHARED}.map{ChatTarget("entry_${it.id}",it.title,itemId=it.id)}}
    LaunchedEffect(destination){chosen=if(destination==ShareDestination.CHAT)"general"else groups.firstOrNull()?.first.orEmpty()}
    AlertDialog(onDismissRequest=onDismiss,title={Text(destination.label)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(destination!=ShareDestination.CHAT)OutlinedTextField(title,{title=it},label={Text("Название")},singleLine=true,isError=title.length>100)
        if(destination!=ShareDestination.CHAT && title.length>100)Text("Название: не более 100 символов. Сократи его перед сохранением.",color=MaterialTheme.colorScheme.error)
        RichTextEditor(text,{text=it;format=MARKDOWN_TEXT_FORMAT},Modifier.heightIn(max=260.dp),format=format,label="Текст",minLines=2,maxLines=8)
        val choices=if(destination==ShareDestination.CHAT)targets.map{it.id to it.title}else groups
        choices.forEach{(id,name)->Row{RadioButton(chosen==id,{chosen=id});TextButton({chosen=id}){Text(name)}}}
        if(choices.isEmpty() && destination==ShareDestination.NOTE)TextButton({chosen=store.notes.addGroup("Заметки",false,true)}){Text("Создать личную группу")}
        if(choices.isEmpty() && destination==ShareDestination.PLAN)TextButton({store.addCollection(true,"Планы",true);chosen=store.plans.last().id}){Text("Создать тему")}
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({
        val plain=richPlainText(text,format)
        val limit=if(destination==ShareDestination.TASK)10000 else if(destination==ShareDestination.NOTE)100000 else 4000
        if(text.length>limit){error="Слишком длинный текст: максимум $limit символов. Сократи его или выбери заметку.";return@TextButton}
        when(destination){
            ShareDestination.CHAT->{val target=targets.find{it.id==chosen};if(chats==null || target==null){error="Для отправки нужен вход в облако"}else{working=true;chats.send(target,text,onQueued={working=false;onDone()},onFailure={working=false;error=it},format=format)}}
            ShareDestination.NOTE->{val group=store.notes.groups.find{it.id==chosen};if(group!=null){val id=store.notes.createFromText(group,text,format,title);if(id.isNotEmpty())onDone()else error=store.notes.error}}
            ShareDestination.PLAN->{if(chosen.isNotEmpty()){store.addItem(true,chosen,title,text,titleFormat=PLAIN_TEXT_FORMAT,descriptionFormat=format);onDone()}}
            ShareDestination.TASK->{val day=java.time.LocalDate.now();onTask(Entry(newId(),title,day,mondayOf(day),owner=store.viewer,who=store.viewer,note=text,descriptionFormat=format));onDone()}
        }
    },enabled=!working && richPlainText(text,format).isNotBlank() && (destination==ShareDestination.CHAT || (title.isNotBlank() && title.length<=100))){Text(if(working)"Отправляем…"else if(destination==ShareDestination.CHAT)"Отправить"else if(destination==ShareDestination.TASK)"Открыть редактор"else"Создать")}},dismissButton={TextButton(onDismiss){Text("Отмена")}})
}
