package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

val LocalCloudMode=staticCompositionLocalOf{false}

@Composable fun CloudGate(notificationTarget:GatheringNotificationTarget?,onNotificationHandled:()->Unit) {
    val context=LocalContext.current
    val configured=remember{FirebaseApp.getApps(context).isNotEmpty()}
    var demo by rememberSaveable {mutableStateOf(!configured)}
    if(demo) {
        var store by remember{mutableStateOf(DemoStore(context))}
        CompositionLocalProvider(LocalDemoIdentity provides store.identity,LocalCloudMode provides false) {
            KotikiTheme(store.theme) {
                Column {
                    if(configured)TextButton({demo=false}){Text("Войти в облачный аккаунт")}
                    Box(Modifier.weight(1f)){KotikiApp(store,notificationTarget,onNotificationHandled){person->
                        context.getSharedPreferences("local_test_profiles",0).edit().putString("selected",person.name).apply()
                        store=DemoStore(context,person)
                    }}
                }
            }
        }
        return
    }
    val auth=remember{FirebaseAuth.getInstance()}
    val db=remember{FirebaseFirestore.getInstance()}
    var user by remember{mutableStateOf(auth.currentUser)}
    var session by remember{mutableStateOf<CloudSession?>(null)}
    var accessMessage by remember{mutableStateOf("Проверяем доступ…")}
    var retry by remember{mutableIntStateOf(0)}
    DisposableEffect(auth){
        val listener=FirebaseAuth.AuthStateListener{AppLockSessions.retainFor(it.currentUser?.uid);user=it.currentUser}
        auth.addAuthStateListener(listener)
        onDispose{auth.removeAuthStateListener(listener)}
    }
    DisposableEffect(user?.uid,retry) {
        session=null;accessMessage="Проверяем доступ…"
        var membership:ListenerRegistration?=null
        var workspace:ListenerRegistration?=null
        val uid=user?.uid
        var active=true
        if(uid!=null)membership=db.collection("members").document(uid).addSnapshotListener{member,error->
            if(!active)return@addSnapshotListener
            workspace?.remove();workspace=null;session=null
            val spaceId=member?.getString("spaceId")
            val canonicalUid=member?.getString("canonicalUid") ?: uid
            if(error!=null || spaceId==null){accessMessage="Аккаунт ещё не подключён к вашему пространству. Доступ добавляет владелец проекта."}
            else workspace=db.collection("spaces").document(spaceId).addSnapshotListener spaceListener@{space,spaceError->
                if(!active)return@spaceListener
                session=runCatching {
                    require(spaceError==null && space!=null && space.exists())
                    @Suppress("UNCHECKED_CAST") val roles=(space!!.get("roles") as Map<String,String>).mapValues{Person.valueOf(it.value)}
                    @Suppress("UNCHECKED_CAST") val uids=space.get("uids") as List<String>
                    @Suppress("UNCHECKED_CAST") val rawNames=space.get("names") as Map<String,String>
                    require(uids.size==2 && uids.toSet().size==2 && roles.keys==uids.toSet() && roles.values.toSet()==setOf(Person.USER_1,Person.USER_2) && canonicalUid in uids)
                    CloudSession(canonicalUid,spaceId,roles.getValue(canonicalUid),uids,roles,roles.values.associateWith{rawNames[it.name] ?: it.label},authUid=uid,
                        actorRoles=roles + ((space.get("actors") as? Map<*,*>)?.entries?.mapNotNull {entry->
                            val person=runCatching{Person.valueOf(entry.value as String)}.getOrNull()
                            if(entry.key is String && person!=null)(entry.key as String) to person else null
                        }?.toMap() ?: emptyMap()))
                }.getOrNull()
                if(session==null)accessMessage="Нет доступа к пространству или оно ещё не настроено."
            }
        }
        onDispose{active=false;membership?.remove();workspace?.remove()}
    }
    val current=session?.takeIf{it.authUid==user?.uid}
    KotikiTheme("system") {
        if(user==null)LoginScreen(auth,{demo=true})
        else if(current==null)Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                BrandMark(Modifier.size(100.dp))
                Text(accessMessage)
                Text(user?.email ?: "",style=MaterialTheme.typography.bodySmall)
                if(BuildConfig.AGENT_TEST)Text("Тестовый UID: ${user?.uid}",style=MaterialTheme.typography.bodySmall)
                Button({retry++}){Text("Проверить снова")}
                TextButton({auth.signOut()}){Text("Выйти из аккаунта")}
            }
        } else key(current.authUid,current.spaceId) {
            val store=remember{DemoStore(context,cloudSession=current)}
            val sync=remember{CloudSync(store,current,db)}
            val chats=remember{CloudChats(current){store.chatPending=it}}
            LaunchedEffect(current.authUid){AndroidPush.register(context,current)}
            val reminderEntries=store.entries.toList()
            LaunchedEffect(reminderEntries,store.cloudReady) {
                if(store.cloudReady)kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    LocalReminders.sync(context,current.authUid,reminderEntries,current.viewer)
                }
            }
            var logoutPending by remember{mutableStateOf(false)}
            var logoutError by remember{mutableStateOf(false)}
            fun logout() {
                if(logoutPending)return
                logoutPending=true;logoutError=false
                AndroidPush.unregister(context,current){ok->
                    logoutPending=false
                    if(ok){LocalReminders.clearProfile(context,current.authUid);auth.signOut()}else logoutError=true
                }
            }
            DisposableEffect(sync){sync.start();store.cloudHistory=sync::history;onDispose{sync.close();chats.close()}}
            CompositionLocalProvider(LocalDemoIdentity provides current.identity,LocalCloudMode provides true) {
                KotikiTheme(store.theme) {
                    if(store.cloudReady)LockGate(current.authUid) { KotikiApp(store,notificationTarget,onNotificationHandled,onSignOut={logout()},chats=chats)
                        if(logoutPending)AlertDialog(onDismissRequest={},title={Text("Выходим из аккаунта")},text={Text("Отключаем уведомления на этом телефоне. Нужен интернет.")},confirmButton={})
                        if(logoutError)AlertDialog(onDismissRequest={logoutError=false},text={Text("Не удалось отключить уведомления. Проверь интернет и повтори выход.")},confirmButton={TextButton({logoutError=false}){Text("Понятно")}}) }
                    else Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Text(store.cloudStatus)
                            if(store.cloudError.isEmpty())CircularProgressIndicator()
                            TextButton({auth.signOut()},enabled=!store.cloudPending && !store.chatPending){Text("Выйти из аккаунта")}
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LoginScreen(auth:FirebaseAuth,onDemo:()->Unit) {
    var email by rememberSaveable{mutableStateOf("")}
    // Password is intentionally neither persisted nor saved in instance state.
    var password by remember{mutableStateOf("")}
    var working by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            BrandMark(Modifier.size(180.dp),withName=true)
            Text("Войти в наше пространство",style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),enabled=!working)
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Пароль")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!working)
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
            Button({
                working=true;error=""
                auth.signInWithEmailAndPassword(email.trim(),password).addOnCompleteListener {task->
                    working=false;password=""
                    if(!task.isSuccessful)error="Не удалось войти. Проверь email, пароль и подключение к интернету."
                }
            },Modifier.fillMaxWidth(),enabled=!working && email.isNotBlank() && password.isNotEmpty()){Text(if(working)"Входим…" else "Войти")}
            Text("Доступ только у участников вашего пространства. Установка APK сама по себе не даёт доступа.",style=MaterialTheme.typography.bodySmall)
            if(BuildConfig.AGENT_TEST)OutlinedButton({
                working=true;error=""
                auth.signInAnonymously().addOnCompleteListener {task->
                    working=false
                    if(!task.isSuccessful)error="Анонимный вход недоступен. Проверь Firebase и соединение."
                }
            },enabled=!working){Text("Войти анонимно для теста")}
            TextButton(onDemo,enabled=!working){Text("Посмотреть локальное демо")}
        }
    }
}
