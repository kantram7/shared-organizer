package team.kotiki.app

import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EditorAcknowledgement036Test {
    @Test fun delayedComposeAcknowledgementCannotEraseLaterKeystrokes() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        field.setContent("",MARKDOWN_TEXT_FORMAT)
        field.append("Q");field.append("A");val qaRevision=field.editRevision
        field.append("0");field.append("3");val qa03Revision=field.editRevision
        field.append("6")
        field.acceptModel("QA",MARKDOWN_TEXT_FORMAT,qaRevision)
        assertEquals("QA036",field.text.toString())
        field.acceptModel("QA03",MARKDOWN_TEXT_FORMAT,qa03Revision)
        assertEquals("QA036",field.text.toString())
        field.acceptModel("QA036",MARKDOWN_TEXT_FORMAT)
        field.acceptModel("",MARKDOWN_TEXT_FORMAT)
        assertEquals("",field.text.toString())
    }
    @Test fun realExternalReplacementStillApplies() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        field.setContent("Before",MARKDOWN_TEXT_FORMAT)
        field.acceptModel("After",MARKDOWN_TEXT_FORMAT)
        assertEquals("After",field.text.toString())
        field.append("!")
        field.acceptModel("After",MARKDOWN_TEXT_FORMAT)
        assertEquals("After!",field.text.toString())
    }
    @Test fun formatOnlyExternalChangeIsAppliedInBothDirections() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        field.setContent("**x**",PLAIN_TEXT_FORMAT)
        field.acceptModel("**x**",MARKDOWN_TEXT_FORMAT)
        assertEquals("x",field.text.toString())
        field.acceptModel("**x**",PLAIN_TEXT_FORMAT)
        assertEquals("**x**",field.text.toString())
    }
    @Test fun burstBeyond128AndReorderedModelUpdatesNeverTruncateNativeText() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        field.setContent("",MARKDOWN_TEXT_FORMAT)
        val emitted=mutableListOf<Pair<String,Long>>()
        field.onEmit={value->emitted.add(value to field.editRevision)}
        val expected="QA036-"+"abcdefghijklmnopqrstuvwxyz0123456789".repeat(15)+"-END"
        expected.forEach{field.text.append(it)}
        // ACKs may arrive long after their composition, and older lambdas can run last.
        listOf(20,145,400,emitted.lastIndex,128,144,1).forEach{index->
            val (value,revision)=emitted[index]
            field.acceptModel(value,MARKDOWN_TEXT_FORMAT,revision)
            assertEquals("Delayed update $index lost native input",expected,field.text.toString())
        }
        field.acceptModel("",MARKDOWN_TEXT_FORMAT,field.editRevision)
        assertEquals("",field.text.toString())
        // A pre-clear model cannot resurrect a sent/cleared draft either.
        field.acceptModel(emitted.last().first,MARKDOWN_TEXT_FORMAT,emitted.last().second)
        assertEquals("",field.text.toString())
    }
    @Test fun latestAcknowledgementPreservesSelectedRangeAndExplicitReplacementStillWorks() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        field.setContent("",PLAIN_TEXT_FORMAT)
        field.text.append("**bold**")
        field.setSelection(0,field.length())
        field.acceptModel(field.emitted,MARKDOWN_TEXT_FORMAT,field.editRevision)
        assertEquals(0,field.selectionStart);assertEquals(4,field.selectionEnd)
        field.acceptModel("Replacement",PLAIN_TEXT_FORMAT,field.editRevision)
        assertEquals("Replacement",field.text.toString())
    }

    @Test fun frozenCompositionCannotPairItsOldValueWithTheLiveNativeRevision() {
        val field=VisualEditText(ApplicationProvider.getApplicationContext())
        val clock=EditorModelClock()
        var model by mutableStateOf("")
        field.onModelRevision={clock.revision=it}
        field.setContent("",MARKDOWN_TEXT_FORMAT)
        field.onEmit={next->clock.publish(field.editRevision){model=next}}
        field.text.append("Old model")
        val frozen=Snapshot.takeSnapshot()
        try {
            val prefix=model
            " new characters".repeat(25).forEach{field.text.append(it)}
            val expected=field.text.toString()
            val (oldValue,oldRevision)=frozen.enter{model to clock.revision}
            assertEquals(prefix,oldValue)
            assertTrue(oldRevision<field.editRevision)
            field.acceptModel(oldValue,MARKDOWN_TEXT_FORMAT,oldRevision)
            assertEquals(expected,field.text.toString())
            assertEquals(expected,model)
        } finally {frozen.dispose()}
    }

}


@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EditorBridge036UiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun field():VisualEditText {
        fun find(view:android.view.View):VisualEditText? {
            if(view is VisualEditText && view.contentDescription=="Burst input")return view
            if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
            return null
        }
        return requireNotNull(find(ui.activity.window.decorView))
    }
    @Test fun burstAndDelayedComposeFramePreserveNativeTextThenExternalClearApplies() {
        var model by mutableStateOf("")
        ui.activityRule.scenario.onActivity{activity->activity.setContent{
            KotikiTheme("light"){RichTextEditor(model,{model=it},format=MARKDOWN_TEXT_FORMAT,label="Burst input",compact=true,minLines=1,maxLines=5)}
        }}
        ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        val expected="QA036-BEGIN "+"abcdefghijklmnopqrstuvwxyz0123456789 ".repeat(12)+" END"
        ui.runOnUiThread {
            field().requestFocus()
            expected.forEach{field().text.append(it)}
            assertEquals(expected,field().text.toString())
        }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.autoAdvance=true
        ui.waitForIdle()
        ui.runOnIdle{assertEquals(expected,field().text.toString());assertEquals(expected,richPlainText(model,MARKDOWN_TEXT_FORMAT));model=""}
        ui.waitForIdle()
        ui.runOnIdle{assertEquals("",field().text.toString());assertEquals("",model)}
    }
}
