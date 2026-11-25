package team.kotiki.app

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.cos
import kotlin.math.sin

internal fun winterDesignAllowed(debug:Boolean,preview:Boolean,remoteEnabled:Boolean=false)=(debug && preview) || remoteEnabled
internal fun winterSnowVisible(enabled:Boolean,userSnow:Boolean,force:Boolean)=enabled && (userSnow || force)
class WinterDesignState(val enabled:Boolean=winterDesignAllowed(BuildConfig.DEBUG,BuildConfig.WINTER_PREVIEW),initialSnow:Boolean=true,private val onSnowChanged:(Boolean)->Unit={}) {
    private var snowValue by mutableStateOf(initialSnow)
    var snow:Boolean
        get()=snowValue
        set(value){snowValue=value;onSnowChanged(value)}
}
val LocalWinterDesign=staticCompositionLocalOf<WinterDesignState?>{null}
@Composable fun winterDesignEnabled()=LocalWinterDesign.current?.enabled==true

internal val WinterColors=lightColorScheme(
    primary=Color(0xFF2866A5),onPrimary=Color.White,primaryContainer=Color(0xFFCDE3F5),onPrimaryContainer=Color(0xFF214F78),
    background=Color(0xFFEDF5FC),onBackground=Color(0xFF203E58),surface=Color(0xFFF7FBFF),onSurface=Color(0xFF203E58),
    surfaceVariant=Color(0xFFDFEDF7),onSurfaceVariant=Color(0xFF405F78),outline=Color(0xFF8BA7BC),outlineVariant=Color(0xFFBED8EB),
    secondary=Color(0xFF3D789B),secondaryContainer=Color(0xFFCFE6F7),onSecondaryContainer=Color(0xFF244B68),
)

@Composable fun WinterForest(modifier:Modifier=Modifier) {
    if(!winterDesignEnabled())return
    Image(painterResource(R.drawable.winter_forest),null,modifier.clearAndSetSemantics{})
}

/** A bounded, deterministic snow field. Animation changes draw state, not the app's composition. */
internal data class SnowParticle(val x:Float,val phase:Float,val seconds:Float,val radius:Float,val powder:Boolean)
internal val winterParticles=List(32){i->SnowParticle(((i*37)%96+2)/100f,((i*23)%100)/100f,18f+(i*7%15),3f+(i%4),i%3==0)}
internal fun snowProgress(seconds:Float,particle:SnowParticle):Float=((seconds/particle.seconds+particle.phase)%1f+1f)%1f

@Composable fun WinterSnow(modifier:Modifier=Modifier,force:Boolean=false) {
    val design=LocalWinterDesign.current ?: return
    if(!winterSnowVisible(design.enabled,design.snow,force))return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)==true)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)==true}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    val elapsed=remember {mutableFloatStateOf(0f)}
    LaunchedEffect(foreground) {
        if(!foreground)return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        var previous=0L
        while(true) {
            withFrameNanos {now->
                if(previous==0L)previous=now
                if(now-previous>=41_000_000L) {
                    // Honor the Android animation switch even if it changes while this screen is open.
                    if(ValueAnimator.areAnimatorsEnabled())elapsed.floatValue+=(now-previous).coerceAtMost(100_000_000L)/1_000_000_000f
                    previous=now
                }
            }
        }
    }
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    val paths=remember(density){winterParticles.map {snowflakePath(it.radius*density)}}
    Canvas(modifier.graphicsLayer()) {
        val seconds=elapsed.floatValue
        val stroke=androidx.compose.ui.graphics.drawscope.Stroke(width=density,cap=StrokeCap.Round)
        winterParticles.forEachIndexed {index,p->
            val progress=snowProgress(seconds,p)
            val x=size.width*p.x+sin(seconds*.5f+p.phase*12)*8*density
            val y=progress*(size.height+30*density)-15*density
            val opacity=if(p.x<.2f || p.x>.9f).48f else .18f
            if(p.powder)drawCircle(Color(0xFFAAC0D5).copy(alpha=opacity),1.5f*density,Offset(x,y))
            else withTransform({translate(x,y);rotate((seconds*.08f+p.phase)*180f/kotlin.math.PI.toFloat(),Offset.Zero)}) {
                drawPath(paths[index],Color(0xFF8EAAC4).copy(alpha=opacity),style=stroke)
            }
        }
    }
}
/** Cache all eighteen segments as one vector path; one draw per flake, no per-frame geometry. */
private fun snowflakePath(radius:Float)=Path().apply {
    for(arm in 0..5) {
        val angle=arm*Math.PI.toFloat()/3
        val direction=Offset(cos(angle),sin(angle))
        val end=direction*radius
        moveTo(0f,0f);lineTo(end.x,end.y)
        val branch=direction*(radius*.61f)
        for(side in -1..1 step 2) {
            val branchAngle=angle+side*Math.PI.toFloat()/3
            val tip=branch+Offset(cos(branchAngle),sin(branchAngle))*(radius*.35f)
            moveTo(branch.x,branch.y);lineTo(tip.x,tip.y)
        }
    }
}
