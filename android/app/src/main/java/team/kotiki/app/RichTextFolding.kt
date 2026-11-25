package team.kotiki.app

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan

/** Presentation only: folded characters remain in the document, selection and Markdown serialization. */
internal class CollapsedCheckSpan:ReplacementSpan(),LineHeightSpan {
    override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
        fm?.let{it.top=0;it.ascent=0;it.descent=0;it.bottom=0;it.leading=0};return 0
    }
    override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint){}
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){fm.top=0;fm.ascent=0;fm.descent=0;fm.bottom=0;fm.leading=0}
}

internal fun completedCheckRanges(text:Spanned):List<IntRange> {
    val raw=text.toString()
    return text.getSpans(0,text.length,RichParagraph::class.java).filter{it.kind in listOf("check","checked")}.mapNotNull{paragraph->
        val start=text.getSpanStart(paragraph)
        if(raw.getOrNull(start)!='☑')null else {
            val lineEnd=raw.indexOf('\n',start).let{if(it<0)raw.length else it+1}
            if(lineEnd>start)start until lineEnd else null
        }
    }.distinct().sortedBy{it.first}
}
internal fun applyCompletedFolding(text:Spannable,folded:Boolean) {
    text.getSpans(0,text.length,CollapsedCheckSpan::class.java).forEach(text::removeSpan)
    if(!folded)return
    val merged=mutableListOf<IntRange>()
    completedCheckRanges(text).forEach{range->
        val last=merged.lastOrNull()
        if(last!=null&&last.last+1==range.first)merged[merged.lastIndex]=last.first..range.last else merged+=range
    }
    merged.forEach{text.setSpan(CollapsedCheckSpan(),it.first,it.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
}

internal fun sourceDeepLink(kind:String,vararg ids:String):String? {
    val count=when(kind){"note","entry"->1;"plan"->2;else->return null}
    if(ids.size!=count||ids.any{!it.matches(Regex("[A-Za-z0-9_-]{1,128}"))})return null
    return "kotiki://$kind/"+ids.joinToString("/")
}
internal fun selectionWithSource(text:String,link:String?):String {
    if(link==null)return text
    val uri=runCatching{java.net.URI(link)}.getOrNull()?:return text
    if(uri.scheme!="kotiki"||uri.userInfo!=null||uri.query!=null||uri.fragment!=null)return text
    val verified=sourceDeepLink(uri.host.orEmpty(),*uri.path.orEmpty().trim('/').split('/').toTypedArray())?:return text
    return text+"\n\nИсточник: $verified"
}
