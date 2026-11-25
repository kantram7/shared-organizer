package team.kotiki.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*

const val PLAIN_TEXT_FORMAT = "plain-v1"
const val MARKDOWN_TEXT_FORMAT = "markdown-v1"
internal data class RichParagraph(val kind:String)

/** The version marker is deliberate: old plain notes containing ** never become formatting. */
internal object RichTextCodec {
    private val inlineTokens=listOf("**","__","~~","<u>","*","_")
    fun escape(value:String)=buildString { value.forEach{if(it in "\\*_[]~#`<>")append('\\');append(it)} }
    fun decode(source:String,format:String=MARKDOWN_TEXT_FORMAT):SpannableStringBuilder {
        if(format!=MARKDOWN_TEXT_FORMAT)return SpannableStringBuilder(source)
        val result=SpannableStringBuilder()
        var fenced=false
        source.split('\n').forEachIndexed{index,line->
            if(index>0)result.append('\n')
            if(line.trimStart().startsWith("```")){fenced=!fenced;result.append(line);return@forEachIndexed}
            if(fenced){result.append(line);return@forEachIndexed}
            var body=line
            var kind=""
            var prefix=""
            when {
                line.startsWith("## ")->{kind="h2";body=line.substring(3)}
                line.startsWith("# ")->{kind="h1";body=line.substring(2)}
                Regex("^[-*] \\[[ xX]] ").containsMatchIn(line)->{kind=if(line[3]==' ')"check" else "checked";body=line.substring(6);prefix=if(kind=="check")"☐ " else "☑ "}
                line.startsWith("- ")||line.startsWith("* ")->{kind="bullet";body=line.substring(2);prefix="• "}
                Regex("^\\d+\\. ").containsMatchIn(line)->{val match=Regex("^\\d+\\. ").find(line)!!;kind="number";prefix=match.value;body=line.substring(prefix.length)}
            }
            val start=result.length
            result.append(prefix);inline(body,result)
            if(kind.isNotEmpty() && result.length>start){
                result.setSpan(RichParagraph(kind),start,result.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(kind.startsWith("h")){
                    result.setSpan(RelativeSizeSpan(if(kind=="h1")1.3f else 1.15f),start,result.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        // Inclusive editing endpoints are enabled only after parsing: otherwise append() would
        // extend each completed paragraph through every following paragraph in the document.
        result.getSpans(0,result.length,RichParagraph::class.java).forEach{result.setSpan(it,result.getSpanStart(it),result.getSpanEnd(it),Spanned.SPAN_EXCLUSIVE_INCLUSIVE)}
        result.getSpans(0,result.length,RelativeSizeSpan::class.java).forEach{result.setSpan(it,result.getSpanStart(it),result.getSpanEnd(it),Spanned.SPAN_EXCLUSIVE_INCLUSIVE)}
        return result
    }
    private fun inline(source:String,out:SpannableStringBuilder) {
        var i=0
        while(i<source.length) {
            if(source[i]=='\\' && i+1<source.length){out.append(source[i+1]);i+=2;continue}
            if(source[i]=='`'){
                val end=source.indexOf('`',i+1)
                if(end>i){out.append(source.substring(i,end+1));i=end+1;continue}
            }
            val unfinishedPair=listOf("**","__").firstOrNull{source.startsWith(it,i)&&source.indexOf(it,i+2)<0}
            if(unfinishedPair!=null){out.append(unfinishedPair);i+=2;continue}
            if(source[i]=='['){
                val end=source.indexOf("](",i+1);val close=if(end>=0)source.indexOf(')',end+2)else -1
                if(close>=0){val url=source.substring(end+2,close)
                    if(url.startsWith("https://")||url.startsWith("http://")||url.startsWith("kotiki://")){
                        val from=out.length;inline(source.substring(i+1,end),out)
                        if(out.length>from)out.setSpan(URLSpan(url),from,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i=close+1;continue
                    }
                }
            }
            var consumed=false
            for(token in inlineTokens)if(source.startsWith(token,i)){
                val closeToken=if(token=="<u>")"</u>" else token
                var end=source.indexOf(closeToken,i+token.length)
                while(end>0 && source[end-1]=='\\')end=source.indexOf(closeToken,end+closeToken.length)
                if(end>i+token.length){
                    val start=out.length;inline(source.substring(i+token.length,end),out)
                    val span=when(token){"**","__"->StyleSpan(Typeface.BOLD);"*","_"->StyleSpan(Typeface.ITALIC);"~~"->StrikethroughSpan();else->UnderlineSpan()}
                    out.setSpan(span,start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i=end+closeToken.length;consumed=true;break
                }
            }
            if(!consumed){out.append(source[i]);i++}
        }
    }
    fun encode(text:Spanned):String {
        val raw=text.toString();var offset=0
        return raw.split('\n').joinToString("\n"){line->
            val start=offset;val end=start+line.length;offset=end+1
            val paragraph=text.getSpans(start,end,RichParagraph::class.java).firstOrNull{ text.getSpanStart(it)<=start && text.getSpanEnd(it)>start }
            var skip=0
            val prefix=when(paragraph?.kind){
                "h1"->"# ";"h2"->"## "
                "bullet"->{if(line.startsWith("• "))skip=2;"- "}
                "check","checked"->{if(line.startsWith("☐ ")||line.startsWith("☑ "))skip=2;if(line.startsWith("☑"))"- [x] " else "- [ ] "}
                "number"->{val p=Regex("^\\d+\\. ").find(line)?.value;skip=p?.length?:0;p?:"1. "}
                else->""
            }
            prefix+encodeInline(text,start+skip,end)
        }
    }
    private fun encodeInline(text:Spanned,start:Int,end:Int):String {
        val out=StringBuilder();var active=emptyList<Pair<String,String>>()
        var i=start
        while(i<end){
            val nextBoundary=text.nextSpanTransition(i,end,CharacterStyle::class.java).coerceAtLeast(i+1)
            val styles=text.getSpans(i,i+1,CharacterStyle::class.java).filter{text.getSpanStart(it)<=i&&text.getSpanEnd(it)>i}
            val next=buildList<Pair<String,String>> {
                val link=styles.filterIsInstance<URLSpan>().firstOrNull();if(link!=null)add("[" to "](${link.url})")
                if(styles.filterIsInstance<StyleSpan>().any{it.style and Typeface.BOLD!=0})add("**" to "**")
                if(styles.filterIsInstance<StyleSpan>().any{it.style and Typeface.ITALIC!=0})add("_" to "_")
                if(styles.any{it is UnderlineSpan})add("<u>" to "</u>")
                if(styles.any{it is StrikethroughSpan})add("~~" to "~~")
            }
            var common=0;while(common<active.size&&common<next.size&&active[common]==next[common])common++
            active.drop(common).reversed().forEach{out.append(it.second)};next.drop(common).forEach{out.append(it.first)}
            out.append(escape(text.subSequence(i,nextBoundary).toString()));active=next;i=nextBoundary
        }
        active.reversed().forEach{out.append(it.second)};return out.toString()
    }
}

fun richPlainText(text:String,format:String=PLAIN_TEXT_FORMAT)=RichTextCodec.decode(text,format).toString()

internal fun legacyNoteMarkdown(blocks:List<NoteBlock>):String=blocks.joinToString("\n"){block->
    val text=RichTextCodec.escape(block.text).let{if(block.bold&&it.isNotEmpty())"**$it**" else it}
    when(block.kind){NoteBlockKind.HEADING->"## $text";NoteBlockKind.BULLET->"- $text";NoteBlockKind.NUMBERED->"1. $text";NoteBlockKind.CHECK->"- [${if(block.checked)"x" else " "}] $text";else->text}
}
