package team.kotiki.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EditorSelection036Test {
    private fun editor()=VisualEditText(ApplicationProvider.getApplicationContext()).apply{setContent("",MARKDOWN_TEXT_FORMAT)}

    @Test fun selectionFormattingIncludesListsAndHeadingsWithoutSourceOrHistoryCommands() {
        val formats=editorSelectionFormats(false).map{it.second}
        assertTrue(formats.containsAll(listOf("bold","italic","underline","strike","paragraph","h1","h2","bullet","number","check","clear")))
        assertFalse(formats.any{it in listOf("source","md","undo","redo")})
        assertEquals(listOf("bold","italic","clear"),editorSelectionFormats(true).map{it.second})
        val field=editor();field.text.append("one\ntwo");field.setSelection(0,field.length());field.formatSelection("number")
        assertEquals("1. one\n2. two",field.emitted)
        field.setSelection(0,field.length());field.formatSelection("check")
        assertEquals("- [ ] one\n- [ ] two",field.emitted)
        assertTrue(field.text.getSpans(0,field.length(),RichParagraph::class.java).all{it.kind=="check"})
        field.setSelection(0,field.length());field.formatSelection("h1")
        field.setSelection(0,field.length());field.formatSelection("paragraph")
        assertEquals("one\ntwo",field.emitted)
        assertTrue(field.text.getSpans(0,field.length(),RichParagraph::class.java).isEmpty())
        assertTrue(field.text.getSpans(0,field.length(),android.text.style.RelativeSizeSpan::class.java).isEmpty())
    }

    @Test fun tappingAlreadyFocusedFieldIsExplicitButLongPressAndDragAreNot() {
        val field=editor();field.layout(0,0,300,150);field.requestFocus()
        var taps=0;field.onPointerFocus={taps++}
        fun gesture(duration:Long,endY:Float=20f) {
            val down=android.view.MotionEvent.obtain(100,100,android.view.MotionEvent.ACTION_DOWN,20f,20f,0)
            val up=android.view.MotionEvent.obtain(100,100+duration,android.view.MotionEvent.ACTION_UP,20f,endY,0)
            field.onTouchEvent(down);field.onTouchEvent(up);down.recycle();up.recycle()
        }
        gesture(80);gesture(80);assertEquals(2,taps)
        gesture(android.view.ViewConfiguration.getLongPressTimeout()+100L);assertEquals(2,taps)
        gesture(80,120f);assertEquals(2,taps)
    }

    @Test fun typedMarkdownListStartsAndContinuesWithoutSourceMode() {
        val field=editor()
        "- Milk".forEach{field.text.append(it)}
        assertEquals("• Milk",field.text.toString())
        field.text.append('\n');field.text.append("Coffee")
        assertEquals("- Milk\n- Coffee",field.emitted)
        assertEquals("• Milk\n• Coffee",field.text.toString())
    }

    @Test fun compositionCompletionConvertsNewMarkupWithoutReinterpretingLegacyLiteral() {
        val field=editor();field.setContent("**legacy** ",PLAIN_TEXT_FORMAT)
        // IME provisional text must not be rewritten until the composition is committed.
        val composing=SpannableStringBuilder("**new**");BaseInputConnection.setComposingSpans(composing)
        field.text.append(composing)
        assertEquals("**legacy** **new**",field.text.toString())
        BaseInputConnection.removeComposingSpans(field.text)
        field.finishMarkdownComposition()
        assertEquals("**legacy** new",field.text.toString())
        assertTrue(field.text.getSpans(11,14,StyleSpan::class.java).any{it.style==Typeface.BOLD})
        assertEquals("**legacy** new",richPlainText(field.emitted,MARKDOWN_TEXT_FORMAT))
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun ruleLinesFollowWrappedAndLargeHeadingMetricsAndContinueOverBlankPaper() {
        val text=RichTextCodec.decode("# Large heading\nA long ordinary paragraph that wraps across the available space.\nLast")
        val paint=TextPaint().apply{textSize=32f}
        val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,190).setIncludePad(false).setLineSpacing(8f,1f).build()
        val rules=noteRulePositions(layout,8f,0f,700f,46f,8f)
        assertTrue(layout.lineCount>3)
        assertTrue(rules.size>layout.lineCount)
        for(i in 0 until layout.lineCount)assertTrue(rules[i]>=8+layout.getLineBaseline(i)+layout.getLineDescent(i))
        assertTrue(rules.zipWithNext().all{(a,b)->b>a})
        assertEquals(46f,rules.last()-rules[rules.lastIndex-1],.01f)
        val scrolled=noteRulePositions(layout,8f,200f,300f,46f,8f)
        assertTrue(scrolled.all{it>=200f})
        assertTrue(scrolled.filter{it<500f}.all{value->rules.any{kotlin.math.abs(it-value)<.01f}})
    }
}
