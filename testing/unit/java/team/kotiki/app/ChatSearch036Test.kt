package team.kotiki.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ChatSearch036Test {
    @Test fun longResultShowsTheMatchInsteadOfOnlyItsBeginning() {
        val text="Начало "+"длинная история ".repeat(40)+"ИСКОМОЕ слово "+"конец ".repeat(40)
        val excerpt=chatSearchExcerpt(text,"искомое")
        assertTrue(excerpt.contains("ИСКОМОЕ"));assertTrue(excerpt.startsWith("…"));assertTrue(excerpt.length<=182)
    }
    @Test fun highlightIsLiteralCaseInsensitiveAndCoversEveryMatch() {
        val rich=chatSearchHighlighted("Кот [a] кот [A]","[a]",Color.Blue)
        assertEquals("Кот [a] кот [A]",rich.text)
        assertEquals(listOf(4 to 7,12 to 15),rich.spanStyles.map{it.start to it.end})
        assertTrue(chatSearchHighlighted("Text","",Color.Blue).spanStyles.isEmpty())
    }
}
