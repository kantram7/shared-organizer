package team.kotiki.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RichTextTest {
    @Test fun legacyMarkersStayLiteralAfterMigrationAndEditing() {
        val old="**stars** _underscores_ <u>literal</u> # title"
        assertEquals(old,richPlainText(old,PLAIN_TEXT_FORMAT))
        val encoded=RichTextCodec.encode(RichTextCodec.decode(old,PLAIN_TEXT_FORMAT))
        assertEquals(old,richPlainText(encoded,MARKDOWN_TEXT_FORMAT))
        val blocks=listOf(NoteBlock(text="**literal**"),NoteBlock(kind=NoteBlockKind.CHECK,text="Buy milk",checked=true))
        assertEquals("**literal**\n☑ Buy milk",richPlainText(legacyNoteMarkdown(blocks),MARKDOWN_TEXT_FORMAT))
    }
    @Test fun stylesListsAndLinksRoundTripWithoutMarkersInVisualText() {
        val source="# Heading\n**Bold** _italic_ <u>underline</u> ~~gone~~\n- Bullet\n1. Number\n- [ ] Todo\n- [x] Done\n[site](https://example.com)"
        val first=RichTextCodec.decode(source)
        assertEquals("Heading\nBold italic underline gone\n• Bullet\n1. Number\n☐ Todo\n☑ Done\nsite",first.toString())
        val second=RichTextCodec.decode(RichTextCodec.encode(first))
        assertEquals(first.toString(),second.toString())
        assertEquals(RichTextCodec.encode(first),RichTextCodec.encode(second))
    }
    @Test fun overlappingStylesPreserveVisibleTextAndStyleRanges() {
        val text=SpannableStringBuilder("one two three")
        text.setSpan(StyleSpan(Typeface.BOLD),0,7,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.ITALIC),4,13,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val decoded=RichTextCodec.decode(RichTextCodec.encode(text))
        assertEquals(text.toString(),decoded.toString())
        assertTrue(decoded.getSpans(4,7,StyleSpan::class.java).any{it.style==Typeface.BOLD})
        assertTrue(decoded.getSpans(4,7,StyleSpan::class.java).any{it.style==Typeface.ITALIC})
    }
    @Test fun unknownMarkupIsNotDropped() {
        val text="<table><tr><td>Keep me</td></tr></table>\n```kotlin\nx()\n```"
        val visual=RichTextCodec.decode(text)
        assertEquals(text,visual.toString())
        assertEquals(text,RichTextCodec.decode(RichTextCodec.encode(visual)).toString())
    }
    @Test fun noteFormatAndSearchSurviveRestart() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        var store=NotesStore(context,Person.USER_1);store.file.delete();store=NotesStore(context,Person.USER_1)
        store.addGroup("Local",false,true);store.addNote(store.groups.single())
        store.edit(store.notes.single().copy(title="**Title**",titleFormat=MARKDOWN_TEXT_FORMAT,blocks=emptyList(),markdown="A **searchable** note",textFormat=MARKDOWN_TEXT_FORMAT))
        store.close()
        val restored=NotesStore(context,Person.USER_1).notes.single()
        assertEquals("Title",restored.label);assertEquals("A searchable note",restored.preview)
        assertTrue(restored.matches("searchable"));assertEquals(MARKDOWN_TEXT_FORMAT,restored.textFormat)
    }
    @Test fun nativeSelectionFormatsAcrossParagraphsAndUndoRestores() {
        val view=VisualEditText(ApplicationProvider.getApplicationContext())
        view.setContent("one\ntwo",PLAIN_TEXT_FORMAT);view.setSelection(0,7);view.formatSelection("bold")
        assertEquals("**one**\n**two**",view.emitted)
        view.history(-1);assertEquals("one\ntwo",view.emitted)
    }
    @Test fun enterContinuesListAndEmptyItemLeavesIt() {
        val view=VisualEditText(ApplicationProvider.getApplicationContext())
        view.setContent("- Item",MARKDOWN_TEXT_FORMAT);view.setSelection(view.length());view.text.append('\n')
        assertEquals("• Item\n• ",view.text.toString())
        view.text.append('\n');assertEquals("• Item\n",view.text.toString())
    }
    @Test fun typingMarkdownConvertsOnlyTheNewRunAndKeepsLegacyLiterals() {
        val view=VisualEditText(ApplicationProvider.getApplicationContext())
        view.setContent("**old literal** ",PLAIN_TEXT_FORMAT)
        "**new bold**".forEach{view.text.append(it)}
        assertEquals("**old literal** new bold",view.text.toString())
        assertEquals("**old literal** new bold",richPlainText(view.emitted,MARKDOWN_TEXT_FORMAT))
        view.switchSource(true);view.switchSource(false)
        assertEquals("**old literal** new bold",view.text.toString())
    }
    @Test fun titleOnlyAllowsBoldItalicAndHeadingEnterReturnsToNormalParagraph() {
        val title=VisualEditText(ApplicationProvider.getApplicationContext()).apply{inlineOnly=true}
        title.setContent("",PLAIN_TEXT_FORMAT);title.text.append("# Heading")
        assertEquals("# Heading",title.text.toString())
        assertTrue(title.text.getSpans(0,title.length(),RichParagraph::class.java).isEmpty())
        val note=VisualEditText(ApplicationProvider.getApplicationContext())
        note.setContent("# Head",MARKDOWN_TEXT_FORMAT);note.text.append("ing")
        assertEquals(note.length(),note.text.getSpanEnd(note.text.getSpans(0,note.length(),android.text.style.RelativeSizeSpan::class.java).single()))
        note.text.append('\n');note.text.append("Body")
        assertEquals("# Heading\nBody",note.emitted)
    }
    @Test fun completedFoldingChangesOnlyPresentationAndKeepsFollowingLinesVisible() {
        val body="Before\n- [x] Done one\n- [x] Done two\n- [ ] Todo\nAfter"
        val text=RichTextCodec.decode(body)
        val paint=android.text.TextPaint().apply{textSize=32f}
        fun layout()=android.text.StaticLayout.Builder.obtain(text,0,text.length,paint,600).setIncludePad(false).build()
        val expandedHeight=layout().height
        applyCompletedFolding(text,true)
        assertEquals(2,completedCheckRanges(text).size)
        assertEquals(1,text.getSpans(0,text.length,CollapsedCheckSpan::class.java).size)
        assertEquals(body,RichTextCodec.encode(text))
        val foldedLayout=layout()
        assertTrue(foldedLayout.height<expandedHeight)
        val after=foldedLayout.getLineForOffset(text.indexOf("After"))
        assertTrue(foldedLayout.getLineBottom(after)>foldedLayout.getLineTop(after))
        applyCompletedFolding(text,false)
        assertEquals(expandedHeight,layout().height)
        assertEquals(body,RichTextCodec.encode(text))
    }
    @Test fun parsedParagraphFormattingNeverSpillsIntoFollowingPlainParagraph() {
        val source="# Heading\nPlain\n- [ ] Todo\nAfter\n- Bullet\nLast"
        val parsed=RichTextCodec.decode(source)
        assertEquals(source,RichTextCodec.encode(parsed))
        for(word in listOf("Plain","After","Last")){
            val start=parsed.indexOf(word)
            assertTrue(parsed.getSpans(start,start+word.length,RichParagraph::class.java).none{parsed.getSpanStart(it)<start&&parsed.getSpanEnd(it)>start})
            assertTrue(parsed.getSpans(start,start+word.length,android.text.style.RelativeSizeSpan::class.java).isEmpty())
        }
    }
    @Test fun editingHiddenBoundaryUnfoldsBeforeBackspaceAndDoesNotLoseDoneText() {
        val view=VisualEditText(ApplicationProvider.getApplicationContext())
        view.setContent("- [x] Done\nAfter",MARKDOWN_TEXT_FORMAT);view.setFoldCompleted(true)
        var unfolded=false;view.onUnfoldRequested={unfolded=true}
        val after=view.text.indexOf("After")
        view.text.delete(after-1,after)
        assertTrue(unfolded);assertTrue(view.text.toString().contains("Done"))
        assertEquals(0,view.text.getSpans(0,view.length(),CollapsedCheckSpan::class.java).size)
    }
    @Test fun selectedTextIncludesOnlyValidatedOpaqueSourceLink() {
        val note=sourceDeepLink("note","note-123")!!
        assertEquals("Text\n\nИсточник: kotiki://note/note-123",selectionWithSource("Text",note))
        assertEquals("kotiki://plan/group-1/plan-2",sourceDeepLink("plan","group-1","plan-2"))
        assertNull(sourceDeepLink("note","../../secret"))
        assertEquals("Text",selectionWithSource("Text","https://attacker.test/"))
        assertEquals("Text",selectionWithSource("Text","kotiki://note/note-1?admin=true"))
    }
}
