package team.kotiki.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource

/** Vector paths and mask from graphics/icons/cat-girl.svg; no bitmap scaling. */
@Composable internal fun CatGirlIcon(modifier:Modifier,color:Color) {
    val bow=remember {PathParser().parsePathString("M-.65 -.5 C-1.35 -1.12 -2.8 -1.9 -3.05 -1.35 C-3.3 -.6 -3.3 .6 -3.05 1.35 C-2.8 1.9 -1.35 1.12 -.65 .5 Z M.65 -.5 C1.35 -1.12 2.8 -1.9 3.05 -1.35 C3.3 -.6 3.3 .6 3.05 1.35 C2.8 1.9 1.35 1.12 .65 .5 Z").toPath()}
    Box(modifier.graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}) {
        Image(painterResource(R.drawable.ic_cat_face),null,Modifier.fillMaxSize(),colorFilter=ColorFilter.tint(color))
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
                translate(18.7f,5.2f) {
                    rotate(23f,pivot=Offset.Zero) {
                        scale(1.4f,pivot=Offset.Zero) {
                            // Match the SVG mask, including its rounded stroke clearance.
                            drawPath(bow,Color.Transparent,blendMode=BlendMode.Clear)
                            drawPath(bow,Color.Transparent,style=Stroke(.9f,join=StrokeJoin.Round),blendMode=BlendMode.Clear)
                            drawCircle(Color.Transparent,1.2f,center=Offset.Zero,blendMode=BlendMode.Clear)
                            drawPath(bow,color)
                            drawCircle(color,.62f,center=Offset.Zero)
                        }
                    }
                }
            }
        }
    }
}
