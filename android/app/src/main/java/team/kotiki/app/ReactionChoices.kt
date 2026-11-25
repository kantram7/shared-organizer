package team.kotiki.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal object ReactionChoices {
    val base=listOf("💜","🐾","👍","😊","🔥","👌","🤔","❤️","👀")
    val winter=listOf("❄️","🥶")
    val all=base+winter
    fun available(winterTheme:Boolean)=if(winterTheme)all else base
    fun decode(data:Map<String,Any>?):List<String> {
        val raw=(data?.get("emojis") as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(data?.get("emoji") as? String)
        return raw.filter{it in all}.distinct().take(3)
    }
    fun toggle(current:List<String>,emoji:String,winterTheme:Boolean):List<String> {
        require(emoji in all){"Неизвестная реакция"}
        if(emoji in current)return current-emoji
        require(emoji in available(winterTheme)){"Эта реакция доступна в зимней теме"}
        require(current.size<3){"Можно выбрать до трёх реакций. Сначала убери одну."}
        return current+emoji
    }
}

@Composable internal fun ReactionPicker(selected:List<String>,winter:Boolean,busy:Boolean,onToggle:(String)->Unit) {
    Row(Modifier.fillMaxWidth().clipToBounds().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp,vertical=4.dp)) {
        ReactionChoices.available(winter).forEach { emoji ->
            val chosen=emoji in selected
            TextButton({onToggle(emoji)},enabled=!busy && (chosen || selected.size<3),
                modifier=Modifier.size(44.dp).semantics { this.selected=chosen;contentDescription="Реакция $emoji" }
                    .then(if(chosen)Modifier.background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(10.dp)).border(1.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(10.dp)) else Modifier),
                contentPadding=PaddingValues(0.dp)){Text(emoji)}
        }
    }
}
