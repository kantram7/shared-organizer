package team.kotiki.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun PinKeypad(pin:String,length:Int,enabled:Boolean,biometric:Boolean,onDigit:(String)->Unit,onBackspace:()->Unit,
    onBiometric:()->Unit,onRecover:()->Unit,error:String?) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=28.dp,vertical=24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        BrandMark(Modifier.size(108.dp))
        Text("С возвращением",style=MaterialTheme.typography.headlineSmall)
        Text("Разблокируй Kotiki team",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(vertical=14.dp).semantics{contentDescription="PIN из $length цифр, введено ${pin.length}"},horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            repeat(length){index->Surface(Modifier.size(12.dp),shape=CircleShape,border=BorderStroke(1.5.dp,MaterialTheme.colorScheme.primary),
                color=if(index<pin.length)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface){}}
        }
        Column(Modifier.widthIn(max=300.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("bio","0","back")).forEach{row->
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach { value ->
                        val modifier=Modifier.weight(1f).height(64.dp)
                        when(value) {
                            "bio"->if(biometric)TextButton(onBiometric,modifier,enabled=enabled){Text("Биометрия",style=MaterialTheme.typography.labelSmall)} else Spacer(modifier)
                            "back"->TextButton(onBackspace,modifier.semantics{contentDescription="Стереть цифру"},enabled=enabled && pin.isNotEmpty()){Text("⌫",style=MaterialTheme.typography.headlineSmall)}
                            else->FilledTonalButton({onDigit(value)},modifier,enabled=enabled,shape=RoundedCornerShape(18.dp),contentPadding=PaddingValues(0.dp)) {
                                Text(value,style=MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                }
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Text(if(biometric)"PIN или биометрия" else "Введи PIN",color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onRecover,enabled=enabled){Text("Забыл PIN")}
    }
}
