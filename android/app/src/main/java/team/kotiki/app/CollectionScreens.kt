package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun CollectionsScreen(store:DemoStore,isPlans:Boolean,onPlanChat:(ChatTarget)->Unit={},onHelp:(String)->Unit) {
    val available=(if(isPlans)store.plans else store.lists).filter{it.shared || (!isPlans && it.owner==store.viewer)}
    // Snapshot order on entry: editing an open theme never moves it under the finger.
    var order by remember {mutableStateOf(available.sortedByDescending{it.updatedAt}.map{it.id})}
    val collections=available.sortedBy {order.indexOf(it.id).let {index->if(index<0)Int.MAX_VALUE else index}}
    var selectedId by rememberSaveable(isPlans){mutableStateOf(collections.firstOrNull()?.id ?: "")}
    val selected=collections.find{it.id==selectedId} ?: collections.firstOrNull()
    var dialog by rememberSaveable {mutableStateOf<String?>(null)}
    val groupActions=rememberItemMenu()
    var editingItem by remember {mutableStateOf<CollectionItem?>(null)}
    val scroll=rememberScrollState()
    var viewport by remember{mutableStateOf<Rect?>(null)}
    CompositionLocalProvider(LocalReorderViewport provides ReorderViewport({viewport}){scroll.scrollBy(it)}) {
    Column(Modifier.fillMaxSize().onGloballyPositioned{viewport=it.boundsInWindow()}.verticalScroll(scroll)) {
        ScreenHeading(if(isPlans)"Наши планы" else "Списки",if(isPlans)"+ Тема" else "+ Список",{dialog="collection"})
        CollectionTopics(collections,selected?.id,!isPlans,onHelp,onActions={selectedId=it;groupActions.expanded=true}){selectedId=it}
        selected?.let {group->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(group.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton({dialog="item"}){Text(if(isPlans)"+ План" else "+ Пункт")}
                ItemActions(group.name,if(store.cloudSession==null || group.owner==store.viewer){{store.deleteCollection(isPlans,group.id)}}else null,groupActions,onEdit={dialog="rename"},
                    deleteMessage="Будут удалены все записи внутри: ${group.items.size}.")
            }
            ReorderColumn(store.manualOrder,"plans/${group.id}",group.items,{it.id}) {item->
                val actions=rememberItemMenu()
                fun open(){if(isPlans)onPlanChat(ChatTarget("plan_${group.id}_${item.id}",item.title,group.id,item.id)) else editingItem=item}
                Surface(Modifier.fillMaxWidth().padding(bottom=7.dp).winterIcicles(item.id,if(item.id==store.manualOrder.sorted("plans/${group.id}",group.items,{it.id}).lastOrNull()?.id)6.dp else 0.dp).clickable{open()},shape=RoundedCornerShape(12.dp),color=winterSurface(MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(!isPlans)Checkbox(item.checked,{store.toggleItem(group.id,item.id)})
                        LinkText(item.title,format=item.titleFormat,modifier=Modifier.weight(1f).padding(vertical=12.dp))
                        if(!isPlans)CollectionAccess(group,onHelp)
                        ItemActions(item.title,{store.deleteItem(isPlans,group.id,item.id)},actions,
                            onEdit={editingItem=item},onOpen=if(isPlans){{open()}}else null)
                    }
                }
            }
            if(group.items.isEmpty())CatEmpty(if(isPlans)"Здесь будут ваши планы" else "Список пока пуст",if(isPlans)"Добавить план" else "Добавить пункт"){dialog="item"}
        } ?: CatEmpty(if(isPlans)"Место для ваших идей" else "Здесь будут ваши списки",if(isPlans)"Создать тему" else "Создать список"){dialog="collection"}
        Spacer(Modifier.height(24.dp))
    }
    }
    editingItem?.let {opened->selected?.let {group->
        PlanEditor(opened,onDismiss={editingItem=null},isPlan=isPlans,sourceLink=sourceDeepLink("plan",group.id,opened.id)){title,description->
            store.updateItem(isPlans,group.id,opened.id,title,description);editingItem=null
        }
    }}
    if(dialog=="item" && isPlans)PlanEditor(null,onDismiss={dialog=null}){title,description->
        selected?.let{store.addItem(true,it.id,title,description)};dialog=null
    }
    dialog?.takeUnless{it=="item" && isPlans}?.let {type->
        NameDialog(title=when(type){"collection"->if(isPlans)"Новая тема планов" else "Новый список";"rename"->"Переименовать";else->if(isPlans)"Добавить план" else "Добавить пункт"},
            withVisibility=type=="collection" && !isPlans,initial=if(type=="rename")selected?.name ?: "" else "",onDismiss={dialog=null}) {name,shared->
            when(type) {
                "collection"->{store.addCollection(isPlans,name,shared);selectedId=(if(isPlans)store.plans else store.lists).last().id;order=listOf(selectedId)+order}
                "rename"->selected?.let{store.renameCollection(isPlans,it.id,name)}
                else->selected?.let{store.addItem(isPlans,it.id,name)}
            };dialog=null
        }
    }

}

@Composable internal fun CollectionTopics(collections:List<DemoCollection>,selectedId:String?,showAccess:Boolean,onHelp:(String)->Unit,onActions:(String)->Unit,onSelect:(String)->Unit) {
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
                    Surface(Modifier.width(with(density){widths[i].toDp()}).padding(vertical=4.dp)
                        .clickable{onSelect(c.id)},
                        shape=RoundedCornerShape(10.dp),color=if(c.id==selectedId)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.heightIn(min=40.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(c.name,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=style)
                            if(showAccess)CollectionAccess(c,onHelp)
                        }
                    }
                }
            }
            if(hidden.isNotEmpty())Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton({menuExpanded=true}){Text("Ещё (${hidden.size})",maxLines=1,overflow=TextOverflow.Ellipsis);Spacer(Modifier.width(4.dp));DisclosureIcon(menuExpanded)}
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

@Composable fun PlanEditor(initial:CollectionItem?,onDismiss:()->Unit,isPlan:Boolean=true,sourceLink:String?=null,onSave:(String,String)->Unit) {
    var title by rememberSaveable(initial?.id){mutableStateOf(initial?.title ?: "")}
    var titleFormat by rememberSaveable(initial?.id){mutableStateOf(initial?.titleFormat ?: "markdown-v1")}
    var descriptionFormat by rememberSaveable(initial?.id){mutableStateOf(initial?.descriptionFormat ?: "markdown-v1")}
    var description by rememberSaveable(initial?.id){mutableStateOf(initial?.description ?: "")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(initial==null)"Новый план" else if(isPlan)"Редактировать план" else "Редактировать пункт")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            RichTextEditor(title,{title=it;titleFormat="markdown-v1"},Modifier.fillMaxWidth(),format=titleFormat,sourceLink=sourceLink,label="Название",titleOnly=true,minLines=1,maxLines=2,maxLength=100)
            if(isPlan)RichTextEditor(description,{description=it;descriptionFormat="markdown-v1"},Modifier.fillMaxWidth(),format=descriptionFormat,sourceLink=sourceLink,label="Описание",minLines=3,maxLines=8,maxLength=4000)
            EditorLinks(title+"\n"+description)
        }},confirmButton={TextButton({onSave(if(titleFormat=="plain-v1")RichTextCodec.escape(title.trim())else title.trim(),if(descriptionFormat=="plain-v1")RichTextCodec.escape(description.trim())else description.trim())},enabled=title.isNotBlank()){Text("Сохранить")}},
        dismissButton={TextButton(onDismiss){Text("Отмена")}})
}
