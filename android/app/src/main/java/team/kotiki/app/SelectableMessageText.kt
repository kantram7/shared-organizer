package team.kotiki.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun SelectableMessageText(message:ChatMessage,modifier:Modifier=Modifier){
    val currentMessage by rememberUpdatedState(message)
    val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    val create by rememberUpdatedState(LocalCreateTask.current)
    val internal by rememberUpdatedState(LocalOpenInternalLink.current)
    val rich=remember(message.text,message.format,message.deleted){
        val value=if(message.deleted)SpannableStringBuilder("Сообщение удалено")else RichTextCodec.decode(message.text,message.format)
        val existing=value.getSpans(0,value.length,URLSpan::class.java).map{WebLink(value.getSpanStart(it),value.getSpanEnd(it),it.url)}
        value.getSpans(0,value.length,URLSpan::class.java).forEach(value::removeSpan)
        val all=existing+webLinks(value.toString()).filter{candidate->existing.none{candidate.start<it.end&&candidate.end>it.start}}+
            Regex("kotiki://[^\\s<>]+").findAll(value).map{WebLink(it.range.first,it.range.last+1,it.value)}.toList()
        all.forEach{link->value.setSpan(object:URLSpan(link.url){override fun onClick(widget:android.view.View){if(link.url.startsWith("kotiki://"))internal?.invoke(link.url)else openWebLink(context,link.url)}},link.start,link.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        value
    }
    AndroidView(factory={ctx->TextView(ctx).apply{
        textSize=14f;includeFontPadding=false;setPadding(0,5,0,5);setTextIsSelectable(true)
        customSelectionActionModeCallback=object:ActionMode.Callback{
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean{if(create!=null && !currentMessage.deleted)menu.add(0,8201,20,"Создать задачу");return true}
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onDestroyActionMode(mode:ActionMode){}
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean{
                if(item.itemId!=8201)return false
                val a=minOf(selectionStart,selectionEnd).coerceAtLeast(0);val b=maxOf(selectionStart,selectionEnd).coerceIn(a,text.length)
                create?.invoke(text.substring(a,b)+"\n\nИсточник: kotiki://chat/${currentMessage.chatId}/message/${currentMessage.id}");mode.finish();return true
            }
        }
    }},update={view->if(view.tag!=Triple(message.text,message.format,message.deleted)){view.text=rich;view.tag=Triple(message.text,message.format,message.deleted)};view.contentDescription=rich.toString();view.setTextColor(colors.onSurface.toArgb());view.setLinkTextColor(colors.primary.toArgb())},modifier=modifier)
}
