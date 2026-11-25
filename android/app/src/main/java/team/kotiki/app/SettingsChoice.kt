package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsChoice(label:String,selected:Boolean,enabled:Boolean=true,onSelect:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick=onSelect)
        .padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        RadioButton(selected,onClick=null,enabled=enabled,modifier=Modifier.size(24.dp))
        Text(label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .38f))
    }
}
