package team.kotiki.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.offset
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.delay

internal class EntryNewState(context:Context,private val viewer:Person,private val session:CloudSession?) {
    private val prefs=context.getSharedPreferences("entry_seen_${session?.uid ?: viewer.name}_${session?.spaceId.orEmpty()}",0)
    private val seen=mutableStateMapOf<String,Boolean>().apply{prefs.all.filterValues{it==true}.keys.forEach{put(it,true)}}
    private val retained=mutableStateMapOf<String,Boolean>()
    var active by mutableStateOf(true)
    fun isNew(e:Entry):Boolean=e.owner!=viewer && (e.who==viewer || e.who==Person.BOTH) && e.visibility==Visibility.SHARED && !e.done && (retained[e.id]==true || seen[e.id]!=true)
    fun refresh(){retained.clear()}
    fun seen(e:Entry,opened:Boolean=false){
        if(e.owner==viewer || (e.who!=viewer && e.who!=Person.BOTH) || e.visibility!=Visibility.SHARED)return
        if(opened)retained.remove(e.id)else if(isNew(e))retained[e.id]=true
        if(seen[e.id]==true)return
        seen[e.id]=true;prefs.edit().putBoolean(e.id,true).apply()
        session?.let{s->FirebaseFirestore.getInstance().collection("spaces").document(s.spaceId).collection("members").document(s.uid).collection("seenEntries").document(e.id)
            .set(mapOf("seenAt" to FieldValue.serverTimestamp(),"actor" to s.authUid))}
    }
    fun observe():()->Unit {
        val s=session ?: return {}
        val listener=FirebaseFirestore.getInstance().collection("spaces").document(s.spaceId).collection("members").document(s.uid).collection("seenEntries").addSnapshotListener{snapshot,_->
            snapshot?.documents?.forEach{d->seen[d.id]=true;prefs.edit().putBoolean(d.id,true).apply()}
        };return{listener.remove()}
    }
}
internal val LocalEntryNewState=staticCompositionLocalOf<EntryNewState?>{null}
internal fun Modifier.markNewEntry(entry:Entry):Modifier=composed {
    val state=LocalEntryNewState.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    var visible by remember(entry.id){mutableStateOf(false)}
    val fresh=state?.isNew(entry)==true
    val color=MaterialTheme.colorScheme.primary.copy(alpha=.82f)
    val labelPaint=remember{android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)}
    LaunchedEffect(visible,state?.active,fresh,lifecycleState){if(visible && state?.active==true && fresh && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)){delay(350);if(visible && state.active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))state.seen(entry)}}
    this.onGloballyPositioned{coordinates->val rect=coordinates.boundsInWindow();val root=coordinates.findRootCoordinates().boundsInWindow();val overlap=rect.intersect(root);visible=coordinates.isAttached && overlap.height>rect.height*.6f && overlap.width>rect.width*.6f}
        .drawWithContent{
            drawContent()
            if(fresh){
                val inset=1.dp.toPx()
                labelPaint.color=android.graphics.Color.argb(225,(color.red*255).toInt(),(color.green*255).toInt(),(color.blue*255).toInt());labelPaint.textSize=10.sp.toPx()
                val metrics=labelPaint.fontMetrics;val labelHeight=metrics.descent-metrics.ascent
                val top=labelHeight/2+inset;val x=12.dp.toPx();val width=labelPaint.measureText("Новое")
                clipRect(x-4.dp.toPx(),0f,x+width+4.dp.toPx(),top+2.dp.toPx(),clipOp=ClipOp.Difference){
                    drawRoundRect(color,Offset(inset,top),Size(size.width-2*inset,size.height-top-inset),CornerRadius(12.dp.toPx()),style=Stroke(1.5.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(11.dp.toPx(),5.dp.toPx()))))
                }
                drawIntoCanvas{it.nativeCanvas.drawText("Новое",x,top-(metrics.ascent+metrics.descent)/2,labelPaint)}
            }
        }.layout { measurable,constraints ->
            val extra=if(fresh)14.sp.roundToPx() else 0
            val child=measurable.measure(constraints.offset(vertical=-extra))
            layout(child.width,child.height+extra){child.placeRelative(0,extra)}
        }
}
