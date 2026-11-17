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
@Composable fun ItemActions(title:String,onDelete:()->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        IconButton({expanded=true},Modifier.semantics{contentDescription="Действия: $title"}){Text("⋮",style=MaterialTheme.typography.titleLarge)}
        DropdownMenu(expanded,{expanded=false}) {
            DropdownMenuItem(text={Text("Удалить",color=MaterialTheme.colorScheme.error)},onClick={expanded=false;onDelete()})
        }
    }
}
