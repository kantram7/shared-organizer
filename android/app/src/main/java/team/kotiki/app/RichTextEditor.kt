package team.kotiki.app

import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.text.*
import android.text.style.*
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/** Shared visual editor. Its sole persisted representation is versioned Markdown. */
@Composable fun RichTextEditor(
    value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    format:String=PLAIN_TEXT_FORMAT,label:String?=null,readOnly:Boolean=false,
    minLines:Int=3,maxLines:Int=Int.MAX_VALUE,compact:Boolean=false,titleOnly:Boolean=false,
    onExplicitFocus:(()->Unit)?=null,onCreateTask:((String)->Unit)?=null,blankTapNewLine:Boolean=false,maxLength:Int?=null,
    collapsibleChecks:Boolean=false,sourceLink:String?=null,onFocusChanged:((Boolean)->Unit)?=null,
) {
    val colors=MaterialTheme.colorScheme
    val bringIntoView=remember{BringIntoViewRequester()}
    val scope=rememberCoroutineScope()
    val caretRequests=remember { object { var job:kotlinx.coroutines.Job?=null } }
    var field by remember{mutableStateOf<VisualEditText?>(null)}
    var focused by remember{mutableStateOf(false)}
    var linkDialog by remember{mutableStateOf(false)}
    var link by remember{mutableStateOf("")}
    var linkRange by remember{mutableStateOf(0 to 0)}
    var lengthError by remember{mutableStateOf(false)}
    var checksFolded by rememberSaveable{mutableStateOf(true)}
    val completed=remember(value,format){if(collapsibleChecks)completedCheckRanges(RichTextCodec.decode(value,format)).size else 0}
    val callback by rememberUpdatedState(onValueChange)
    val acceptedValue by rememberUpdatedState(value)
    val acceptedFormat by rememberUpdatedState(format)
    val createTask by rememberUpdatedState(onCreateTask?:LocalCreateTask.current)
    val sourceReference by rememberUpdatedState(sourceLink)
    val modelClock=remember{EditorModelClock()}
    // This clock is snapshot state, read with the same snapshot as the model argument.
    // Reading the live native revision here would pair a stale model with a newer token.
    val revisionAtComposition=modelClock.revision
    val accept:(String)->Unit={next->
        if(maxLength!=null&&next.length>maxLength){lengthError=true;field?.setContent(acceptedValue,acceptedFormat)}
        else{lengthError=false;modelClock.publish(field?.editRevision ?: modelClock.revision){callback(next)}}
    }
    val fieldFrame=if(!compact&&!blankTapNewLine)Modifier
        .border(1.dp,if(focused)colors.primary else colors.outlineVariant,RoundedCornerShape(12.dp))
        .padding(horizontal=12.dp,vertical=8.dp) else Modifier
    Column(modifier.then(fieldFrame)) {
        if(label!=null&&!compact)Text(label,style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
        AndroidView(factory={context->VisualEditText(context).apply {
            field=this
            onModelRevision={modelClock.revision=it}
            background=null;setPadding(0,8,0,8);gravity=android.view.Gravity.TOP or android.view.Gravity.START
            textSize=if(titleOnly)20f else 16f
            setLineSpacing((if(compact)3f else 5f)*resources.displayMetrics.density,1f)
            setTextIsSelectable(true)
            setSingleLine(titleOnly)
            this.minLines=minLines;this.maxLines=if(titleOnly)1 else maxLines
            hint=if(label!=null&&!compact)"" else if(titleOnly)"Название" else if(compact)label?:"Сообщение" else "Текст…"
            setReadOnly(readOnly)
            contentDescription=label?:if(titleOnly)"Название" else "Текст блока"
            this.blankTapNewLine=blankTapNewLine
            inlineOnly=titleOnly
            onUnfoldRequested={checksFolded=false}
            onEmit=accept
            onCaret={rect->caretRequests.job?.cancel();caretRequests.job=scope.launch{bringIntoView.bringIntoView(androidx.compose.ui.geometry.Rect(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat()))}}
            onFocusChangeListener=android.view.View.OnFocusChangeListener{_,hasFocus->focused=hasFocus;onFocusChanged?.invoke(hasFocus);if(hasFocus){onExplicitFocus?.invoke();post{revealCaret()}}}
            installSelectionActions(
                createTask={selected->createTask?.invoke(selectionWithSource(selected,sourceReference))},
                canCreateTask={createTask!=null},
                onLink={start,end->linkRange=start to end;link="";linkDialog=true},
            )
            setContent(value,format)
        }},update={edit->
            edit.setTextColor(colors.onSurface.toArgb());edit.setHintTextColor(colors.onSurfaceVariant.copy(alpha=.65f).toArgb())
            edit.setReadOnly(readOnly)
            edit.ruleColor=colors.onSurface.copy(alpha=.10f).toArgb()
            edit.showRules=blankTapNewLine
            edit.onPointerFocus=onExplicitFocus
            edit.onEmit=accept
            edit.acceptModel(value,format,revisionAtComposition)
            edit.setFoldCompleted(collapsibleChecks&&checksFolded)
        },modifier=Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).heightIn(min=if(titleOnly)32.dp else (minLines*28).dp).then(if(blankTapNewLine)Modifier.weight(1f)else Modifier))
        if(completed>0)TextButton({checksFolded=!checksFolded}){
            Text("Выполнено · $completed");Spacer(Modifier.width(4.dp));DisclosureIcon(!checksFolded)
        }
        if(lengthError)Text("Не более $maxLength символов с учётом оформления. Последнее изменение не добавлено.",color=colors.error,style=MaterialTheme.typography.bodySmall)
    }
    if(linkDialog)AlertDialog(onDismissRequest={linkDialog=false},title={Text("Ссылка")},text={OutlinedTextField(link,{link=it},singleLine=true,placeholder={Text("https://…")})},
        confirmButton={TextButton({field?.let{it.setSelection(linkRange.first.coerceIn(0,it.length()),linkRange.second.coerceIn(0,it.length()));it.linkSelection(link.trim())};linkDialog=false},enabled=link.startsWith("https://")||link.startsWith("http://")){Text("Добавить")}},dismissButton={TextButton({linkDialog=false}){Text("Отмена")}})
}

internal class VisualEditText(context:android.content.Context):EditText(context) {
    var emitted="";private set
    private var modelValue=""
    private var modelFormat=PLAIN_TEXT_FORMAT
    var editRevision=0L;private set
    var onModelRevision:(Long)->Unit={}
    private var emittedFormat=PLAIN_TEXT_FORMAT
    var onEmit:(String)->Unit={}
    var onCaret:((android.graphics.Rect)->Unit)?=null
    var onPointerFocus:(()->Unit)?=null
    private var downX=0f
    private var downY=0f
    private var downAt=0L
    var blankTapNewLine=false
    var showRules=false
        set(value){if(field!=value){field=value;invalidate()}}
    var ruleColor=0
        set(value){if(field!=value){field=value;invalidate()}}
    private val rulePaint=Paint(Paint.ANTI_ALIAS_FLAG)

    var inlineOnly=false
    var onUnfoldRequested:(()->Unit)?=null
    private var foldedChecks=false
    private var adjusting=false
    private var sourceMode=false
    private var locked=false
    private var editableKeys:android.text.method.KeyListener?=null
    private var before=""
    private var lastVisibleWindowHeight=-1
    private var newlineAt=-1
    private var rawRunStart=-1
    private var rawRunEnd=-1
    private val undo=ArrayDeque<String>()
    private val redo=ArrayDeque<String>()
    init {
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){if(!adjusting){
                before=currentMarkdown()
                // Editing at a hidden boundary first reveals it, so Backspace can never silently eat hidden text.
                if(foldedChecks&&text.getSpans((start-1).coerceAtLeast(0),(start+count+1).coerceAtMost(length()),CollapsedCheckSpan::class.java).isNotEmpty()){
                    foldedChecks=false;applyCompletedFolding(text,false);onUnfoldRequested?.invoke()
                }
            }}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!adjusting){
                newlineAt=if(count==1&&s?.get(start)=='\n')start else -1
                if(start==rawRunEnd&&before==0)rawRunEnd=start+count else {rawRunStart=start;rawRunEnd=start+count}
            }}
            override fun afterTextChanged(s:Editable?) {if(!adjusting){
                if(newlineAt>=0&&!sourceMode&&android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text)<0)continueList(newlineAt)
                newlineAt=-1
                if(!sourceMode&&android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text)<0)convertTypedMarkdown()
                if(undo.lastOrNull()!=before)undo.addLast(before);while(undo.size>60)undo.removeFirst();redo.clear();applyCompletedFolding(text,foldedChecks&&!sourceMode);publish();post{revealCaret()}
            }}
        })
        viewTreeObserver.addOnGlobalLayoutListener {
            val visible=android.graphics.Rect();getWindowVisibleDisplayFrame(visible)
            val height=visible.height()
            if(height!=lastVisibleWindowHeight) {
                lastVisibleWindowHeight=height
                if(hasFocus())revealCaret()
            }
        }
    }
    /** Selection is the only formatting surface; standard copy/paste actions stay intact. */
    fun installSelectionActions(createTask:(String)->Unit,canCreateTask:()->Boolean,onLink:(Int,Int)->Unit) {
        customSelectionActionModeCallback=object:ActionMode.Callback {
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {
                if(!locked)editorSelectionFormats(inlineOnly).forEachIndexed { index,action ->
                    menu.add(0,8200+index,20+index,action.first)
                }
                if(!locked&&!inlineOnly)menu.add(0,8301,40,"Ссылка")
                if(canCreateTask())menu.add(0,8302,41,"Создать задачу")
                return true
            }
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onDestroyActionMode(mode:ActionMode){}
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean {
                val action=editorSelectionFormats(inlineOnly).getOrNull(item.itemId-8200)
                when {
                    action!=null&&!locked->formatSelection(action.second)
                    item.itemId==8301&&!locked&&!inlineOnly->onLink(minOf(selectionStart,selectionEnd).coerceAtLeast(0),maxOf(selectionStart,selectionEnd).coerceAtLeast(0))
                    item.itemId==8302&&canCreateTask()->createTask(text.toString().substring(minOf(selectionStart,selectionEnd).coerceAtLeast(0),maxOf(selectionStart,selectionEnd).coerceAtLeast(0)))
                    else->return false
                }
                mode.finish();return true
            }
        }
    }
    override fun onCreateInputConnection(outAttrs:EditorInfo):InputConnection? {
        val target=super.onCreateInputConnection(outAttrs)?:return null
        return object:InputConnectionWrapper(target,false) {
            override fun finishComposingText():Boolean {
                val result=super.finishComposingText();finishMarkdownComposition();return result
            }
            override fun commitText(text:CharSequence?,newCursorPosition:Int):Boolean {
                val result=super.commitText(text,newCursorPosition);finishMarkdownComposition();return result
            }
        }
    }
    internal fun finishMarkdownComposition() {
        if(adjusting||locked||sourceMode||android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text)>=0)return
        val previous=currentMarkdown();convertTypedMarkdown()
        if(currentMarkdown()!=previous){applyCompletedFolding(text,foldedChecks);publish();post{revealCaret()}}
    }
    /** Ruled paper is an ink overlay, never a replacement for the theme's background. */
    override fun onDraw(canvas:Canvas) {
        if(showRules)layout?.let { lines ->
            rulePaint.color=ruleColor;rulePaint.strokeWidth=resources.displayMetrics.density.coerceAtLeast(1f)*.6f
            val saved=canvas.save()
            canvas.clipRect(scrollX+paddingLeft,scrollY+paddingTop,scrollX+width-paddingRight,scrollY+height-paddingBottom)
            noteRulePositions(lines,paddingTop.toFloat(),scrollY.toFloat(),(height-paddingBottom).toFloat(),lineHeight.toFloat(),lineSpacingExtra).forEach { y ->
                canvas.drawLine((scrollX+paddingLeft).toFloat(),y,(scrollX+width-paddingRight).toFloat(),y,rulePaint)
            }
            canvas.restoreToCount(saved)
        }
        super.onDraw(canvas)
    }
    fun setFoldCompleted(folded:Boolean){
        if(foldedChecks==folded)return
        foldedChecks=folded;applyCompletedFolding(text,folded&&!sourceMode)
        requestLayout();invalidate()
    }
    private fun convertTypedMarkdown(){
        if(rawRunStart<0||rawRunEnd>length()||rawRunStart>=rawRunEnd)return
        val raw=text.substring(rawRunStart,rawRunEnd)
        val rich=RichTextCodec.decode(raw)
        if(rich.toString()==raw||rich.getSpans(0,rich.length,Any::class.java).isEmpty())return
        if(inlineOnly&&rich.getSpans(0,rich.length,Any::class.java).any{it !is StyleSpan})return
        // Only newly typed characters are candidates; saved legacy literals are never reinterpreted.
        val start=rawRunStart;val end=rawRunEnd
        adjusting=true;text.replace(start,end,rich);setSelection((start+rich.length).coerceAtMost(length()));adjusting=false
        rawRunStart=-1;rawRunEnd=-1
    }
    fun setReadOnly(readOnly:Boolean){
        if(readOnly!=locked){
            if(readOnly){editableKeys=keyListener;keyListener=null}else keyListener=editableKeys
            locked=readOnly
        }
        showSoftInputOnFocus=!readOnly;isCursorVisible=!readOnly
    }
    fun revealCaret(){
        if(!hasFocus())return
        val position=selectionEnd.coerceIn(0,length());val lines=layout?:return
        val line=lines.getLineForOffset(position)
        bringPointIntoView(position)
        val x=lines.getPrimaryHorizontal(position).toInt()+totalPaddingLeft-scrollX
        onCaret?.invoke(android.graphics.Rect(x,lines.getLineTop(line)+totalPaddingTop-scrollY,x+8,lines.getLineBottom(line)+totalPaddingTop-scrollY+12))
    }
    override fun onSelectionChanged(start:Int,end:Int){
        super.onSelectionChanged(start,end)
        if(onCaret!=null)post{revealCaret()}
    }
    private fun continueList(at:Int) {
        val raw=text.toString();val start=raw.lastIndexOf('\n',at-1)+1
        val paragraph=text.getSpans(start,at,RichParagraph::class.java).firstOrNull()?:return
        if(paragraph.kind in listOf("h1","h2")){
            text.removeSpan(paragraph);text.setSpan(RichParagraph(paragraph.kind),start,at,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.getSpans(start,at+1,RelativeSizeSpan::class.java).forEach{span->val from=text.getSpanStart(span);text.removeSpan(span);if(from<at)text.setSpan(RelativeSizeSpan(span.sizeChange),from,at,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
            return
        }
        if(paragraph.kind !in listOf("bullet","number","check","checked"))return
        val prefix=when(paragraph.kind){"bullet"->"• ";"number"->Regex("^\\d+\\. ").find(raw.substring(start,at))?.value?:"1. ";else->if(raw.getOrNull(start)=='☑')"☑ " else "☐ "}
        adjusting=true
        text.removeSpan(paragraph)
        if(raw.substring(start,at).trim()==prefix.trim()) {
            text.delete(start,at+1);setSelection(start)
        } else {
            text.setSpan(RichParagraph(paragraph.kind),start,at,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val next=when(paragraph.kind){"number"->"${prefix.substringBefore('.').toIntOrNull()?.plus(1)?:1}. ";"check","checked"->"☐ ";else->prefix}
            text.insert(at+1,next)
            text.setSpan(RichParagraph(if(paragraph.kind=="checked")"check" else paragraph.kind),at+1,at+1+next.length,Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            setSelection(at+1+next.length)
        }
        adjusting=false
        rawRunStart=-1;rawRunEnd=-1
    }
    private fun currentMarkdown()=if(sourceMode)text.toString()else RichTextCodec.encode(text)
    /** Only a model captured after the latest native edit may replace native text.
     * A revision token has constant memory; bursts and reordered updates never evict an ACK.
     */
    fun acceptModel(value:String,format:String,observedRevision:Long=editRevision) {
        if(observedRevision<editRevision)return
        if(value==modelValue && format==modelFormat)return
        modelValue=value;modelFormat=format
        if(value==emitted && format==emittedFormat)return
        setContent(value,format)
    }
    fun setContent(value:String,format:String) {
        modelValue=value;modelFormat=format;emittedFormat=format;editRevision++
        adjusting=true
        val cursor=selectionEnd.coerceAtLeast(0)
        setText(if(sourceMode){if(format==MARKDOWN_TEXT_FORMAT)value else RichTextCodec.escape(value)}else RichTextCodec.decode(value,format))
        setSelection(cursor.coerceAtMost(length()))
        emitted=value;adjusting=false
        rawRunStart=-1;rawRunEnd=-1
        applyCompletedFolding(text,foldedChecks&&!sourceMode)
        onModelRevision(editRevision)
    }
    fun switchSource(source:Boolean){val value=currentMarkdown();sourceMode=source;setContent(value,MARKDOWN_TEXT_FORMAT);publish()}
    private fun publish(){
        emitted=currentMarkdown();emittedFormat=MARKDOWN_TEXT_FORMAT;editRevision++
        onEmit(emitted)
    }
    private fun change(action:()->Unit){undo.addLast(currentMarkdown());redo.clear();adjusting=true;action();adjusting=false;rawRunStart=-1;rawRunEnd=-1;applyCompletedFolding(text,foldedChecks&&!sourceMode);publish();requestFocus()}
    fun history(direction:Int){val from=if(direction<0)undo else redo;val to=if(direction<0)redo else undo;if(from.isEmpty())return;to.addLast(currentMarkdown());val value=from.removeLast();setContent(value,MARKDOWN_TEXT_FORMAT);publish()}
    fun linkSelection(url:String){val a=minOf(selectionStart,selectionEnd).coerceAtLeast(0);val b=maxOf(selectionStart,selectionEnd).coerceAtLeast(0);if(a<b)change{text.setSpan(URLSpan(url),a,b,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    fun formatSelection(kind:String) {
        if(sourceMode||locked)return
        val a=minOf(selectionStart,selectionEnd).coerceAtLeast(0);val b=maxOf(selectionStart,selectionEnd).coerceAtLeast(0)
        if(kind in listOf("paragraph","h1","h2","bullet","number","check")) {
            val raw=text.toString();val start=raw.lastIndexOf('\n',(a-1).coerceAtLeast(-1))+1;val end=raw.indexOf('\n',b).let{if(it<0)raw.length else it}
            change {
                val source=RichTextCodec.encode(SpannableStringBuilder(text,start,end))
                val lines=source.split('\n').mapIndexed{i,line->
                    val plain=line.replace(Regex("^(#{1,2} |[-*] (\\[[ xX]] )?|\\d+\\. )"),"")
                    (when(kind){"paragraph"->"";"h1"->"# ";"h2"->"## ";"bullet"->"- ";"number"->"${i+1}. ";else->"- [ ] "})+plain
                }.joinToString("\n")
                // Editable.replace retains enclosing spans. Explicitly remove the old block
                // type/heading metrics before inserting the newly formatted paragraphs.
                text.getSpans(start,end,RichParagraph::class.java).filter{text.getSpanStart(it)<end&&text.getSpanEnd(it)>start}.forEach(text::removeSpan)
                text.getSpans(start,end,RelativeSizeSpan::class.java).filter{text.getSpanStart(it)<end&&text.getSpanEnd(it)>start}.forEach(text::removeSpan)
                val replacement=RichTextCodec.decode(lines)
                text.replace(start,end,replacement);setSelection((start+replacement.length).coerceAtMost(length()))
            };return
        }
        if(a==b)return
        change {
            val matching=text.getSpans(a,b,CharacterStyle::class.java).filter{span->when(kind){"bold"->span is StyleSpan&&span.style==Typeface.BOLD;"italic"->span is StyleSpan&&span.style==Typeface.ITALIC;"underline"->span is UnderlineSpan;"strike"->span is StrikethroughSpan;else->true}}
            val covering=matching.any{text.getSpanStart(it)<=a&&text.getSpanEnd(it)>=b}
            matching.forEach{span->
                val start=text.getSpanStart(span);val end=text.getSpanEnd(span);text.removeSpan(span)
                fun copy():Any=when(span){is StyleSpan->StyleSpan(span.style);is UnderlineSpan->UnderlineSpan();is StrikethroughSpan->StrikethroughSpan();is URLSpan->URLSpan(span.url);else->RelativeSizeSpan(1f)}
                if(start<a)text.setSpan(copy(),start,a,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(end>b)text.setSpan(copy(),b,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if(!covering&&kind!="clear")text.setSpan(when(kind){"bold"->StyleSpan(Typeface.BOLD);"italic"->StyleSpan(Typeface.ITALIC);"underline"->UnderlineSpan();else->StrikethroughSpan()},a,b,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSelection(a,b)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_DOWN){downX=event.x;downY=event.y;downAt=event.eventTime}
        if(event.action==MotionEvent.ACTION_UP&&!locked) {
            val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
            if(event.eventTime-downAt<android.view.ViewConfiguration.getLongPressTimeout() &&
                kotlin.math.abs(event.x-downX)<=slop && kotlin.math.abs(event.y-downY)<=slop)onPointerFocus?.invoke()
        }

        if(event.action==MotionEvent.ACTION_UP&&!locked&&isFocusableInTouchMode&&!sourceMode){
            val line=layout?.getLineForVertical((event.y-totalPaddingTop+scrollY).toInt())?:0
            val start=layout?.getLineStart(line)?:0
            if(event.x<totalPaddingLeft+48 && start<length()&&text[start] in "☐☑"){
                change{text.replace(start,start+1,if(text[start]=='☐')"☑" else "☐")};return true
            }
            if(blankTapNewLine&&event.y>totalPaddingTop+(layout?.height?:height)-scrollY){
                if(text.isNotEmpty()&&!text.endsWith("\n"))text.append('\n')
                requestFocus();setSelection(length());(context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);return true
            }
        }
        return super.onTouchEvent(event)
    }
}


internal fun editorSelectionFormats(inlineOnly:Boolean):List<Pair<String,String>> =
    listOf("Жирный" to "bold","Курсив" to "italic") +
        (if(inlineOnly)emptyList() else listOf("Подчеркнуть" to "underline","Зачеркнуть" to "strike",
            "Обычный текст" to "paragraph","Заголовок" to "h1","Подзаголовок" to "h2",
            "Маркированный список" to "bullet","Нумерованный список" to "number","Чек-лист" to "check")) +
        listOf("Сбросить оформление" to "clear")

/** Rules follow actual line metrics, including headings and wrapped lines; blank paper continues below. */
internal fun noteRulePositions(layout:android.text.Layout,paddingTop:Float,scrollY:Float,viewportHeight:Float,normalStep:Float,extra:Float):List<Float> {
    val result=mutableListOf<Float>()
    val bottom=scrollY+viewportHeight
    var last=paddingTop
    for(i in 0 until layout.lineCount) {
        if(layout.getLineBottom(i)<=layout.getLineTop(i))continue
        // Descenders are inside lineBottom; a small portion of the interline gap stays below the ink.
        val inkBottom=layout.getLineBaseline(i)+layout.getLineDescent(i)
        val y=paddingTop+maxOf(inkBottom.toFloat(),layout.getLineBottom(i)-extra*.35f)
        if(y>last+.5f){if(y>=scrollY)result.add(y);last=y}
    }
    val step=normalStep.coerceAtLeast(1f)
    var y=last+step
    // Skip off-screen blank rows without iterating over an arbitrarily long scroll offset.
    if(y<scrollY)y+=kotlin.math.ceil((scrollY-y)/step)*step
    while(y<bottom){result.add(y);y+=step}
    return result
}


/** Keeps the emitted model and its native revision atomic in every Compose snapshot. */
internal class EditorModelClock {
    var revision by mutableLongStateOf(0L)
    fun publish(nativeRevision:Long,updateModel:()->Unit) {
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            revision=nativeRevision
            updateModel()
        }
    }
}
