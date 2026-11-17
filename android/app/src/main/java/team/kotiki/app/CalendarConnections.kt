package team.kotiki.app

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Every profile has a separate Keystore key and encrypted no-backup file. Tokens are never persisted here. */
internal class CalendarPreferences(context: Context, private val uid: String) : CalendarLinkStore {
    private val name = "calendar_v1_" + calendarHash(uid)
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.bin"))
    init { require(uid.isNotBlank()) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(name, null) as? SecretKey)?.let { return it }
        if (file.baseFile.exists()) throw CalendarExportException("Защищённые настройки календаря недоступны. Подключи календарь заново после очистки его настроек.")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        if (bytes.size < 29) throw CalendarExportException("Не удалось прочитать защищённые настройки календарей.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(uid.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun write(json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(uid.toByteArray(Charsets.UTF_8)) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun change(action: (JSONObject) -> Unit) = synchronized(lock) { val json = read(); action(json); write(json) }
    fun googleAccount(): String? = synchronized(lock) { read().optString("google").takeIf { it.isNotBlank() } }
    fun setGoogle(account: String) = change { it.put("google", account) }
    fun forgetGoogle() = change { json ->
        json.remove("google")
        json.optJSONObject("links")?.let { links -> links.keys().asSequence().toList().filter { !links.getJSONObject(it).getString("id").startsWith("https://") }.forEach { links.remove(it) } }
    }
    fun yandex(): YandexCalendarConnection? = synchronized(lock) { read().optJSONObject("yandex")?.let {
        YandexCalendarConnection(it.getString("username"), it.getString("password"), it.getString("url"), it.getString("label"))
    } }
    fun setYandex(value: YandexCalendarConnection) = change { it.put("yandex", JSONObject().put("username", value.username).put("password", value.password).put("url", value.calendarUrl).put("label", value.label)) }
    fun forgetYandex() = change { json ->
        json.remove("yandex")
        json.optJSONObject("links")?.let { links -> links.keys().asSequence().toList().filter { links.getJSONObject(it).getString("id").startsWith("https://") }.forEach { links.remove(it) } }
    }
    fun zone(): ZoneId = synchronized(lock) { read().optString("zone").takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: ZoneId.systemDefault() }
    fun setZone(zone: String) = change { if(zone.isBlank()) it.remove("zone") else it.put("zone", ZoneId.of(zone).id) }
    override fun link(key: String): CalendarLink? = synchronized(lock) { read().optJSONObject("links")?.optJSONObject(key)?.let {
        CalendarLink(it.getString("id"), it.optString("etag"), it.optString("pending"), it.getString("stamp"))
    } }
    override fun putLink(key: String, link: CalendarLink) = change { json ->
        val links = json.optJSONObject("links") ?: JSONObject().also { json.put("links", it) }
        links.put(key, JSONObject().put("id", link.externalId).put("etag", link.etag).put("pending", link.pendingBody).put("stamp", link.stamp))
    }
    companion object { private val lock = Any() }
}

private fun calendarUiError(error: Throwable): String = when(error) {
    is CalendarExportException -> error.message ?: "Не удалось обратиться к календарю."
    is java.io.IOException -> "Нет соединения с календарём. Проверь интернет и повтори."
    is java.security.GeneralSecurityException -> "Не удалось открыть защищённые настройки календарей."
    else -> "Не удалось подключить календарь. Проверь настройки доступа и повтори."
}
private val calendarExportMutex = Mutex()

/** Returns an action that authorizes ONLY the explicitly picked Google account. */
@Composable
private fun rememberGoogleAuthorization(onToken: (String, String) -> Unit, onError: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val tokenCallback by rememberUpdatedState(onToken); val errorCallback by rememberUpdatedState(onError)
    var requestedAccount by remember { mutableStateOf<String?>(null) }
    fun deliver(result: AuthorizationResult) {
        val account = requestedAccount
        val token = result.accessToken
        if (account.isNullOrBlank() || token.isNullOrBlank() || GOOGLE_CALENDAR_SCOPE !in result.grantedScopes) {
            errorCallback("Разрешение на Google Календарь не получено."); return
        }
        tokenCallback(account, token)
    }
    val resolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { response ->
        if (response.resultCode != Activity.RESULT_OK || response.data == null) errorCallback("Подключение Google отменено.")
        else try { deliver(client.getAuthorizationResultFromIntent(response.data!!)) } catch (_: Exception) { errorCallback("Google не подтвердил разрешение. Подключи аккаунт снова.") }
    }
    return { account ->
        requestedAccount = account
        val request = AuthorizationRequest.builder().setAccount(Account(account, "com.google"))
            .setRequestedScopes(listOf(Scope(GOOGLE_CALENDAR_SCOPE))).build()
        client.authorize(request).addOnSuccessListener { result ->
            if (result.hasResolution()) {
                val intent = result.pendingIntent
                if (intent == null) errorCallback("Не удалось открыть согласие Google.")
                else runCatching { resolution.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                    .onFailure { errorCallback("Не удалось открыть согласие Google.") }
            } else deliver(result)
        }.addOnFailureListener { errorCallback("Google Календарь не подключён. Проверь аккаунт, интернет и настройку OAuth приложения.") }
    }
}

/** Wire below the saved event details/editor. Pass the currently signed-in profile UID, never a global constant. */
@Composable
fun CalendarExportActions(entry: Entry, authUid: String) {
    if (authUid.isBlank()) { Text("Войди в аккаунт, чтобы подключить календарь."); return }
    key(authUid, entry.id) {
        val context = LocalContext.current
        val preferences = remember { CalendarPreferences(context, authUid) }
        val scope = rememberCoroutineScope()
        var google by remember { mutableStateOf<String?>(null) }
        var yandex by remember { mutableStateOf<YandexCalendarConnection?>(null) }
        var googleLinked by remember { mutableStateOf(false) }; var yandexLinked by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
        var settings by remember { mutableStateOf(false) }; var refresh by remember { mutableIntStateOf(0) }
        var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
        LaunchedEffect(refresh) {
            try {
                withContext(Dispatchers.IO) {
                    google = preferences.googleAccount(); yandex = preferences.yandex(); zone = preferences.zone()
                    googleLinked = google?.let { preferences.link(calendarLinkKey(CalendarProvider.GOOGLE, it, entry.id)) != null } ?: false
                    yandexLinked = yandex?.let { preferences.link(calendarLinkKey(CalendarProvider.YANDEX, it.username.lowercase(java.util.Locale.ROOT) + "|" + it.calendarUrl.trimEnd('/') + "/", entry.id)) != null } ?: false
                }
            } catch (e: Exception) { message = calendarUiError(e) }
        }
        fun export(provider: CalendarProvider, account: String = "", token: String = "") {
            scope.launch {
                try {
                    message = withContext(Dispatchers.IO) { calendarExportMutex.withLock {
                        val exporter = CalendarExporter(CalendarNetwork(), preferences)
                        if (provider == CalendarProvider.GOOGLE) exporter.google(entry, account, token, zone)
                        else exporter.yandex(entry, requireNotNull(yandex), zone)
                    } }
                    refresh++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (provider == CalendarProvider.GOOGLE && (e as? CalendarExportException)?.status == 401)
                        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
                    message = calendarUiError(e)
                } finally { busy = false }
            }
        }
        val authorize = rememberGoogleAuthorization(onToken = { account, token -> export(CalendarProvider.GOOGLE, account, token) }, onError = { message = it; busy = false })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Копия в моём календаре", style = MaterialTheme.typography.titleSmall)
            Text("Изменения отправляются только по кнопке. Часовой пояс: ${zone.id}.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && entry.date != null, onClick = {
                if (google == null) settings = true else { busy = true; message = null; authorize(google!!) }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (googleLinked) "Изменить в календаре · Google" else "Google Календарь") }
            OutlinedButton(enabled = !busy && entry.date != null, onClick = {
                if (yandex == null) settings = true else { busy = true; message = null; export(CalendarProvider.YANDEX) }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (yandexLinked) "Изменить в календаре · Яндекс" else "Яндекс Календарь") }
            if (entry.date == null) Text("Для экспорта выбери день.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (settings) AlertDialog(onDismissRequest = { settings = false; refresh++ }, title = { Text("Подключение календарей") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { CalendarConnectionSettings(authUid) }
        }, confirmButton = { TextButton(onClick = { settings = false; refresh++ }) { Text("Готово") } })
    }
}

@Composable
fun CalendarConnectionSettings(authUid: String) {
    if (authUid.isBlank()) { Text("Календари доступны после входа."); return }
    key(authUid) {
        val context = LocalContext.current
        val preferences = remember { CalendarPreferences(context, authUid) }; val scope = rememberCoroutineScope()
        var google by remember { mutableStateOf<String?>(null) }; var yandexLabel by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
        var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
        var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
        var calendars by remember { mutableStateOf<List<DavCalendar>>(emptyList()) }
        var discoveredUsername by remember { mutableStateOf("") }; var discoveredPassword by remember { mutableStateOf("") }
        var yandexForm by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try { withContext(Dispatchers.IO) { google = preferences.googleAccount(); yandexLabel = preferences.yandex()?.let { "${it.username} · ${it.label}" }; zone = preferences.zone().id } }
            catch(e: Exception) { message = calendarUiError(e) }
        }
        fun localChange(action: () -> Unit) { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { action() } }
            catch(e: Exception) { message = calendarUiError(e) }
            finally { busy = false }
        } }
        val authorize = rememberGoogleAuthorization(onToken = { account, _ ->
            localChange { preferences.setGoogle(account); google = account; message = "Google подключён. Экспорт выполняется из карточки события." }
        }, onError = { message = it; busy = false })
        val chooseAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val account = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (result.resultCode == Activity.RESULT_OK && !account.isNullOrBlank() && type == "com.google") authorize(account)
            else { busy = false; message = "Выбор аккаунта отменён." }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Календари", style = MaterialTheme.typography.titleMedium)
            Text("Это отдельное подключение. Аккаунт Google может отличаться от аккаунта входа в приложение.", style = MaterialTheme.typography.bodySmall)
            google?.let { Text("Google: $it") }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; message = null
                runCatching { chooseAccount.launch(AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder().setAllowableAccountsTypes(listOf("com.google")).build())) }
                    .onFailure { busy = false; message = "Для подключения Google нужны Google Play services и аккаунт на устройстве." }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (google == null) "Подключить Google" else "Выбрать другой Google аккаунт") }
            if (google != null) TextButton(enabled = !busy, onClick = {
                busy = true
                val request = RevokeAccessRequest.builder().setAccount(Account(google!!, "com.google")).setScopes(listOf(Scope(GOOGLE_CALENDAR_SCOPE))).build()
                Identity.getAuthorizationClient(context).revokeAccess(request).addOnSuccessListener {
                    localChange { preferences.forgetGoogle(); google = null; message = "Google отключён. Созданные копии остались в календаре." }
                }.addOnFailureListener { busy = false; message = "Не удалось отозвать доступ Google. Проверь соединение." }
            }) { Text("Отключить Google") }
            HorizontalDivider()
            yandexLabel?.let { Text("Яндекс: $it") }
            OutlinedButton(enabled = !busy, onClick = { yandexForm = !yandexForm; password = ""; calendars = emptyList(); discoveredPassword = "" }, modifier = Modifier.fillMaxWidth()) { Text(if(yandexLabel == null) "Подключить Яндекс" else "Настроить Яндекс") }
            if (yandexLabel != null) TextButton(enabled = !busy, onClick = { localChange { preferences.forgetYandex(); yandexLabel = null; message = "Яндекс отключён. Пароль приложения можно отозвать в Яндекс ID." } }) { Text("Отключить Яндекс") }
            if (yandexForm) {
                Text("В Яндекс ID → Безопасность → Пароли приложений создай пароль категории «Календарь». Введи его только здесь.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(username, { username = it; calendars = emptyList(); discoveredPassword = "" }, label = { Text("Логин Яндекса") }, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; calendars = emptyList(); discoveredPassword = "" }, label = { Text("Пароль приложения") }, enabled = !busy, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && username.isNotBlank() && password.isNotBlank(), onClick = {
                    busy = true; message = null
                    val login = username.trim(); val secret = password
                    scope.launch {
                        try {
                            calendars = withContext(Dispatchers.IO) { discoverYandexCalendars(CalendarNetwork(), login, secret) }
                            discoveredUsername = login; discoveredPassword = secret; password = ""
                            message = "Выбери календарь для копий событий."
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { message = calendarUiError(e) }
                        finally { busy = false }
                    }
                }) { Text("Найти календари") }
                calendars.forEach { calendar -> OutlinedButton(enabled = !busy, onClick = {
                    localChange {
                        preferences.setYandex(YandexCalendarConnection(discoveredUsername, discoveredPassword, calendar.url, calendar.label))
                        yandexLabel = "$discoveredUsername · ${calendar.label}"; discoveredPassword = ""; password = ""; calendars = emptyList(); yandexForm = false
                        message = "Яндекс подключён. Экспорт выполняется из карточки события."
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(calendar.label) } }
            }
            HorizontalDivider()
            Text("Часовой пояс экспорта: $zone", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { localChange { preferences.setZone(""); zone = preferences.zone().id } }) { Text("Как на устройстве") }
            TextButton(enabled = !busy, onClick = { localChange { preferences.setZone("Europe/Moscow"); zone = "Europe/Moscow" } }) { Text("Москва · Europe/Moscow") }
            Text("Обратно события не импортируются. Удаление в приложении не удаляет копию из внешнего календаря.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
