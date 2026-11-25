package team.kotiki.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One editor instance survives the change between inline and expanded controls. */
@Composable internal fun ChatComposer(value:String,onChange:(String)->Unit,onSend:()->Unit,onAttach:()->Unit,onFocus:()->Unit={}) {
    val measurer=rememberTextMeasurer()
    val plain=remember(value){richPlainText(value,MARKDOWN_TEXT_FORMAT)}
    val textStyle=LocalTextStyle.current.copy(fontSize=16.sp)
    Layout(modifier=Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp).testTag("chat-composer"),content={
        RichTextEditor(value,onChange,Modifier.heightIn(min=40.dp).testTag("chat-composer-text"),format=MARKDOWN_TEXT_FORMAT,maxLength=4000,label="Сообщение",minLines=1,maxLines=5,compact=true,onExplicitFocus=onFocus)
        IconButton(onAttach,Modifier.size(40.dp).testTag("chat-attach").semantics{contentDescription="Прикрепить файлы"}){Icon(painterResource(R.drawable.ic_chat_attach),null,Modifier.size(20.dp))}
        FilledIconButton(onSend,Modifier.size(40.dp).testTag("chat-send").semantics{contentDescription="Отправить сообщение"},enabled=value.isNotBlank(),shape=CircleShape){Icon(painterResource(R.drawable.ic_send_plane),null,Modifier.size(25.dp))}
    }){children,constraints->
        val width=constraints.maxWidth
        val button=40.dp.roundToPx();val gap=4.dp.roundToPx()
        val narrow=(width-button*2-gap*2).coerceAtLeast(1)
        // Decide against the narrow width, so expansion cannot oscillate when
        // the same text fits on one line after gaining the full editor width.
        val multiline=plain.contains('\n') || measurer.measure(AnnotatedString(plain),textStyle,
            constraints=Constraints(maxWidth=(narrow-6.dp.roundToPx()).coerceAtLeast(1))).lineCount>1
        val editorWidth=if(multiline)width else narrow
        val editor=children[0].measure(Constraints(minWidth=editorWidth,maxWidth=editorWidth))
        val attach=children[1].measure(Constraints.fixed(button,button))
        val send=children[2].measure(Constraints.fixed(button,button))
        val height=if(multiline)editor.height+gap+button else maxOf(editor.height,button)
        layout(width,height){
            editor.placeRelative(0,if(multiline)0 else (height-editor.height)/2)
            attach.placeRelative(if(multiline)0 else width-button*2-gap,height-button)
            send.placeRelative(width-button,height-button)
        }
    }
}
