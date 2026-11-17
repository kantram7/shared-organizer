package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun CollectionsScreen(store:DemoStore,isPlans:Boolean,onHelp:(String)->Unit) {
    val available=(if(isPlans)store.plans else store.lists).filter{it.shared || (!isPlans && it.owner==store.viewer)}
    // Snapshot order on entry: editing an open theme never moves it under the finger.
    var order by remember {mutableStateOf(available.sortedByDescending{it.updatedAt}.map{it.id})}
    val collections=available.sortedBy {order.indexOf(it.id).let {index->if(index<0)Int.MAX_VALUE else index}}
    var selectedId by rememberSaveable(isPlans){mutableStateOf(collections.firstOrNull()?.id ?: "")}
    val selected=collections.find{it.id==selectedId} ?: collections.firstOrNull()
    var dialog by rememberSaveable {mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf<DemoCollection?>(null)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(if(isPlans)"Наши планы" else "Списки",if(isPlans)"+ Тема" else "+ Список",{dialog="collection"})
        CollectionTopics(collections,selected?.id,!isPlans,onHelp){selectedId=it}
        selected?.let {group->
            ScreenHeading(group.name,if(isPlans)"+ План" else "+ Пункт",{dialog="item"})
            FlowRow {
                TextButton({dialog="rename"}){Text("Переименовать")}
                TextButton({deleting=group}){Text(if(isPlans)"Удалить тему" else "Удалить список",color=MaterialTheme.colorScheme.error)}
            }
            group.items.forEach {item->
                Surface(Modifier.fillMaxWidth().padding(bottom=7.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(!isPlans)Checkbox(item.checked,{store.toggleItem(group.id,item.id)})
                        Text(item.title,Modifier.weight(1f).padding(vertical=12.dp))
                        if(!isPlans)CollectionAccess(group,onHelp)
                        ItemActions(item.title){store.deleteItem(isPlans,group.id,item.id)}
                    }
                }
            }
            if(group.items.isEmpty())CatEmpty(if(isPlans)"Здесь будут ваши планы" else "Список пока пуст",if(isPlans)"Добавить план" else "Добавить пункт"){dialog="item"}
        } ?: CatEmpty(if(isPlans)"Место для ваших идей" else "Здесь будут ваши списки",if(isPlans)"Создать тему" else "Создать список"){dialog="collection"}
        Spacer(Modifier.height(24.dp))
    }
    dialog?.let {type->
        NameDialog(title=when(type){"collection"->if(isPlans)"Новая тема планов" else "Новый список";"rename"->"Переименовать";else->if(isPlans)"Добавить план" else "Добавить пункт"},
            withVisibility=type=="collection" && !isPlans,initial=if(type=="rename")selected?.name ?: "" else "",onDismiss={dialog=null}) {name,shared->
            when(type) {
                "collection"->{store.addCollection(isPlans,name,shared);selectedId=(if(isPlans)store.plans else store.lists).last().id;order=listOf(selectedId)+order}
                "rename"->selected?.let{store.renameCollection(isPlans,it.id,name)}
                else->selected?.let{store.addItem(isPlans,it.id,name)}
            };dialog=null
        }
    }
    deleting?.let {group->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить «${group.name}»?")},
        text={Text("Будут удалены все записи внутри: ${group.items.size}.${if(group.shared)" Содержимое предназначено для обоих участников." else ""}")},
        confirmButton={TextButton({store.deleteCollection(isPlans,group.id);deleting=null}){Text("Удалить")}},
        dismissButton={TextButton({deleting=null}){Text("Отмена")}})}
}

@Composable private fun CollectionTopics(collections:List<DemoCollection>,selectedId:String?,showAccess:Boolean,onHelp:(String)->Unit,onSelect:(String)->Unit) {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=MaterialTheme.typography.labelLarge
    var menuExpanded by remember {mutableStateOf(false)}
    var showAll by rememberSaveable {mutableStateOf(false)}
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available=constraints.maxWidth
        val gap=with(density){6.dp.roundToPx()}
        val widths=collections.map {c->
            val padding=with(density){(48.dp+if(showAccess){if(c.shared)56.dp else 44.dp}else 0.dp).roundToPx()}
            (measurer.measure(c.name,style=style,maxLines=1).size.width+padding).coerceAtMost(available)
        }
        var row=0;var occupied=0;var count=0
        for(width in widths) {
            if(occupied>0 && occupied+gap+width>available){row++;occupied=0}
            if(row>=2)break
            occupied+=width+if(occupied>0)gap else 0;count++
        }
        val visible=if(showAll)collections else collections.take(count)
        val hidden=collections.drop(count)
        Column {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                visible.forEachIndexed {i,c->
                    FilterChip(c.id==selectedId,{onSelect(c.id)},modifier=Modifier.width(with(density){widths[i].toDp()}),
                        label={Text(c.name,maxLines=1,overflow=TextOverflow.Ellipsis)},
                        trailingIcon=if(showAccess){{CollectionAccess(c,onHelp)}}else null)
                }
            }
            if(hidden.isNotEmpty())Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton({menuExpanded=true}){Text("Ещё (${hidden.size}) ▾",maxLines=1,overflow=TextOverflow.Ellipsis)}
                    DropdownMenu(menuExpanded,{menuExpanded=false}) {
                        hidden.forEach {c->DropdownMenuItem(text={Text(c.name)},onClick={onSelect(c.id);menuExpanded=false},
                            trailingIcon=if(showAccess){{CollectionAccess(c,onHelp)}}else null)}
                    }
                }
                TextButton({showAll=!showAll;menuExpanded=false}){Text(if(showAll)"Свернуть" else "Развернуть")}
            }
        }
    }
}
@Composable private fun CollectionAccess(c:DemoCollection,onHelp:(String)->Unit) {
    AccessIcon(Entry(c.id,c.name,null,LocalDate.now(),who=if(c.shared)Person.BOTH else c.owner,owner=c.owner,
        visibility=if(c.shared)Visibility.SHARED else Visibility.PRIVATE),onHelp)
}
@Composable fun NameDialog(title:String,withVisibility:Boolean,onDismiss:()->Unit,initial:String="",onSave:(String,Boolean)->Unit) {
    var name by rememberSaveable {mutableStateOf(initial)}
    var shared by rememberSaveable {mutableStateOf(true)}
    val focus=remember {FocusRequester()}
    LaunchedEffect(Unit){focus.requestFocus()}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column {
        OutlinedTextField(name,{name=it.take(100)},Modifier.fillMaxWidth().focusRequester(focus),label={Text("Название")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text,imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={if(name.isNotBlank())onSave(name.trim(),shared)}))
        if(withVisibility)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(shared,{shared=it});Text("Видно всем")}
    }},confirmButton={TextButton({onSave(name.trim(),shared)},enabled=name.isNotBlank()){Text(if(initial.isEmpty())"Добавить" else "Сохранить")}},
        dismissButton={TextButton(onDismiss){Text("Отмена")}})
}
