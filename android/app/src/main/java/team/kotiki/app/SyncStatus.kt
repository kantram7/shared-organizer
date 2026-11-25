package team.kotiki.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun SyncStatus(store:DemoStore) {
    var details by remember{mutableStateOf(false)}
    val pending=store.cloudPending || store.chatPending || store.notes.pending || store.profilePending
    val error=store.cloudError.ifBlank{store.notes.error.ifBlank{store.profileError}}
    val lastTime=maxOf(store.lastSyncedAt,store.notes.lastSyncedAt)
    val label=when {error.isNotEmpty()->"Ошибка связи";pending->"Отправка…";!store.cloudOnline->"Нет сети";else->"Синхронизировано"}
    val time=if(lastTime>0)SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(lastTime)) else "—"
    val detailsModifier=Modifier.padding(end=10.dp).clickable{details=true}
        .semantics{contentDescription="Синхронизация: $label. Последняя: $time"}
    val statusColor=if(error.isEmpty())MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    val synchronized=error.isEmpty() && !pending && store.cloudOnline
    val compact=buildAnnotatedString {
        if(synchronized)withStyle(SpanStyle(fontWeight=FontWeight.Bold)){append("✓")}
        else append(label)
        append(" $time")
    }
    Text(compact,detailsModifier,fontSize=11.sp,lineHeight=14.sp,color=statusColor,
        maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Синхронизация")},
        text={Text("${error.ifBlank{if(pending)"Изменения ожидают отправки" else store.cloudStatus}}\nПоследняя подтверждённая синхронизация: $time")},
        confirmButton={TextButton({details=false}){Text("Понятно")}})
}
