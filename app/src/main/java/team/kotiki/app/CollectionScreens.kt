package team.kotiki.app

import androidx.compose.foundation.horizontalScroll
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

@Composable fun CollectionsScreen(store:DemoStore,isPlans:Boolean,onHelp:(String)->Unit) {
    val collections = if(isPlans)store.plans else store.lists
    var selectedId by rememberSaveable(isPlans) { mutableStateOf(collections.firstOrNull()?.id ?: "") }
    val selected = collections.find { it.id==selectedId } ?: collections.firstOrNull()
    var dialog by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(if(isPlans)"Наши планы" else "Списки",if(isPlans)"+ Лист" else "+ Список",{dialog="collection"})
        if(isPlans) {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                collections.forEach { c ->
                    Surface(shape=RoundedCornerShape(13.dp),color=if(c.id==selected?.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={selectedId=c.id}) { Text(c.name) }
                            CollectionAccess(c,onHelp)
                        }
                    }
                }
            }
        } else {
            collections.forEach { c ->
                Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),shape=RoundedCornerShape(15.dp),color=if(c.id==selected?.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(5.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={selectedId=c.id},modifier=Modifier.weight(1f)) { Text(c.name,Modifier.fillMaxWidth()) }
                        CollectionAccess(c,onHelp)
                    }
                }
            }
        }
        selected?.let { group ->
            ScreenHeading(group.name,if(isPlans)"+ План" else "+ Пункт",{dialog="item"})
            group.items.forEach { item ->
                Surface(Modifier.fillMaxWidth().padding(bottom=9.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(!isPlans)Checkbox(item.checked,{store.toggleItem(group.id,item.id)})
                        Column(Modifier.weight(1f).padding(5.dp)) {
                            Text(item.title)
                            if(isPlans)Text("Без даты",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CollectionAccess(group,onHelp)
                    }
                }
            }
            if(group.items.isEmpty())Text("Пока пусто. Добавим первое?",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
    }
    dialog?.let { type ->
        NameDialog(if(type=="collection")if(isPlans)"Новый лист планов" else "Новый список" else if(isPlans)"Добавить план" else "Добавить пункт",
            withVisibility=type=="collection",onDismiss={dialog=null}) { name, shared ->
            if(type=="collection") {
                store.addCollection(isPlans,name,shared)
                selectedId=(if(isPlans)store.plans else store.lists).last().id
            } else selected?.let { store.addItem(isPlans,it.id,name) }
            dialog=null
        }
    }
}
@Composable private fun CollectionAccess(c:DemoCollection,onHelp:(String)->Unit) {
    AccessIcon(Entry(c.id,c.name,null,LocalDate.now(),who=if(c.shared)Person.BOTH else Person.USER_1,
        visibility=if(c.shared)Visibility.SHARED else Visibility.PRIVATE),onHelp)
}
@Composable fun NameDialog(title:String,withVisibility:Boolean,onDismiss:()->Unit,onSave:(String,Boolean)->Unit) {
    var name by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={
        Column {
            OutlinedTextField(value=name,onValueChange={name=it.take(100)},label={Text("Название")},singleLine=true)
            if(withVisibility)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(shared,{shared=it});Text("Видно всем")}
        }
    },confirmButton={TextButton(onClick={onSave(name.trim(),shared)},enabled=name.isNotBlank()){Text("Добавить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}
