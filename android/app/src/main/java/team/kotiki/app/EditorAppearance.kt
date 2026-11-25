package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Separate backdrop and content layers; ordinary palettes keep their existing surface color. */
@Composable internal fun EditorSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable () -> Unit,
) {
    val winter = winterDesignEnabled()
    Surface(modifier, shape=shape, color=MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize(), propagateMinConstraints=true) {
            if(winter) Box(Modifier.matchParentSize().drawWithCache {
                val wash = Brush.linearGradient(listOf(Color(0xFFF8FBFE),Color(0xFFF3F8FC)),Offset.Zero,Offset(size.width,size.height))
                // Quiet frost at the corners, never the forest from the main screens.
                val branches = Path().apply {
                    val step=24.dp.toPx()
                    for(i in 0..4) {
                        val x=size.width-i*step
                        moveTo(x,-step);lineTo(size.width+step,step*(i+2))
                        moveTo(x,step*.6f);lineTo(x-step*.45f,-step*.1f)
                        val y=size.height-i*step
                        moveTo(-step,y-step);lineTo(step*(i+1),size.height+step)
                    }
                }
                onDrawBehind {
                    drawRect(wash)
                    drawPath(branches,Color(0x1CA2BDCE),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            })
            content()
            if(winter)WinterSnow(Modifier.matchParentSize())
        }
    }
}

/** Surface opacity is independent of the future backdrop image or its palette. */
@Composable internal fun layeredSurface(opacity:Float=.72f)=MaterialTheme.colorScheme.surface.copy(alpha=opacity)
