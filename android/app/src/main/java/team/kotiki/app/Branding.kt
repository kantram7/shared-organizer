package team.kotiki.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

val LocalWelcomeVisible=staticCompositionLocalOf{false}

@Composable fun BrandMark(modifier:Modifier=Modifier,withName:Boolean=false) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val resource=if(withName) {if(dark)R.drawable.ic_brand_emblem_light else R.drawable.ic_brand_emblem}
        else {if(dark)R.drawable.ic_brand_symbol_light else R.drawable.ic_brand_symbol}
    Image(painterResource(resource),contentDescription="Kotiki team",modifier=modifier,contentScale=ContentScale.Fit)
}
/** Fade the emblem in, then dissolve the cover over the already composed app. */
@Composable fun BrandWelcome(show:Boolean,onContinue:()->Unit,content:@Composable ()->Unit) {
    val logoAlpha=remember {Animatable(0f)}
    val coverAlpha=remember {Animatable(1f)}
    var exiting by remember {mutableStateOf(false)}
    val finish by rememberUpdatedState(onContinue)
    LaunchedEffect(show,exiting) {
        if(!show)return@LaunchedEffect
        if(!ValueAnimator.areAnimatorsEnabled()) {finish();return@LaunchedEffect}
        if(exiting) {
            coverAlpha.animateTo(0f,tween(400,easing=FastOutSlowInEasing))
            finish()
        } else {
            logoAlpha.animateTo(1f,tween(420,easing=FastOutSlowInEasing))
            delay(1000)
            exiting=true
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Prepare the screen before fading, so its first composition cannot interrupt the transition.
        Box(if(show)Modifier.fillMaxSize().clearAndSetSemantics{} else Modifier.fillMaxSize()) {CompositionLocalProvider(LocalWelcomeVisible provides show){content()}}
        if(show)Surface(
            Modifier.fillMaxSize().graphicsLayer{alpha=coverAlpha.value}
                .clickable(onClickLabel="Открыть органайзер",onClick={exiting=true}),
            color=MaterialTheme.colorScheme.background
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.Center) {
                BrandMark(Modifier.width(220.dp).aspectRatio(240f/285f).graphicsLayer{alpha=logoAlpha.value},withName=true)
            }
        }
    }
}
