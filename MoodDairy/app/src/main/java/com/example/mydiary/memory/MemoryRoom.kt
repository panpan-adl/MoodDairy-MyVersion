package com.example.mydiary.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue

@Composable
internal fun MemoryRoom(theme:String,items:List<JSONObject>,onClick:(JSONObject)->Unit) {
    val glow by rememberInfiniteTransition(label="room-light").animateFloat(0.35f,0.65f,infiniteRepeatable(tween(4000),RepeatMode.Reverse),label="soft-light")
    val wall=when(theme){"bedroom"->Color(0xFFF1E9F8);"cabin"->Color(0xFFD9EAD3);else->Color(0xFFF7EFD8)}
    BoxWithConstraints(Modifier.fillMaxWidth().height(370.dp).background(wall,RoundedCornerShape(22.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width; val h=size.height
            drawRect(Color(0xFFCDAF8E),Offset(0f,h*.78f),Size(w,h*.22f))
            for(i in 1..5) drawLine(Color(0xFFBB9A77),Offset(w*i/6,h*.78f),Offset(w*i/6,h),2f)
            drawLine(Color(0xFFB89472),Offset(0f,h*.9f),Offset(w,h*.9f),2f)
            drawOval(Color(0xFFEEE2CC),Offset(w*.2f,h*.82f),Size(w*.6f,h*.13f))
            drawRoundRect(Color(0xFFA58665),Offset(w*.65f,h*.065f),Size(w*.28f,h*.26f),androidx.compose.ui.geometry.CornerRadius(10f))
            drawRect(Color(0xFFBBDCDC),Offset(w*.67f,h*.08f),Size(w*.24f,h*.23f))
            drawCircle(if(theme=="bedroom") Color(0xFFFFF5CC) else Color(0xFFFFE6A2),w*.03f,Offset(w*.86f,h*.12f))
            drawOval(Color(0xFF94B5A4),Offset(w*.67f,h*.24f),Size(w*.24f,h*.08f))
            drawLine(Color.White,Offset(w*.79f,h*.08f),Offset(w*.79f,h*.31f),6f)
            drawLine(Color.White,Offset(w*.67f,h*.20f),Offset(w*.91f,h*.20f),6f)
            for(y in listOf(.32f,.54f,.76f)) drawRect(Color(0xFFAD8862),Offset(w*.05f,h*y),Size(w*.9f,h*.02f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF7B3).copy(alpha=glow),Color.Transparent)),w*.35f,Offset(w*.22f,h*.1f))
            if(theme=="bedroom") {
                drawRoundRect(Color(0xFFAE95B8),Offset(w*.1f,h*.79f),Size(w*.55f,h*.13f),androidx.compose.ui.geometry.CornerRadius(20f))
                drawRoundRect(Color(0xFFE5D7E9),Offset(w*.12f,h*.81f),Size(w*.51f,h*.1f),androidx.compose.ui.geometry.CornerRadius(16f))
                drawRoundRect(Color.White,Offset(w*.14f,h*.82f),Size(w*.13f,h*.05f),androidx.compose.ui.geometry.CornerRadius(12f))
            } else {
                drawRoundRect(Color(0xFF89684D),Offset(w*.1f,h*.81f),Size(w*.44f,h*.035f),androidx.compose.ui.geometry.CornerRadius(8f))
                drawRect(Color(0xFF89684D),Offset(w*.13f,h*.84f),Size(w*.025f,h*.1f))
                drawRect(Color(0xFF89684D),Offset(w*.49f,h*.84f),Size(w*.025f,h*.1f))
                drawRoundRect(Color(0xFF789A8C),Offset(w*.2f,h*.785f),Size(w*.15f,h*.022f),androidx.compose.ui.geometry.CornerRadius(3f))
            }
            drawRoundRect(Color(0xFFB8795E),Offset(w*.8f,h*.88f),Size(w*.1f,h*.075f),androidx.compose.ui.geometry.CornerRadius(9f))
            drawLine(Color(0xFF527C57),Offset(w*.85f,h*.9f),Offset(w*.85f,h*.76f),5f)
            for(i in 0..3) drawOval(Color(0xFF78A47D),Offset(w*(.78f+i*.015f),h*(.83f-i*.025f)),Size(w*.09f,h*.04f))
        }
        for(item in items) {
            val slot=item.getInt("slot"); val letter=item.optJSONObject("letter")
            val icon=when(item.getString("kind")){"book"->Icons.Outlined.MenuBook;"frame"->Icons.Outlined.Photo;"recorder"->Icons.Outlined.Mic;"mailbox"->Icons.Outlined.Mail;else->Icons.Outlined.Stars}
            Column(Modifier.offset(x=maxWidth*(.06f+(slot%4)*.23f),y=370.dp*(.16f+(slot/4)*.22f))
                .width(maxWidth*.2f).clickable {onClick(item)},horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(icon,contentDescription=item.optString("title","一段经历"),tint=if(letter?.optBoolean("due")==true) Color(0xFF926323) else Color(0xFF47665B),modifier=Modifier.size(36.dp))
                Text(item.optString("title").ifBlank{"记忆"}.take(7),style=MaterialTheme.typography.labelSmall,maxLines=1)
                if(letter?.optBoolean("due")==true&&letter.optString("state")=="sealed") Text("到期啦",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
