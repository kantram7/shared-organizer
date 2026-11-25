package team.kotiki.app

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun localProfileKey(session:CloudSession?,viewer:Person)=session?.let{"${it.spaceId}/${it.uid}"}?:"demo/${viewer.name}"
internal fun localFile(context:Context,prefix:String,profile:String):File {
    val hash=MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString(""){"%02x".format(it)}
    return File(context.noBackupFilesDir,"$prefix-$hash.json")
}

/** Presentation only: never written into records, Firestore, or Android backups. */
class LocalOrderStore(context:Context,profile:String) {
    private val file=android.util.AtomicFile(localFile(context,"order",profile))
    private val orders=mutableStateMapOf<String,List<String>>()
    init {runCatching{val j=JSONObject(String(file.readFully()));j.keys().forEach{k->
        val a=j.getJSONArray(k);orders[k]=(0 until a.length()).map{a.getString(it)}
    }}}
    fun <T> sorted(scope:String,items:List<T>,id:(T)->String):List<T> {
        val order=orders[scope]?:return items
        val ranks=order.withIndex().associate{it.value to it.index}
        return items.sortedBy{ranks[id(it)]?:Int.MAX_VALUE}
    }
    fun move(scope:String,allIds:List<String>,from:String,to:String):Boolean {
        if(from==to || from !in allIds || to !in allIds)return false
        val next=sorted(scope,allIds){it}.toMutableList()
        val target=next.indexOf(to);next.remove(from);next.add(target,from)
        val json=JSONObject();orders.forEach{(k,v)->json.put(k,JSONArray(v))};json.put(scope,JSONArray(next))
        var stream:java.io.FileOutputStream?=null
        return try {
            stream=file.startWrite();stream.write(json.toString().toByteArray());file.finishWrite(stream)
            orders[scope]=next;true
        } catch(_:Exception){file.failWrite(stream);false}
    }
}

internal class ReorderViewport(val bounds:()->Rect?,val scroll:suspend (Float)->Unit)
internal val LocalReorderViewport=staticCompositionLocalOf<ReorderViewport?>{null}

/** One column is one drop boundary. Timed records are rendered outside it. */
@Composable fun <T> ReorderColumn(store:LocalOrderStore,scope:String,items:List<T>,id:(T)->String,
    allIds:List<String> = items.map(id),enabled:Boolean=true,content:@Composable (T)->Unit) {
    val ordered=if(enabled)store.sorted(scope,items,id)else items
    val currentItems by rememberUpdatedState(ordered)
    val currentAll by rememberUpdatedState(allIds)
    val bounds=remember(scope){mutableMapOf<String,Rect>()}
    var dragging by remember(scope){mutableStateOf<String?>(null)}
    var target by remember(scope){mutableStateOf<String?>(null)}
    var pointerY by remember(scope){mutableFloatStateOf(0f)}
    var offset by remember(scope){mutableFloatStateOf(0f)}
    var startTop by remember(scope){mutableFloatStateOf(0f)}
    var columnBounds by remember(scope){mutableStateOf<Rect?>(null)}
    val motionScope=rememberCoroutineScope()
    var settling by remember(scope){mutableStateOf(false)}
    var dropJob by remember(scope){mutableStateOf<Job?>(null)}
    val viewport=LocalReorderViewport.current
    val context=androidx.compose.ui.platform.LocalContext.current
    fun updateTarget() {
        val available=currentItems.mapNotNull{item->bounds[id(item)]?.let{id(item) to it}}
        target=available.minByOrNull{(_,r)->kotlin.math.abs(r.center.y-pointerY)}?.first
    }
    fun finish() {
        val from=dragging;val to=target
        if(from==null || to==null){dragging=null;target=null;offset=0f;return}
        settling=true
        dropJob=motionScope.launch {
            val source=bounds[from];val destination=bounds[to]
            if(source!=null && destination!=null){
                val targetTop=if(destination.top>source.top)destination.bottom-source.height else destination.top
                animate(offset,targetTop-startTop,animationSpec=tween(150)){value,_->offset=value}
            }
            if(from!=to && !store.move(scope,currentAll,from,to))
                android.widget.Toast.makeText(context,"Не удалось сохранить порядок",android.widget.Toast.LENGTH_SHORT).show()
            dragging=null;target=null;offset=0f;settling=false
        }
    }
    LaunchedEffect(dragging) {
        while(dragging!=null && !settling) {
            delay(16)
            val view=viewport?.bounds();val column=columnBounds
            if(view!=null && column!=null) {
                val edge=64f
                val speed=when {
                    pointerY<view.top+edge && column.top<view.top -> -14f
                    pointerY>view.bottom-edge && column.bottom>view.bottom -> 14f
                    else->0f
                }
                if(speed!=0f){viewport.scroll(speed);updateTarget()}
            }
        }
    }
    LaunchedEffect(ordered.map(id)){if(dragging!=null && ordered.none{id(it)==dragging}){dragging=null;target=null}}
    Column(Modifier.fillMaxWidth().onGloballyPositioned{columnBounds=Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()))}) {
        ordered.forEachIndexed{index,item->val itemId=id(item);key(itemId) {
            val active=dragging==itemId
            var baseTop by remember{mutableFloatStateOf(0f)}
            val fromIndex=ordered.indexOfFirst{id(it)==dragging}
            val targetIndex=ordered.indexOfFirst{id(it)==target}
            val draggedHeight=bounds[dragging]?.height?:0f
            val neighborTarget=when {
                active || fromIndex<0 || targetIndex<0->0f
                fromIndex<targetIndex && index in (fromIndex+1)..targetIndex -> -draggedHeight
                targetIndex<fromIndex && index in targetIndex until fromIndex -> draggedHeight
                else->0f
            }
            val neighborOffset by key(dragging){animateFloatAsState(neighborTarget,tween(160),label="Reorder gap")}
            Box(Modifier.fillMaxWidth().onGloballyPositioned{val r=Rect(it.positionInWindow(),Size(it.size.width.toFloat(),it.size.height.toFloat()));bounds[itemId]=r;baseTop=r.top}
                .zIndex(if(active)1f else 0f)
                .then(if(enabled && ordered.size>1)Modifier.pointerInput(scope,itemId) {
                    detectDragGesturesAfterLongPress(
                        onDragStart={p->dropJob?.cancel();settling=false;dragging=itemId;target=itemId;startTop=baseTop;pointerY=baseTop+p.y;offset=0f},
                        onDragCancel={dragging=null;target=null;offset=0f},onDragEnd={finish()},
                        onDrag={change,amount->change.consume();offset+=amount.y;pointerY+=amount.y;updateTarget()})
                }.semantics {customActions=listOfNotNull(
                    if(index>0)CustomAccessibilityAction("Переместить выше"){store.move(scope,currentAll,itemId,id(currentItems[index-1]))}else null,
                    if(index<ordered.lastIndex)CustomAccessibilityAction("Переместить ниже"){store.move(scope,currentAll,itemId,id(currentItems[index+1]))}else null)
                } else Modifier)) {
                Box(Modifier.graphicsLayer{translationY=if(active) {
                    val area=columnBounds;val height=bounds[itemId]?.height?:0f
                    val top=if(area==null)startTop+offset else (startTop+offset).coerceIn(area.top,(area.bottom-height).coerceAtLeast(area.top))
                    top-baseTop
                }else neighborOffset;alpha=if(active).94f else 1f;shadowElevation=if(active)8.dp.toPx()else 0f}){Column{content(item)}}
            }
        }}
    }
}
