package team.kotiki.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VisibilityCreationTest {
    @Test fun editingPreservesOriginalVisibilityAndStillSavesContent() {
        val prefix="visibility_${UUID.randomUUID()}_"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
        }
        val store=DemoStore(context)
        val today=LocalDate.now()
        Visibility.entries.forEach { mode ->
            val original=Entry("fixed_${mode.name}","Initial",today,mondayOf(today),owner=store.viewer,visibility=mode)
            store.upsert(original)
            Visibility.entries.filter{it!=mode}.forEach { attempted ->
                store.upsert(original.copy(title="Updated ${attempted.name}",visibility=attempted))
                val saved=DemoStore(context).entries.single{it.id==original.id}
                assertEquals(mode,saved.visibility)
                assertEquals("Updated ${attempted.name}",saved.title)
            }
        }
    }
}
