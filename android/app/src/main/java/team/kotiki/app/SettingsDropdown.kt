package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class SettingsDropdownOption(val value:String,val label:String,val enabled:Boolean=true)

@Composable internal fun SettingsDropdown(label:String,selected:String,options:List<SettingsDropdownOption>,enabled:Boolean=true,onSelect:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    val selectedLabel=options.firstOrNull{it.value==selected}?.label.orEmpty()
    LaunchedEffect(enabled){if(!enabled)expanded=false}
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth=maxWidth
        OutlinedButton({expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
            .semantics{contentDescription="$label: $selectedLabel"},contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp)) {
            Text(selectedLabel,Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DisclosureIcon(expanded)
        }
        DropdownMenu(expanded && enabled,{expanded=false},modifier=Modifier.width(menuWidth).heightIn(max=320.dp)) {
            options.forEach{option->DropdownMenuItem(text={Text(option.label)},enabled=option.enabled,
                trailingIcon=if(option.value==selected)({Text("✓")})else null,
                onClick={expanded=false;onSelect(option.value)})}
        }
    }
}
