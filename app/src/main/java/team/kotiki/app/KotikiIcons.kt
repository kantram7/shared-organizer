package team.kotiki.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

enum class Glyph { CAT, PAIR, SHIELD_CAT, EYE_OFF, BOARD, PLANS, CALENDAR, LIST, MAIL, SETTINGS, CLOSE, LEFT, RIGHT, PLUS }

@Composable fun KotikiIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) { glyph(glyph, color) }
    }
}
private fun DrawScope.cat(color: Color) {
    val p = Path().apply {
        moveTo(4f, 10f); lineTo(4f, 3f); lineTo(9f, 6f); quadraticTo(12f, 4f, 15f, 6f)
        lineTo(20f, 3f); lineTo(20f, 10f); cubicTo(26f, 23f, -2f, 23f, 4f, 10f); close()
    }
    drawPath(p, color, style = Stroke(1.6f, cap = StrokeCap.Round))
    drawCircle(color, .8f, Offset(8f, 12f)); drawCircle(color, .8f, Offset(16f, 12f))
    drawLine(color, Offset(10.5f, 15f), Offset(12f, 16.2f), 1.3f, StrokeCap.Round)
    drawLine(color, Offset(12f, 16.2f), Offset(13.5f, 15f), 1.3f, StrokeCap.Round)
}
private fun DrawScope.glyph(g: Glyph, c: Color) {
    fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(c, Offset(x,y), Offset(x2,y2), 1.7f, StrokeCap.Round)
    fun box(x: Float, y: Float, w: Float, h: Float) = drawRect(c, Offset(x,y), androidx.compose.ui.geometry.Size(w,h), style = Stroke(1.6f))
    when (g) {
        Glyph.CAT -> cat(c)
        Glyph.PAIR -> { scale(.6f, .6f, Offset.Zero) { translate(0f, 7f) { cat(c) }; translate(16f, 7f) { cat(c) } } }
        Glyph.SHIELD_CAT -> {
            drawPath(Path().apply { moveTo(12f,2f); lineTo(21f,6f); cubicTo(21f,15f,19f,19f,12f,22f); cubicTo(5f,19f,3f,15f,3f,6f); close() }, c, style = Stroke(1.5f))
            // Cat coordinates occupy x=7.2..16.8, y=7.2..16.8, entirely inside the shield.
            translate(7.2f, 7.2f) { scale(.4f, .4f, Offset.Zero) { cat(c) } }
        }
        Glyph.EYE_OFF -> {
            drawPath(Path().apply { moveTo(2f,12f); quadraticTo(12f,-1f,22f,12f); quadraticTo(12f,25f,2f,12f) }, c, style=Stroke(1.6f))
            drawCircle(c,3f,Offset(12f,12f),style=Stroke(1.6f));line(3f,3f,21f,21f)
        }
        Glyph.BOARD -> { box(3f,4f,7f,16f);box(14f,4f,7f,10f) }
        Glyph.PLANS -> { line(12f,2f,12f,22f);line(2f,12f,22f,12f);line(5f,5f,19f,19f);line(19f,5f,5f,19f) }
        Glyph.CALENDAR -> { box(3f,5f,18f,16f);line(3f,10f,21f,10f);line(7f,2f,7f,7f);line(17f,2f,17f,7f) }
        Glyph.LIST -> { for(y in listOf(6f,12f,18f)){line(9f,y,21f,y);line(3f,y,5f,y)} }
        Glyph.MAIL -> { box(2f,5f,20f,14f);line(2f,5f,12f,13f);line(12f,13f,22f,5f) }
        Glyph.SETTINGS -> { drawCircle(c,7f,Offset(12f,12f),style=Stroke(1.8f));drawCircle(c,2.5f,Offset(12f,12f),style=Stroke(1.6f));line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f) }
        Glyph.CLOSE -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
        Glyph.LEFT -> { line(15f,5f,8f,12f);line(8f,12f,15f,19f) }
        Glyph.RIGHT -> { line(9f,5f,16f,12f);line(16f,12f,9f,19f) }
        Glyph.PLUS -> { line(12f,5f,12f,19f);line(5f,12f,19f,12f) }
    }
}
