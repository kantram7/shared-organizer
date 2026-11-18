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
    Column(Modifier.widthIn(min=96.dp).padding(end=10.dp).clickable{details=true}
        .semantics{contentDescription="Синхронизация: $label. Последняя: $time"},horizontalAlignment=Alignment.End) {
        Text(label,fontSize=10.sp,lineHeight=13.sp,color=if(error.isEmpty())MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        Text(time,fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("Синхронизация")},
        text={Text("${error.ifBlank{if(pending)"Изменения ожидают отправки" else store.cloudStatus}}\nПоследняя подтверждённая синхронизация: $time")},
        confirmButton={TextButton({details=false}){Text("Понятно")}})
}
