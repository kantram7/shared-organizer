package team.kotiki.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun EntryDetailsDialog(entry:Entry,onClose:()->Unit,onEdit:(()->Unit)?,actions:@Composable ()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        EditorSurface(Modifier.fillMaxWidth().fillMaxHeight(.94f).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            shape=MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(entry.isGathering)"Общий сбор" else if(entry.kind==Kind.TASK)"Дело" else "Событие",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    actions()
                    IconButton(onClose,Modifier.semantics{contentDescription="Закрыть событие"}){KotikiIcon(Glyph.CLOSE)}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    DetailField("Название",entry.title)
                    if(entry.note.isNotBlank())DetailField("Описание",entry.note)
                    Text("Участники",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        (if(entry.who==Person.BOTH)listOf(Person.USER_1,Person.USER_2)else listOf(entry.who)).forEach{person->
                            Surface(shape=MaterialTheme.shapes.small,color=participantTint(person)) {
                                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    KotikiIcon(if(person==Person.USER_2)Glyph.CAT_GIRL else Glyph.CAT,Modifier.size(24.dp));Text(personLabel(person))
                                }
                            }
                        }
                    }
                    DetailField("Когда",if(entry.isMultiDay)"${fullDayLabel(entry.date!!)} — ${fullDayLabel(entry.lastDate!!)}" else entry.date?.let(::fullDayLabel)?:"На неделе, без дня")
                    if(entry.start.isNotBlank())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        SuggestionChip({},label={Text("С ${entry.start}")});SuggestionChip({},label={Text("По ${entry.end}")})
                    }
                    Text(when(entry.visibility){Visibility.PRIVATE->"Видно только мне";Visibility.BUSY->"Другому участнику видна только занятость";Visibility.SHARED->if(entry.editable)"Видно обоим · совместное редактирование" else "Видно обоим · редактирует автор"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    content()
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider(Modifier.padding(vertical=10.dp))
                if(onEdit!=null)Button(onEdit,Modifier.fillMaxWidth()){Text("Редактировать")}
                else QuietCloseButton(onClose,Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun DetailField(label:String,value:String) {
    Box(Modifier.fillMaxWidth().padding(top=8.dp)) {
        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.extraSmall,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),color=layeredSurface(if(winterDesignEnabled()).55f else .72f)) {
            Box(Modifier.heightIn(min=64.dp).padding(horizontal=14.dp,vertical=16.dp),contentAlignment=Alignment.CenterStart) {
                LinkText(value,style=MaterialTheme.typography.bodyLarge)
            }
        }
        Text(label,Modifier.padding(start=10.dp).offset(y=(-8).dp).background(MaterialTheme.colorScheme.surface).padding(horizontal=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun EntryReminderSummary(entry:Entry,authUid:String) {
    val context=LocalContext.current
    val prefs=remember(context){ReminderPreferences(context)}
    val preference=remember(authUid,entry.id){prefs.preference(authUid,entry.id)}
    Surface(shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=layeredSurface(.65f)) {
        ReminderHeader(reminderDescription(entry,preference))
    }
}
