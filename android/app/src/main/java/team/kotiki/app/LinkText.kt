package team.kotiki.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import android.text.style.*
import android.graphics.Typeface
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

internal data class WebLink(val start:Int,val end:Int,val url:String)
internal fun webLinks(text:String):List<WebLink> {
    val pattern=Regex("(?i)(?<![\\p{L}\\p{N}_@/.:])(?:https?://|www\\.)[^\\s<>\\\"]+|(?<![\\p{L}\\p{N}_@/.:])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+[\\p{L}]{2,}(?:/[^\\s<>\\\"]*)?")
    return pattern.findAll(text).mapNotNull {match->
        var raw=match.value.trimEnd('.',',','!','?',':',';','\'','»','”')
        for((close,open) in listOf(')' to '(',']' to '[','}' to '{')) {
            while(raw.endsWith(close) && raw.count{it==close}>raw.count{it==open})raw=raw.dropLast(1)
        }
        val url=if(raw.startsWith("http://",true)||raw.startsWith("https://",true))raw else "https://$raw"
        val uri=runCatching{java.net.URI(url)}.getOrNull()
        if(uri==null || uri.rawAuthority.isNullOrBlank() || uri.rawAuthority.contains('@'))null
        else WebLink(match.range.first,match.range.first+raw.length,url)
    }.toList()
}

internal fun browserIntent(context:Context,url:String):Intent {
    require(url.startsWith("https://",true)||url.startsWith("http://",true))
    val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Resolve a neutral URL to the selected browser, rather than an app claiming the actual link.
    val browser=Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        .resolveActivity(context.packageManager)?.packageName
    if(browser!=null && browser!="android" && browser!=context.packageName)intent.setPackage(browser)
    return intent
}
internal fun openWebLink(context:Context,url:String) {
    try { context.startActivity(browserIntent(context,url)) }
    catch(_:android.content.ActivityNotFoundException){Toast.makeText(context,"Не найден браузер для открытия ссылки",Toast.LENGTH_SHORT).show()}
    catch(_:SecurityException){Toast.makeText(context,"Не удалось открыть ссылку",Toast.LENGTH_SHORT).show()}
}

@Composable fun LinkText(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,
    fontSize:TextUnit=TextUnit.Unspecified,fontWeight:FontWeight?=null,textDecoration:TextDecoration?=null,
    style:TextStyle=LocalTextStyle.current,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,format:String=PLAIN_TEXT_FORMAT) {
    val context=LocalContext.current
    val internalLink=LocalOpenInternalLink.current
    val linkColor=MaterialTheme.colorScheme.primary
    val value=remember(text,format,linkColor,context,internalLink){buildAnnotatedString {
        val rich=RichTextCodec.decode(text,format)
        append(rich.toString())
        rich.getSpans(0,rich.length,CharacterStyle::class.java).forEach{span->
            val style=when(span){
                is StyleSpan->SpanStyle(fontWeight=if(span.style and Typeface.BOLD!=0)FontWeight.Bold else null,fontStyle=if(span.style and Typeface.ITALIC!=0)FontStyle.Italic else null)
                is UnderlineSpan->SpanStyle(textDecoration=TextDecoration.Underline)
                is StrikethroughSpan->SpanStyle(textDecoration=TextDecoration.LineThrough)
                is RelativeSizeSpan->SpanStyle(fontSize=span.sizeChange.em)
                else->null
            }
            if(style!=null)addStyle(style,rich.getSpanStart(span),rich.getSpanEnd(span))
        }
        val explicit=rich.getSpans(0,rich.length,URLSpan::class.java).map{WebLink(rich.getSpanStart(it),rich.getSpanEnd(it),it.url)}
        val links=explicit+webLinks(rich.toString()).filter{candidate->explicit.none{candidate.start<it.end&&candidate.end>it.start}}+
            Regex("kotiki://[^\\s<>]+").findAll(rich).map{WebLink(it.range.first,it.range.last+1,it.value)}.toList()
        links.filter{it.end>it.start}.forEach{link->addLink(LinkAnnotation.Url(link.url,
            TextLinkStyles(SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline))) {if(link.url.startsWith("kotiki://"))internalLink?.invoke(link.url)else openWebLink(context,link.url)},link.start,link.end)}
    }}
    Text(value,modifier,color=color,fontSize=fontSize,fontWeight=fontWeight,textDecoration=textDecoration,style=style,maxLines=maxLines,overflow=overflow)
}

/** Editing keeps normal caret/selection behavior; links have their own tap targets below the field. */
@Composable fun EditorLinks(text:String) {
    val links=remember(text){webLinks(text).map{it.url}.distinct()}
    links.forEach{LinkText(it,style=MaterialTheme.typography.bodySmall)}
}
