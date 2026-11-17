package team.kotiki.app
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable fun NotificationPermissionSettings() {
    val context=LocalContext.current
    var allowed by remember {mutableStateOf(GatheringNotifications.canPost(context))}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it}
    Text(if(allowed)"Уведомления разрешены" else "Разреши уведомления для приглашений, чатов и напоминаний",style=MaterialTheme.typography.bodySmall)
    OutlinedButton({if(!allowed && android.os.Build.VERSION.SDK_INT>=33)launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text(if(allowed)"Настройки уведомлений Android" else "Разрешить уведомления")}
}
