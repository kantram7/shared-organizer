package team.kotiki.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

enum class Glyph(val resource: Int) {
    MEETING(R.drawable.ic_gathering_hearts), MENU(R.drawable.ic_menu), CAT(R.drawable.ic_cat_face), CAT_GIRL(0), PAIR(0), PRIVATE_LOCK(R.drawable.ic_private_lock),
    EYE_OFF(R.drawable.ic_eye_off), BOARD(R.drawable.ic_board), PLANS(R.drawable.ic_plans),
    CALENDAR(R.drawable.ic_calendar), LIST(R.drawable.ic_list), MAIL(R.drawable.ic_mail), CHAT(R.drawable.ic_chat),
    SETTINGS(R.drawable.ic_settings), CLOSE(R.drawable.ic_close), LEFT(R.drawable.ic_left),
    RIGHT(R.drawable.ic_right), PLUS(R.drawable.ic_plus)
}

@Composable fun KotikiIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val width = if(glyph==Glyph.PAIR)48.dp else 24.dp
    val height = 24.dp
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        when(glyph) {
            Glyph.PAIR -> Row(Modifier.requiredSize(48.dp,24.dp)) {
                Image(painterResource(R.drawable.ic_cat_face),null,Modifier.size(24.dp),colorFilter=ColorFilter.tint(color))
                CatGirlIcon(Modifier.size(24.dp),color)
            }
            Glyph.CAT_GIRL -> CatGirlIcon(Modifier.fillMaxSize(),color)
            else -> Image(painterResource(if(glyph==Glyph.PLANS && winterDesignEnabled())R.drawable.ic_snowflake_fern else glyph.resource),contentDescription=null,modifier=Modifier.fillMaxSize(),
                contentScale=ContentScale.Fit,colorFilter=ColorFilter.tint(color))
        }
    }
}
