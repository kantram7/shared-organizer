package team.kotiki.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Only compiled into the opt-in preview APK. No Firebase initialization or real accounts. */
class WinterPreviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && BuildConfig.WINTER_PREVIEW)
        enableEdgeToEdge()
        val store=DemoStore(this)
        val prefs=getSharedPreferences("winter_preview",0)
        if(!prefs.getBoolean("seeded",false)) {
            val today=LocalDate.now();val week=mondayOf(today)
            store.entries.clear()
            store.upsert(Entry("winter-cocoa","Заказать какао",today,week,who=Person.USER_1))
            store.upsert(Entry("winter-skates","Забрать коньки",today,week,start="13:00",end="13:30",kind=Kind.EVENT,who=Person.USER_1))
            store.upsert(Entry("winter-rink","На каток вдвоём",today,week,start="19:00",end="20:30",kind=Kind.EVENT,isGathering=true,invite=InviteState.ACCEPTED))
            store.upsert(Entry("winter-evening","Вечер без спешки",today.plusDays(1),week,start="20:00",end="21:00",kind=Kind.EVENT))
            store.upsert(Entry("winter-film","Выбрать зимний фильм",null,week))
            store.notes.addGroup("Зима",true,true)
            val group=store.notes.groups.last()
            store.notes.addNote(group)
            store.notes.edit(store.notes.notes.last().copy(title="Наши зимние радости",blocks=listOf(
                NoteBlock(kind=NoteBlockKind.CHECK,text="Каток под открытым небом"),NoteBlock(kind=NoteBlockKind.CHECK,text="Испечь печенье"),NoteBlock(text="День без будильников"))))
            store.notes.addNote(group)
            store.notes.edit(store.notes.notes.last().copy(title="Какао на двоих",blocks=listOf(NoteBlock(text="Молоко, тёмный шоколад и щепотка корицы."))))
            prefs.edit().putBoolean("seeded",true).apply()
        }
        if(!prefs.getBoolean("manual_theme_035",false)) {
            store.updateTheme("light")
            prefs.edit().putBoolean("manual_theme_035",true).apply()
        }
        setContent {
            CompositionLocalProvider(LocalDemoIdentity provides store.identity) {
                KotikiTheme(store.theme) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Text("Kotiki team 0.3.5 · локальный просмотр",Modifier.fillMaxWidth().padding(6.dp),style=MaterialTheme.typography.labelSmall)
                        Box(Modifier.weight(1f)){KotikiApp(store,localChatPreview={WinterPreviewChat()})}
                    }
                }
            }
        }
    }
}

@Composable private fun WinterPreviewChat() {
    val messages=remember {mutableStateListOf(
        ChatMessage("snow-1","preview","other","other",Person.USER_2,"На каток сегодня? Возьмём какао?",System.currentTimeMillis()-60000,false,false),
        ChatMessage("snow-2","preview","me","me",Person.USER_1,"Да! И обе пары варежек.",System.currentTimeMillis(),false,false))}
    var draft by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeading("Общий чат")
        Text("Локальный просмотр сообщений",style=MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
            items(messages,key={it.id}){m->MessageBubble(m,personLabel(m.role),m.sender=="me",onDelete={messages.remove(m)},onEdit={t->val i=messages.indexOf(m);if(i>=0)messages[i]=m.copy(text=t)})}
        }
        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(draft,{draft=it},Modifier.weight(1f),label={Text("Сообщение")})
            Button({messages.add(ChatMessage(newId(),"preview","me","me",Person.USER_1,draft,System.currentTimeMillis(),false,false));draft=""},enabled=draft.isNotBlank()){Text("Отправить")}
        }
    }
}
