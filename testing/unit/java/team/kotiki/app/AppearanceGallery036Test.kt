package team.kotiki.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AppearanceGallery036Test {
    @Test fun appearanceSurvivesUnauthenticatedStartupAndProfileFallback() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_appearance",0).edit().clear().commit()
        context.getSharedPreferences("kotiki_demo_v1",0).edit().clear().commit()
        assertEquals("light",AppAppearance.read(context))
        AppAppearance.save(context,"dark")
        val store=DemoStore(context)
        assertEquals("dark",store.theme)
        store.updateTheme("system")
        assertEquals("system",AppAppearance.read(context))
        // No Firebase user or account-dependent store is needed by the startup palette.
        context.getSharedPreferences("app_appearance",0).edit().putString("theme","invalid").commit()
        assertEquals("light",AppAppearance.read(context))
        context.getSharedPreferences("app_appearance",0).edit().clear().commit()
        context.getSharedPreferences("kotiki_demo_v1",0).edit().clear().commit()
    }
    @Test fun nativePickerDialogsResolveSelectedPalette() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        listOf(R.style.KotikiPickerLight to Color.rgb(0x75,0x50,0x8C),
            R.style.KotikiPickerDark to Color.rgb(0xDA,0xC0,0xEE),
            R.style.KotikiPickerWinter to Color.rgb(0x28,0x66,0xA5)).forEach{(style,accent)->
            val themed=android.view.ContextThemeWrapper(context,style)
            val dialogs=listOf(android.app.DatePickerDialog(themed,null,2026,10,25),android.app.TimePickerDialog(themed,null,19,0,true))
            dialogs.forEach{dialog->
                val value=android.util.TypedValue()
                assertTrue(dialog.context.theme.resolveAttribute(android.R.attr.colorAccent,value,true))
                assertEquals(accent,value.data)
            }
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun zoomedPhotoCannotDrawOutsideItsViewport() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val view=OriginalPhotoView(context,{})
        view.layout(0,0,100,100)
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        fun field(name:String)=OriginalPhotoView::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("preview").set(view,source)
        field("rawWidth").setInt(view,100);field("rawHeight").setInt(view,100)
        (field("imageMatrix").get(view) as Matrix).setScale(3f,3f)
        val output=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        assertEquals(Color.RED,output.getPixel(50,50))
        assertEquals(Color.TRANSPARENT,output.getPixel(150,50))
        assertEquals(Color.TRANSPARENT,output.getPixel(50,150))
        view.release();output.recycle()
    }
}
