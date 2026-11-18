package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun CatEmpty(message:String,action:String,onAction:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        KotikiIcon(Glyph.CAT,Modifier.size(36.dp))
        Text(message,style=MaterialTheme.typography.bodyMedium)
        TextButton(onAction){Text(action)}
    }
}
@Stable class ItemMenuState {
    var expanded by mutableStateOf(false)
    var confirming by mutableStateOf(false)
}
@Composable fun rememberItemMenu()=remember {ItemMenuState()}
val LocalRecordDelete=staticCompositionLocalOf<(Entry)->Unit>{{}}
val LocalRecordCanDelete=staticCompositionLocalOf<(Entry)->Boolean>{{false}}

@Composable fun ItemActions(title:String,onDelete:(()->Unit)?,state:ItemMenuState=rememberItemMenu(),
    onEdit:(()->Unit)?=null,onOpen:(()->Unit)?=null,
    deleteMessage:String="Запись будет удалена. Продолжить?",compactGear:Boolean=false) {
    if(onDelete==null && onEdit==null && onOpen==null)return
    val actionColor=MaterialTheme.colorScheme.primary
    Box {
        IconButton({state.expanded=true},Modifier.size(if(compactGear)40.dp else 48.dp).semantics{contentDescription="Действия: $title"}) {
            if(compactGear)KotikiIcon(Glyph.SETTINGS,Modifier.size(16.dp)) else androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                val color=actionColor
                listOf(.25f,.5f,.75f).forEach { y -> drawCircle(color,2.dp.toPx(),androidx.compose.ui.geometry.Offset(size.width/2,size.height*y)) }
            }
        }
        DropdownMenu(state.expanded,{state.expanded=false}) {
            onOpen?.let{DropdownMenuItem(text={Text("Подробнее")},onClick={state.expanded=false;it()})}
            onEdit?.let{DropdownMenuItem(text={Text("Редактировать")},onClick={state.expanded=false;it()})}
            if(onDelete!=null)DropdownMenuItem(text={Text("Удалить",color=MaterialTheme.colorScheme.error)},onClick={state.expanded=false;state.confirming=true})
        }
    }
    if(state.confirming && onDelete!=null)AlertDialog(onDismissRequest={state.confirming=false},
        title={Text("Удалить «$title»?")},text={Text(deleteMessage)},
        confirmButton={TextButton({state.confirming=false;onDelete()}){Text("Удалить",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({state.confirming=false}){Text("Отмена")}})
}
@Composable fun RecordActions(entry:Entry,state:ItemMenuState,onOpen:(Entry)->Unit) {
    val delete=LocalRecordDelete.current
    ItemActions(entry.title,if(LocalRecordCanDelete.current(entry)){{delete(entry)}}else null,state,onOpen={onOpen(entry)})
}
