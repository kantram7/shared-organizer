package team.kotiki.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

internal object PinPolicy {
    const val ITERATIONS=600_000
    fun valid(pin:String)=pin.length in 4..6 && pin.all {it in '0'..'9'}
    fun legacyValid(pin:String)=pin.length in 4..12 && pin.all{it in '0'..'9'}
    fun needsReplacement(length:Int)=length !in 4..6
    fun derive(pin:CharArray,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(pin,salt,ITERATIONS,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}
        finally {spec.clearPassword();pin.fill('\u0000')}
    }
    fun delayMillis(failures:Int):Long = if(failures<5)0 else (30_000L shl (failures-5).coerceAtMost(5)).coerceAtMost(900_000L)
    fun remaining(failures:Int,wall:Long,elapsed:Long,nowWall:Long,nowElapsed:Long):Long {
        val duration=delayMillis(failures)
        // Caller rebases the timestamp once on a device reboot. Wall-clock edits cannot bypass the delay.
        val spent=if(nowElapsed>=elapsed)nowElapsed-elapsed else 0L
        return (duration-spent).coerceAtLeast(0)
    }
}

private fun ByteArray.encoded()=Base64.encodeToString(this,Base64.NO_WRAP)
private fun String.decoded()=Base64.decode(this,Base64.NO_WRAP)
private fun Context.activity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.activity();else->null}

/** Local UI access control, not encryption of Firebase's cache or cloud documents. */
@Stable class AppLockController internal constructor(context:Context,val authUid:String) {
    private val app=context.applicationContext
    private val identity=MessageDigest.getInstance("SHA-256").digest(authUid.toByteArray()).joinToString(""){"%02x".format(it)}
    private val alias="kotiki.lock.$identity"
    private val bioAlias="$alias.biometric"
    private val file=AtomicFile(File(app.noBackupFilesDir,"app-lock-$identity.bin"))
    private val keys get()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private var state=JSONObject()
    private val boot get()=Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT,0)
    private var grantAt=-1L
    private var automaticBiometricAttempted=false
    fun takeAutomaticBiometricAttempt():Boolean {
        if(automaticBiometricAttempted || damaged || !initialized || !enabled || !biometric || unlocked)return false
        automaticBiometricAttempted=true;return true
    }
    private var prompt:CancellationSignal?=null
    var revision by mutableIntStateOf(0);private set
    var unlocked by mutableStateOf(false);private set
    var damaged by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    val initialized get()=state.optBoolean("initialized")
    val enabled get()=state.optBoolean("enabled")
    val pinLength get()=state.optInt("pinLength",0)
    val legacyPin get()=enabled && (pinLength==0 || needsNewPin)
    val needsNewPin get()=state.optBoolean("needsNewPin")
    val biometric get()=state.optBoolean("biometric")

    init {
        try {
            if(file.baseFile.exists()) {
                val bytes=file.readFully()
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,keys.getKey(alias,null) as SecretKey,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
                cipher.updateAAD(authUid.toByteArray())
                state=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
                if(state.optInt("failures")>0 && state.optInt("boot",-1)!=boot){
                    state.put("boot",boot).put("failedElapsed",SystemClock.elapsedRealtime()).put("failedWall",System.currentTimeMillis())
                    persist()
                }
            } else if(keys.containsAlias(alias)) damaged=true
        } catch(_:Exception){damaged=true}
        // This value is deliberately not saveable: a new process always requires verification.
        unlocked=initialized && !enabled && !damaged
    }
    private fun key(name:String,authentication:Boolean=false):SecretKey {
        (keys.getKey(name,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            val spec=KeyGenParameterSpec.Builder(name,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(authentication)
            if(authentication){
                spec.setInvalidatedByBiometricEnrollment(true)
                if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1)
            }
            init(spec.build());generateKey()
        }
    }
    private fun persist() {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(alias))
        cipher.updateAAD(authUid.toByteArray())
        val bytes=cipher.iv+cipher.doFinal(state.toString().toByteArray())
        val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)} catch(e:Exception){file.failWrite(stream);throw e}
        revision++
    }
    fun remainingMillis():Long=PinPolicy.remaining(state.optInt("failures"),state.optLong("failedWall"),state.optLong("failedElapsed"),System.currentTimeMillis(),SystemClock.elapsedRealtime())
    private fun authorize(){grantAt=SystemClock.elapsedRealtime();unlocked=true}
    private fun requireGrant(){check(grantAt>=0 && SystemClock.elapsedRealtime()-grantAt in 0..60_000){"Подтверди текущий PIN ещё раз"}}
    suspend fun verify(pin:String):String? {
        if(busy)return "Проверка уже выполняется"
        if(damaged)return "Не удалось прочитать защиту. Восстанови доступ через аккаунт."
        if(!enabled || !(if(legacyPin)PinPolicy.legacyValid(pin) else PinPolicy.valid(pin)))return if(legacyPin)"Введи прежний PIN" else "Введи PIN из 4–6 цифр"
        if(remainingMillis()>0)return "Слишком много попыток. Подожди немного."
        busy=true
        return try {
            // Persist the attempt before the expensive KDF, so killing the process cannot reset attempts.
            state.put("boot",boot).put("failures",state.optInt("failures")+1).put("failedWall",System.currentTimeMillis()).put("failedElapsed",SystemClock.elapsedRealtime())
            persist()
            val salt=state.getString("salt").decoded()
            val expected=state.getString("verifier").decoded()
            val actual=withContext(Dispatchers.Default){PinPolicy.derive(pin.toCharArray(),salt)}
            val correct=MessageDigest.isEqual(actual,expected);actual.fill(0);expected.fill(0)
            if(correct){
                state.put("failures",0).put("pinLength",pin.length).put("needsNewPin",PinPolicy.needsReplacement(pin.length))
                persist();authorize();null
            }else "Неверный PIN"
        } catch(cancelled:CancellationException){unlocked=false;throw cancelled}
        catch(_:Exception){damaged=true;unlocked=false;"Не удалось проверить PIN. Восстанови доступ через аккаунт."}
        finally {busy=false}
    }
    suspend fun configure(pin:String,confirmation:String,enable:Boolean):String? {
        if(busy)return "Подожди завершения проверки"
        if(damaged)return "Сначала восстанови доступ через аккаунт"
        if(enable && (!PinPolicy.valid(pin) || pin!=confirmation))return "Введи два одинаковых PIN из 4–6 цифр"
        if(initialized && enabled)try{requireGrant()}catch(_:Exception){unlocked=false;return "Подтверди текущий PIN ещё раз"}
        busy=true
        return try {
            if(enable) {
                val salt=ByteArray(32).also{SecureRandom().nextBytes(it)}
                val hash=withContext(Dispatchers.Default){PinPolicy.derive(pin.toCharArray(),salt)}
                state.put("salt",salt.encoded()).put("verifier",hash.encoded()).put("pinLength",pin.length);hash.fill(0)
            }else {state.remove("salt");state.remove("verifier");state.remove("pinLength")}
            state.put("needsNewPin",false).put("initialized",true).put("enabled",enable).put("biometric",false).put("failures",0)
            persist();keys.deleteEntry(bioAlias);authorize();null
        } catch(cancelled:CancellationException){unlocked=false;throw cancelled}
        catch(_:Exception){unlocked=false;damaged=true;"Не удалось сохранить защиту. Восстанови доступ через аккаунт."}
        finally{busy=false}
    }
    fun changeOptions(useBiometric:Boolean):String?=try {
        requireGrant()
        require(!useBiometric || enabled)
        if(!useBiometric){state.put("biometric",false);keys.deleteEntry(bioAlias)}
        persist();null
    }catch(_:Exception){"Подтверди текущий PIN ещё раз"}

    fun authenticateBiometric(context:Context,enroll:Boolean=false,onResult:(String?)->Unit) {
        if(!enabled || damaged || busy)return
        if(enroll)try{requireGrant()}catch(_:Exception){onResult("Сначала подтверди текущий PIN");return}
        if(!enroll && !biometric)return
        val activity=context.activity() ?: run{onResult("Открой приложение для проверки биометрии");return}
        try {
            if(enroll)keys.deleteEntry(bioAlias)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key(bioAlias,true))}
            val signal=CancellationSignal();prompt=signal
            val builder=BiometricPrompt.Builder(activity).setTitle("Войти в Kotiki team")
                .setSubtitle(if(enroll)"Подтверди включение биометрии" else "Подтверди вход по биометрии")
                .setNegativeButton("Ввести PIN",activity.mainExecutor){_,_->prompt=null;onResult(null)}
            if(Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val dialog=builder.build()
            dialog.authenticate(BiometricPrompt.CryptoObject(cipher),signal,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                    try {
                        // A successful callback alone is insufficient: finish the auth-bound crypto operation.
                        val challenge=ByteArray(32).also{SecureRandom().nextBytes(it)}
                        requireNotNull(result.cryptoObject?.cipher).doFinal(challenge)
                        if(enroll)state.put("biometric",true)
                        if(legacyPin)state.put("needsNewPin",true)
                        state.put("failures",0);persist();authorize();onResult(null)
                    }catch(_:Exception){onResult("Биометрия изменилась. Войди по PIN и включи её заново.")}
                    prompt=null
                }
                override fun onAuthenticationError(code:Int,message:CharSequence){prompt=null;onResult(if(code==BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code==BiometricPrompt.BIOMETRIC_ERROR_CANCELED)null else "Биометрия недоступна. Используй PIN.")}
            })
        }catch(_:Exception){onResult("Биометрия недоступна или изменилась. Используй PIN.")}
    }
    // Backgrounding ends the short settings grant, not the authenticated app session.
    fun stopped(){grantAt=-1;prompt?.cancel();prompt=null}
    fun close(){stopped();unlocked=false}
    fun recover(password:String,onResult:(String?)->Unit) {
        if(busy)return
        val user=FirebaseAuth.getInstance().currentUser
        if(user==null || user.uid!=authUid || user.isAnonymous || user.email.isNullOrBlank()){
            onResult("Для восстановления нужен вход в облачный аккаунт с email и паролем.");return
        }
        busy=true
        user.reauthenticate(EmailAuthProvider.getCredential(user.email!!,password)).addOnCompleteListener{task->
            busy=false
            if(!task.isSuccessful || FirebaseAuth.getInstance().currentUser?.uid!=authUid){onResult("Не удалось подтвердить аккаунт. Проверь пароль и интернет.");return@addOnCompleteListener}
            try {
                // Reset local lock only after server-backed reauthentication of the same UID.
                state=JSONObject();damaged=false;keys.deleteEntry(bioAlias);persist();unlocked=false;grantAt=-1
                onResult(null)
            }catch(_:Exception){damaged=true;onResult("Не удалось сбросить локальную защиту")}
        }
    }
}

private val LocalAppLock=staticCompositionLocalOf<AppLockController?>{null}

@Composable fun LockGate(authUid:String,content:@Composable ()->Unit) {
    val context=LocalContext.current
    val lock=remember(authUid){AppLockSessions.get(context,authUid)}
    val screenState=rememberSaveableStateHolder()
    val owner=context.activity() as? androidx.lifecycle.LifecycleOwner
    var foreground by remember {mutableStateOf(true)}
    DisposableEffect(lock,owner){
        val observer=LifecycleEventObserver{_,event->when(event){
            Lifecycle.Event.ON_STOP->{foreground=false;lock.stopped()}
            Lifecycle.Event.ON_START->{foreground=true}
            else->Unit
        }}
        owner?.lifecycle?.addObserver(observer)
        onDispose{owner?.lifecycle?.removeObserver(observer);lock.stopped();context.activity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    lock.revision
    val secureScreen=!foreground || lock.damaged || !lock.initialized || lock.enabled && !lock.unlocked
    DisposableEffect(secureScreen){
        val window=context.activity()?.window
        if(secureScreen)window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    CompositionLocalProvider(LocalAppLock provides lock){
        // Keep launchers and pending OAuth state mounted while external Android UI is open.
        RetainedForegroundContent(foreground) {
            when {
                lock.damaged || lock.initialized && lock.enabled && !lock.unlocked->UnlockPanel(lock,foreground)
                !lock.initialized || lock.needsNewPin->LockSetup(lock)
                else->screenState.SaveableStateProvider(authUid){content()}
            }
        }
    }
}

@Composable internal fun RetainedForegroundContent(foreground:Boolean,content:@Composable ()->Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if(!foreground)Surface(Modifier.fillMaxSize()){}
    }
}

@Composable private fun PinInput(value:String,onChange:(String)->Unit,label:String,maxLength:Int=6) {
    OutlinedTextField(value,{onChange(it.filter{c->c in '0'..'9'}.take(maxLength))},Modifier.fillMaxWidth(),
        label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
}
@Composable private fun UnlockPanel(lock:AppLockController,foreground:Boolean) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var pin by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    var recovery by remember {mutableStateOf(false)}
    var remaining by remember{mutableLongStateOf(lock.remainingMillis())}
    LaunchedEffect(lock){while(true){remaining=lock.remainingMillis();delay(500)}}
    val welcomeVisible=LocalWelcomeVisible.current
    LaunchedEffect(lock,foreground,welcomeVisible) {
        if(foreground && !welcomeVisible){
            delay(250)
            if(lock.takeAutomaticBiometricAttempt())lock.authenticateBiometric(context){error=it}
        }
    }
    fun submit(value:String) { pin="";scope.launch{error=lock.verify(value)} }
    if(!lock.damaged && !lock.legacyPin) {
        PinKeypad(pin,lock.pinLength.coerceIn(4,6),enabled=!lock.busy && remaining==0L,
            biometric=lock.biometric,onDigit={digit->
                if(!lock.busy && remaining==0L) {val next=pin+digit;pin=next;if(next.length==lock.pinLength)submit(next)}
            },onBackspace={pin=pin.dropLast(1)},onBiometric={lock.authenticateBiometric(context){error=it}},
            onRecover={recovery=true},error=if(remaining>0)"Следующая попытка через ${(remaining+999)/1000} с" else error)
    } else Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Kotiki team",style=MaterialTheme.typography.headlineMedium)
        if(lock.damaged)Text("Локальная защита недоступна. Подтверди облачный аккаунт для восстановления.")
        else {
            Text("Один раз введи прежний PIN",style=MaterialTheme.typography.titleLarge)
            Text("После обновления уточним длину кода. Если в нём больше 6 цифр, попросим задать новый. Записи и личные заметки сохранятся.")
            PinInput(pin,{pin=it},"Прежний PIN",12)
            Button({submit(pin)},enabled=!lock.busy && remaining==0L && PinPolicy.legacyValid(pin),modifier=Modifier.fillMaxWidth()){Text("Продолжить")}
            if(lock.biometric)OutlinedButton({lock.authenticateBiometric(context){error=it}},enabled=!lock.busy,modifier=Modifier.fillMaxWidth()){Text("Войти по биометрии")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(remaining>0)Text("Следующая попытка через ${(remaining+999)/1000} с")
        TextButton({recovery=true}){Text("Забыл PIN")}
    }
    if(recovery)RecoveryDialog(lock){recovery=false}
}
@Composable private fun RecoveryDialog(lock:AppLockController,onDismiss:()->Unit){
    var password by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),onDismissRequest={if(!lock.busy)onDismiss()},title={Text("Восстановить доступ")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Подтверди пароль этого Firebase-аккаунта. Затем задай новый PIN. Облачные записи сохранятся.")
        OutlinedTextField(password,{password=it},label={Text("Пароль аккаунта")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=password.isNotEmpty() && !lock.busy,onClick={val attempt=password;password="";lock.recover(attempt){error=it;if(it==null)onDismiss()}}){Text("Подтвердить аккаунт")}},dismissButton={TextButton(onClick=onDismiss,enabled=!lock.busy){Text("Отмена")}})
}
@Composable private fun LockSetup(lock:AppLockController,embedded:Boolean=false,onDone:(()->Unit)?=null){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var pin by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")}
    var enabled by remember{mutableStateOf(true)};var bio by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    Column(Modifier.fillMaxWidth().imePadding().then(if(embedded)Modifier else Modifier.verticalScroll(rememberScrollState())).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(if(lock.needsNewPin)"Задай новый PIN" else if(lock.initialized)"Изменить PIN" else "Защити наше пространство",style=MaterialTheme.typography.titleLarge)
        Text("PIN хранится только на этом устройстве. Это отдельный код, не пароль облачного аккаунта.")
        Row{Checkbox(enabled,{enabled=it});Text("Вход по PIN")}
        if(lock.needsNewPin)Text("Обновлённые правила: только 4–6 цифр. Меняется лишь локальный PIN; аккаунт, записи и заметки сохраняются.")
        if(enabled){
            Text("Только цифры. Длина PIN — от 4 до 6 цифр.",color=MaterialTheme.colorScheme.primary)
            PinInput(pin,{pin=it},"Новый PIN: 4–6 цифр");PinInput(confirm,{confirm=it},"Повтори PIN")
            Row{Checkbox(bio,{bio=it});Text("Вход по биометрии")}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!lock.busy && (!enabled || PinPolicy.valid(pin) && pin==confirm),onClick={
            val first=pin;val second=confirm;pin="";confirm=""
            scope.launch {
                error=lock.configure(first,second,enabled)
                if(error==null){
                    if(enabled && bio)lock.authenticateBiometric(context,true){error=it;onDone?.invoke()}
                    else onDone?.invoke()
                }
            }
        },modifier=Modifier.fillMaxWidth()){Text(if(enabled)"Сохранить PIN" else "Продолжить без PIN")}
    }
}
@Composable fun AppLockSettings(authUid:String){
    val lock=LocalAppLock.current
    if(lock==null || lock.authUid!=authUid){Text("Защита доступна после облачного входа");return}
    lock.revision
    var edit by remember{mutableStateOf(false)};var verified by remember{mutableStateOf(false)}
    var pin by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val context=LocalContext.current
    LaunchedEffect(edit,verified){
        if(edit && verified && lock.enabled){delay(60_000);verified=false;pin="";error="Для продолжения подтверди текущий PIN ещё раз"}
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Защита приложения",style=MaterialTheme.typography.titleMedium)
        Text("PIN: ${if(lock.enabled)"включён" else "выключен"} · Биометрия: ${if(lock.biometric)"включена" else "выключена"}")
        Text("Повторный вход — только после полного закрытия приложения. Сворачивание не блокирует его.")
        OutlinedButton(onClick={edit=true;verified=!lock.enabled;error=null;pin=""}){Text("Настроить защиту")}
    }
    if(edit)AlertDialog(properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),onDismissRequest={if(!lock.busy)edit=false},title={Text("Настройки защиты")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(!verified){
                Text("Для изменений подтверди текущий PIN")
                PinInput(pin,{pin=it},"Текущий PIN",if(lock.legacyPin)12 else 6)
                Button(enabled=!lock.busy && PinPolicy.valid(pin),onClick={val attempt=pin;pin="";scope.launch{error=lock.verify(attempt);if(error==null)verified=true}}){Text("Подтвердить PIN")}
            }else{
                if(lock.enabled){
                    Row{
                        Checkbox(lock.biometric,{checked->if(checked)lock.authenticateBiometric(context,true){error=it} else error=lock.changeOptions(false)})
                        Text("Вход по биометрии")
                    }
                    TextButton(onClick={scope.launch{error=lock.configure("","",false);if(error==null)edit=false}}){Text("Выключить PIN и биометрию")}
                }
                LockSetup(lock,embedded=true){edit=false}
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={QuietCloseButton(onClick={edit=false},enabled=!lock.busy)})
}
