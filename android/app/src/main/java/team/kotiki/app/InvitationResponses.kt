package team.kotiki.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun QuietCloseButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    OutlinedButton(onClick,modifier,enabled=enabled,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Text("Закрыть")}
}
@Composable internal fun InvitationResponses(entry:Entry,viewer:Person,onAccept:()->Unit,onDecline:()->Unit,onPropose:()->Unit) {
    if(entry.canAccept(viewer))Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onAccept,Modifier.weight(1f).fillMaxHeight().semantics{contentDescription="Принять приглашение «${entry.title}»"},contentPadding=PaddingValues(horizontal=10.dp,vertical=10.dp)) {
            Text(if(entry.proposal!=null)"Подтвердить изменения" else "Принять",textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
        OutlinedButton(onDecline,Modifier.weight(1f).fillMaxHeight(),contentPadding=PaddingValues(horizontal=10.dp,vertical=10.dp),
            colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error,containerColor=MaterialTheme.colorScheme.errorContainer.copy(alpha=.25f)),
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.3f))) {
            Text(if(entry.proposal!=null)"Отклонить изменения" else "Отказаться",textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    if(entry.invite in listOf(InviteState.PENDING,InviteState.ACCEPTED) && entry.visibility==Visibility.SHARED)
        OutlinedButton(onPropose,Modifier.fillMaxWidth()){Text("Предложить изменения")}
}

@Composable internal fun ProfileNameRow(name:String,onName:(String)->Unit,pending:Boolean,canSave:Boolean,onSave:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Отображаемое имя",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min=40.dp).height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Surface(Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(4.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.4f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline)) {
                Box(Modifier.padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.CenterStart) {
                    BasicTextField(name,onName,Modifier.fillMaxWidth().semantics{contentDescription="Отображаемое имя"},singleLine=true,
                        textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),enabled=!pending,
                        cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
                }
            }
            OutlinedButton(onSave,Modifier.fillMaxHeight(),enabled=canSave,contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
                Text(if(pending)"Сохраняем…" else "Изменить")
            }
        }
    }
}
