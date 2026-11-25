package team.kotiki.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** One vector is used for every disclosure, in every palette. */
@Composable fun DisclosureIcon(expanded:Boolean=false,modifier:Modifier=Modifier) {
    Icon(painterResource(R.drawable.ic_disclosure_more),null,modifier.size(14.dp).graphicsLayer{rotationZ=if(expanded)180f else 0f})
}

/** Auth snow is independent of the in-app pause control. The overlay never consumes input. */
@Composable fun WinterAuthSurface(modifier:Modifier=Modifier,fill:Boolean=true,content:@Composable BoxScope.()->Unit) {
    Box(if(fill)modifier.fillMaxSize()else modifier) {
        if(winterDesignEnabled())WinterAuthBackdrop(Modifier.matchParentSize())
        content()
        WinterSnow(Modifier.matchParentSize(),force=true)
    }
}

@Composable private fun WinterAuthBackdrop(modifier:Modifier=Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFF7FBFF),Color(0xFFE5F1FB),Color(0xFFD4E7F4))))
        val w=size.width;val h=size.height
        // Quiet frosted branches at the bottom, different from the app's forest scene.
        listOf(.06f to .17f,.91f to .22f,.80f to .12f).forEach{(x,height)->
            val tree=Path().apply{moveTo(w*x,h*(1-height));lineTo(w*(x-.09f),h*.99f);lineTo(w*(x+.09f),h*.99f);close()}
            drawPath(tree,Color(0xFF9EBFD6).copy(alpha=.23f))
        }
        drawOval(Color.White.copy(alpha=.80f),Offset(-w*.3f,h*.91f),androidx.compose.ui.geometry.Size(w*1.2f,h*.18f))
        drawOval(Color(0xFFF3FAFF).copy(alpha=.9f),Offset(w*.25f,h*.94f),androidx.compose.ui.geometry.Size(w*1.1f,h*.14f))
    }
}

/** Stable per record ID, irrespective of edits, filters, scrolling or process restarts. */
internal data class Icicle(val left:Float,val width:Float,val height:Float)
internal fun iciclesFor(id:String):List<Icicle> {
    var seed=2166136261L
    id.forEach { seed=((seed xor it.code.toLong())*16777619L) and 0xffffffffL }
    fun random():Float {seed=(seed*1664525L+1013904223L) and 0xffffffffL;return (seed.toDouble()/4294967296.0).toFloat()}
    val count=2+(random()*4).toInt().coerceAtMost(3)
    val large=random()>.45f
    var left=0f
    return List(count) {
        val width=5+(random()*5).toInt()
        val height=8+(random()*(if(large)15 else 7)).toInt()
        Icicle(left,width.toFloat(),height.toFloat()).also {left+=width+4+(random()*4).toInt()}
    }
}

@Composable fun winterSurface(color:Color,opacity:Float=.86f)=if(winterDesignEnabled())color.copy(alpha=opacity) else color

/** Decorations use only an existing trailing gap; never change measurement or touch bounds. */
@Composable fun Modifier.winterIcicles(id:String,availableSpace:Dp=0.dp):Modifier {
    if(!winterDesignEnabled() || availableSpace<4.dp)return this
    val shards=remember(id){iciclesFor(id)}
    return this.drawWithCache {
        val total=(shards.last().left+shards.last().width)*density
        val start=(size.width-total-14.dp.toPx()).coerceAtLeast(2.dp.toPx())
        val ice=shards.map {s->
            val x=start+s.left*density;val w=s.width*density;val h=(s.height/22f)*(availableSpace.toPx()-1.dp.toPx())
            val path=Path().apply {
                moveTo(x,size.height-1.dp.toPx());lineTo(x+w,size.height-1.dp.toPx())
                lineTo(x+w*.8f,size.height+h*.22f);lineTo(x+w*.62f,size.height+h*.77f)
                lineTo(x+w*.5f,size.height+h);lineTo(x+w*.37f,size.height+h*.77f)
                lineTo(x+w*.2f,size.height+h*.22f);close()
            }
            path to Brush.linearGradient(listOf(Color(0x7098C9DF),Color(0xE0F5FCFF),Color(0x88A9D8EC),Color(0xA6E7F8FF)),Offset(x,0f),Offset(x+w,0f))
        }
        onDrawWithContent { drawContent(); ice.forEach{(path,brush)->drawPath(path,brush)} }
    }
}

/** Quiet snow-covered firs behind every app section; static, no frame-by-frame work. */
@Composable fun WinterBackdrop(modifier:Modifier=Modifier) {
    if(!winterDesignEnabled())return
    val tiers=remember {List(3){i->
        val top=i*70f;val width=70f+i*35f;val height=150f
        val branches=Path().apply {
            moveTo(0f,top);lineTo(width*.35f,top+height*.32f);lineTo(width*.28f,top+height*.32f)
            lineTo(width*.72f,top+height*.68f);lineTo(width*.6f,top+height*.65f)
            lineTo(width,top+height);lineTo(width*.45f,top+height*.91f);lineTo(0f,top+height)
            lineTo(-width*.45f,top+height*.91f);lineTo(-width,top+height)
            lineTo(-width*.6f,top+height*.65f);lineTo(-width*.72f,top+height*.68f)
            lineTo(-width*.28f,top+height*.32f);lineTo(-width*.35f,top+height*.32f);close()
        }
        val snow=Path().apply {
            moveTo(0f,top);lineTo(width*.35f,top+height*.32f);lineTo(width*.1f,top+height*.27f)
            lineTo(0f,top+height*.33f);lineTo(-width*.1f,top+height*.27f);lineTo(-width*.35f,top+height*.32f);close()
            moveTo(-width*.72f,top+height*.68f);lineTo(-width*.32f,top+height*.5f)
            lineTo(0f,top+height*.59f);lineTo(width*.32f,top+height*.5f);lineTo(width*.72f,top+height*.68f)
            lineTo(width*.3f,top+height*.63f);lineTo(0f,top+height*.7f);lineTo(-width*.3f,top+height*.63f);close()
        }
        branches to snow
    }}
    Canvas(modifier) {
        val unit=size.width/430f
        listOf(Triple(.95f,.16f,1.7f),Triple(.61f,.35f,1.05f),Triple(.97f,.69f,1.3f),Triple(.13f,.77f,.7f)).forEachIndexed {index,(x,y,scale)->
            withTransform({translate(size.width*x,size.height*y);scale(unit*scale,unit*scale,Offset.Zero)}) {
                tiers.forEach{(tree,snow)->
                    drawPath(tree,Color(0xFF83A6BB).copy(alpha=if(index%2==0).32f else .19f))
                    drawPath(snow,Color(0xFFF7FBFE).copy(alpha=.7f))
                }
            }
        }
        drawOval(Color(0x99F4FAFF),Offset(-size.width*.2f,size.height-50.dp.toPx()),androidx.compose.ui.geometry.Size(size.width*1.5f,110.dp.toPx()))
    }
}
