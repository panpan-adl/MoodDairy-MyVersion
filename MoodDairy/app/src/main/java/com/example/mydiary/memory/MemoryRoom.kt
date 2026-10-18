package com.example.mydiary.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.*
import org.json.JSONObject

internal data class RoomStyle(val key: String, val title: String, val subtitle: String,
    val wall: Color, val light: Color, val wood: Color, val accent: Color, val icon: ImageVector)

internal val RoomStyles = listOf(
    RoomStyle("study", "温暖书房", "把故事慢慢收藏", Color(0xFFF7EBD9), Color(0xFFFFE7AE), Color(0xFFB58B61), Color(0xFF8D6A47), Icons.Outlined.MenuBook),
    RoomStyle("bedroom", "安静卧室", "给心情一点休息", Color(0xFFEEEAF7), Color(0xFFE4DEFA), Color(0xFFB8A5C9), Color(0xFF82739F), Icons.Outlined.NightsStay),
    RoomStyle("cabin", "自然小屋", "和生活一起呼吸", Color(0xFFE4EEDB), Color(0xFFF0F4CE), Color(0xFF9AAB7C), Color(0xFF577F5C), Icons.Outlined.Forest),
)
internal fun roomStyle(theme: String) = RoomStyles.firstOrNull { it.key == theme } ?: RoomStyles.first()
internal fun roomItemIcon(kind: String): ImageVector = when (kind) {
    "book" -> Icons.Outlined.MenuBook
    "frame" -> Icons.Outlined.Photo
    "recorder" -> Icons.Outlined.Mic
    "mailbox" -> Icons.Outlined.MailOutline
    else -> Icons.Outlined.AutoAwesome
}
internal fun roomItemName(kind: String): String = when (kind) {
    "book" -> "日记本"; "frame" -> "照片相框"; "recorder" -> "声音收藏"
    "mailbox" -> "时光信封"; else -> "纪念物"
}

@Composable
internal fun RoomThemePicker(theme: String, enabled: Boolean, onChoose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        RoomStyles.forEach { style ->
            val selected = theme == style.key
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .background(if (selected) Color(0xFFE3F4EC) else Color.White.copy(alpha = .8f))
                .border(if (selected) 1.5.dp else 1.dp, if (selected) PrimaryVariant else Color(0xFFE4ECE5), RoundedCornerShape(16.dp))
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = { onChoose(style.key) }).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(style.wall)) {
                    Canvas(Modifier.fillMaxSize()) { drawRoomPreview(style) }
                    if (selected) Icon(Icons.Outlined.CheckCircle, "已选择", Modifier.align(Alignment.TopEnd).padding(4.dp).size(13.dp), tint = PrimaryDark)
                }
                Text(style.title, color = if (selected) PrimaryDark else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun RoomPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = .92f))
        .border(1.dp, Color(0xFFE2ECE5), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun RoomHeading(title: String, subtitle: String, icon: ImageVector, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF6EF)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = PrimaryDark)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        trailing?.let { Text(it, color = PrimaryDark, fontSize = 11.sp) }
    }
}

/** Slot identities stay stable while the objects are styled as a curated shelf display. */
@Composable
internal fun MemoryRoom(theme: String, items: List<JSONObject>, onEmptySlot: (Int) -> Unit = {}, onClick: (JSONObject) -> Unit) {
    val style = roomStyle(theme)
    // Grow the scene with accessibility text so labels keep their own space below each shelf.
    val roomHeight = 344.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(roomHeight).clip(RoundedCornerShape(24.dp))
        .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize()) { drawRoom(style) }
        Column(Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 19.dp)
            .width(maxWidth * .57f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (theme == "bedroom") "让心情，轻轻落下" else if (theme == "cabin") "让生活，慢慢生长" else "为每段经历，留一盏灯",
                color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(style.subtitle, color = style.accent, fontSize = 10.sp, lineHeight = 16.sp)
        }
        for (slot in 0..11) {
            val item = items.firstOrNull { it.optInt("slot", -1) == slot }
            val column = slot % 4
            val row = slot / 4
            val slotWidth = maxWidth * .205f
            val x = maxWidth * (.055f + column * .229f)
            val shelfY = roomHeight * (.397f + row * .177f)
            val objectHeight = 42.dp
            if (item != null) {
                val kind = item.optString("kind")
                val due = item.optJSONObject("letter")?.let { it.optBoolean("due") && it.optString("state") == "sealed" } == true
                Column(Modifier.offset(x, shelfY - objectHeight).width(slotWidth)
                    .clickable(role = Role.Button, onClick = { onClick(item) }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.width(49.dp).height(objectHeight)) { drawShelfObject(kind, style, due) }
                    Text(if (due) "来信到期啦" else item.optString("title").ifBlank { roomItemName(kind) },
                        modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
                        color = if (due) Color(0xFF9C7136) else TextPrimary.copy(alpha = .85f),
                        fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Box(Modifier.offset(x, shelfY - 42.dp).width(slotWidth).height(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "在位置${slot + 1}放入经历", onClick = { onEmptySlot(slot) }), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(19.dp).clip(CircleShape).background(Color.White.copy(alpha = .5f))
                        .border(1.dp, style.accent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, "空位置 ${slot + 1}", Modifier.size(11.dp), tint = style.accent.copy(alpha = .45f))
                    }
                }
            }
        }
        Text("${items.size} / 12 件收藏", color = style.accent, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = .86f)).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Small silhouettes sit directly on the shelf; labels are laid out separately beneath it. */
private fun DrawScope.drawShelfObject(kind: String, style: RoomStyle, due: Boolean) {
    val w = size.width; val h = size.height
    val paper = Color(0xFFFFFCF1)
    val ink = style.accent
    fun rect(c: Color, x: Float, y: Float, width: Float, height: Float, r: Float = .04f) =
        drawRoundRect(c, Offset(w*x, h*y), Size(w*width, h*height), CornerRadius(w*r))
    fun line(c: Color, x: Float, y: Float, x2: Float, y2: Float, stroke: Float = 1f) =
        drawLine(c, Offset(w*x, h*y), Offset(w*x2, h*y2), stroke * density, StrokeCap.Round)
    drawOval(style.wood.copy(alpha = .22f), Offset(w*.06f, h*.91f), Size(w*.88f, h*.08f))
    when (kind) {
        "book" -> {
            rect(ink.copy(alpha = .7f), .13f,.12f,.48f,.84f)
            rect(paper,.23f,.14f,.35f,.77f,.015f)
            rect(ink,.12f,.10f,.09f,.86f,.025f)
            line(style.wood,.30f,.29f,.51f,.29f)
            line(style.wood.copy(alpha=.6f),.30f,.38f,.46f,.38f)
            rect(Color(0xFFDCB675),.46f,.13f,.05f,.19f,.005f)
            rect(style.wood,.64f,.30f,.17f,.66f,.025f)
            line(paper,.68f,.40f,.77f,.40f)
        }
        "frame" -> {
            rect(style.wood,.09f,.10f,.82f,.85f,.055f)
            rect(paper,.15f,.17f,.70f,.70f,.02f)
            rect(ink.copy(alpha=.22f),.21f,.24f,.58f,.56f,.01f)
            drawCircle(Color(0xFFE9C87E),w*.07f,Offset(w*.65f,h*.39f))
            val hill = Path().apply {
                moveTo(w*.21f,h*.80f); lineTo(w*.21f,h*.65f)
                lineTo(w*.40f,h*.45f); lineTo(w*.58f,h*.66f)
                lineTo(w*.68f,h*.56f); lineTo(w*.79f,h*.69f); lineTo(w*.79f,h*.80f); close()
            }
            drawPath(hill,ink.copy(alpha=.7f))
        }
        "recorder" -> {
            rect(style.wood,.27f,.86f,.46f,.10f)
            line(style.wood,.50f,.66f,.50f,.88f,2f)
            rect(ink,.34f,.09f,.32f,.57f,.16f)
            for (i in 0..3) line(paper.copy(alpha=.7f),.42f,.21f+i*.09f,.58f,.21f+i*.09f)
            drawArc(ink.copy(alpha=.65f),0f,180f,false,Offset(w*.24f,h*.34f),Size(w*.52f,h*.43f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        "mailbox" -> {
            rect(style.wood.copy(alpha=.55f),.10f,.37f,.80f,.59f)
            rect(if(due) Color(0xFFFFE7B4) else paper,.14f,.29f,.72f,.60f)
            line(ink.copy(alpha=.6f),.16f,.33f,.50f,.59f,1.2f)
            line(ink.copy(alpha=.6f),.50f,.59f,.84f,.33f,1.2f)
            drawCircle(if(due) Color(0xFFCC9953) else ink,w*.065f,Offset(w*.50f,h*.59f))
            rect(ink.copy(alpha=.4f),.20f,.81f,.60f,.09f,.015f)
        }
        else -> {
            rect(style.wood,.20f,.84f,.60f,.12f)
            rect(ink.copy(alpha=.20f),.23f,.21f,.54f,.62f,.20f)
            drawCircle(ink.copy(alpha=.75f),w*.18f,Offset(w*.50f,h*.47f))
            drawCircle(paper,w*.045f,Offset(w*.55f,h*.39f))
            rect(paper,.34f,.75f,.32f,.06f)
        }
    }
}

/** Architecture, daylight and furnishings distinguish the themes even in the small previews. */
private fun DrawScope.drawRoom(style: RoomStyle) {
    val w = size.width; val h = size.height
    val bedroom = style.key == "bedroom"
    val cabin = style.key == "cabin"
    val cream = Color(0xFFFFFAEE)
    fun rect(c: Color, x: Float, y: Float, width: Float, height: Float, r: Float = .012f) =
        drawRoundRect(c, Offset(w*x,h*y), Size(w*width,h*height), CornerRadius(w*r))
    fun line(c: Color, x: Float, y: Float, x2: Float, y2: Float, stroke: Float = 1f) =
        drawLine(c, Offset(w*x,h*y), Offset(w*x2,h*y2), stroke*density, StrokeCap.Round)
    fun oval(c: Color, x: Float, y: Float, width: Float, height: Float) =
        drawOval(c, Offset(w*x,h*y), Size(w*width,h*height))
    fun pine(x: Float, y: Float, scale: Float, c: Color) {
        val tree = Path().apply {
            moveTo(w*x,h*y); lineTo(w*(x-scale*.5f),h*(y+scale*.75f))
            lineTo(w*(x-scale*.28f),h*(y+scale*.75f))
            lineTo(w*(x-scale*.65f),h*(y+scale*1.3f))
            lineTo(w*(x+scale*.65f),h*(y+scale*1.3f))
            lineTo(w*(x+scale*.28f),h*(y+scale*.75f))
            lineTo(w*(x+scale*.5f),h*(y+scale*.75f)); close()
        }
        drawPath(tree,c)
        line(c,x,y+scale,x,y+scale*1.5f,1.2f)
    }
    drawRect(Brush.verticalGradient(listOf(style.wall, if(bedroom) Color(0xFFF7F3FC) else cream)))
    when {
        cabin -> {
            // Timber beams and horizontal boards belong to the cabin only.
            for (i in 1..9) line(style.wood.copy(alpha=.16f),0f,i*.084f,1f,i*.084f)
            line(style.wood.copy(alpha=.4f),.025f,.02f,.025f,.78f,5f)
            line(style.wood.copy(alpha=.4f),.975f,.02f,.975f,.78f,5f)
        }
        bedroom -> {
            for (i in 0..9) rect(style.accent.copy(alpha=.035f),i*.11f,0f,.035f,.78f,0f)
            rect(Color.White.copy(alpha=.28f),.025f,.328f,.95f,.447f,.035f)
        }
        else -> {
            for (i in 1..6) line(style.wood.copy(alpha=.12f),i/7f,0f,i/7f,.79f)
            rect(style.wood.copy(alpha=.08f),.035f,.325f,.93f,.45f,.006f)
            rect(style.wood.copy(alpha=.55f),.036f,.325f,.014f,.45f,.003f)
            rect(style.wood.copy(alpha=.55f),.95f,.325f,.014f,.45f,.003f)
        }
    }
    drawCircle(Brush.radialGradient(listOf(style.light.copy(alpha=.65f),Color.Transparent),
        center=Offset(w*.82f,h*.12f),radius=w*.48f),w*.48f,Offset(w*.82f,h*.12f))
    val floor = when { bedroom -> Color(0xFFDCD5E7); cabin -> Color(0xFFCCBE9B); else -> Color(0xFFD9BF9A) }
    rect(style.wood.copy(alpha=.35f),0f,.779f,1f,.012f,0f)
    drawRect(Brush.verticalGradient(listOf(floor, floor.copy(alpha=.75f))),Offset(0f,h*.79f),Size(w,h*.21f))
    for(i in 0..7) line(style.wood.copy(alpha=.22f),.5f+(i-3.5f)*.075f,.79f,.5f+(i-3.5f)*.22f,1f)
    // All windows stay within the reserved upper-right area, clear of the heading and slots.
    when {
        bedroom -> {
            // Circular moon window, stars and long lavender curtains.
            oval(style.wood.copy(alpha=.18f),.683f,.079f,.248f,.249f)
            oval(cream,.668f,.065f,.248f,.249f)
            oval(Color(0xFF59617F),.685f,.082f,.214f,.215f)
            drawCircle(Color(0xFFFFECC1),w*.035f,Offset(w*.813f,h*.145f))
            drawCircle(Color(0xFF59617F),w*.032f,Offset(w*.828f,h*.135f))
            for((x,y) in listOf(.735f to .139f,.857f to .204f,.755f to .244f)) {
                line(cream,x-.006f,y,x+.006f,y,.8f)
                line(cream,x,y-.006f,x,y+.006f,.8f)
            }
            line(style.wood,.64f,.063f,.943f,.063f,2f)
            rect(Color(0xFFC8B9DE),.638f,.066f,.043f,.255f,.012f)
            rect(Color(0xFFC8B9DE),.906f,.066f,.04f,.255f,.012f)
            line(cream.copy(alpha=.7f),.646f,.24f,.674f,.24f)
            line(cream.copy(alpha=.7f),.91f,.24f,.94f,.24f)
        }
        cabin -> {
            // Square timber window with a forest outside; no curtains.
            rect(Color(0xFF8B9C73),.653f,.068f,.287f,.253f,.009f)
            rect(Color(0xFFDCEBCB),.669f,.083f,.255f,.217f,.004f)
            drawCircle(Color(0xFFF9E6A4),w*.024f,Offset(w*.86f,h*.13f))
            pine(.716f,.158f,.069f,Color(0xFFA6BE8B))
            pine(.854f,.174f,.074f,Color(0xFF77966B))
            pine(.782f,.136f,.095f,Color(0xFF607F5D))
            line(Color(0xFF8B9C73),.797f,.085f,.797f,.30f,2.5f)
            rect(Color(0xFFA3AC7B),.64f,.305f,.313f,.014f,.004f)
        }
        else -> {
            // Tall arched reading window and honey-coloured afternoon light.
            rect(style.wood.copy(alpha=.17f),.68f,.079f,.244f,.253f,.08f)
            rect(cream,.665f,.065f,.244f,.253f,.08f)
            rect(Color(0xFFECCF98),.683f,.082f,.208f,.218f,.07f)
            drawCircle(Color(0xFFFFF1C3),w*.029f,Offset(w*.836f,h*.139f))
            oval(Color(0xFFD1AF7C),.687f,.245f,.20f,.052f)
            line(cream,.787f,.09f,.787f,.30f,2f)
            line(cream,.687f,.211f,.888f,.211f,2f)
            rect(style.wood,.65f,.314f,.28f,.012f,.003f)
        }
    }
    // Shelf surfaces share stable slot coordinates but use different construction details.
    if(cabin) {
        for(x in listOf(.074f,.927f)) line(Color(0xFF9D936F).copy(alpha=.65f),x,.335f,x,.775f,1.2f)
    }
    for(y in listOf(.397f,.574f,.751f)) {
        rect(style.wood.copy(alpha=.13f),.052f,y+.012f,.90f,.018f,.007f)
        rect(if(bedroom) Color(0xFFC6B5D5) else style.wood,.045f,y,.91f,if(cabin) .017f else .012f,if(bedroom) .009f else .004f)
        rect(Color.White.copy(alpha=.5f),.045f,y,.91f,.003f,.002f)
        if(!bedroom) {
            rect(style.wood.copy(alpha=.8f),.10f,y+.01f,.018f,.025f,.003f)
            rect(style.wood.copy(alpha=.8f),.887f,y+.01f,.018f,.025f,.003f)
        }
    }
    when {
        bedroom -> {
            // A broad quilted bed, a soft rectangular rug and a tiny bedside light.
            rect(Color(0xFFC5B8D4).copy(alpha=.45f),.11f,.881f,.65f,.078f,.035f)
            rect(Color(0xFFA492BB),.15f,.805f,.14f,.128f,.025f)
            rect(Color(0xFFD8CCE6),.17f,.834f,.48f,.101f,.026f)
            rect(cream,.20f,.842f,.115f,.047f,.014f)
            rect(Color(0xFFB7A4CE),.34f,.834f,.30f,.101f,.018f)
            for(x in listOf(.39f,.46f,.53f,.60f)) line(Color.White.copy(alpha=.23f),x,.846f,x,.923f)
            rect(style.wood,.705f,.861f,.104f,.014f,.006f)
            rect(style.wood,.72f,.875f,.015f,.055f,.002f)
            rect(style.wood,.78f,.875f,.015f,.055f,.002f)
            oval(Color(0xFFF2D8A8),.733f,.809f,.049f,.046f)
            line(style.wood,.758f,.85f,.758f,.862f)
        }
        cabin -> {
            // Woven round rug, reading chair, stump table and a terracotta fern.
            oval(Color(0xFFB5AF82).copy(alpha=.28f),.10f,.857f,.64f,.106f)
            oval(Color(0xFFE4DDB9),.12f,.865f,.60f,.084f)
            oval(Color(0xFFCCC399),.17f,.877f,.50f,.059f)
            rect(Color(0xFF6F9269),.17f,.813f,.23f,.092f,.028f)
            rect(Color(0xFF9AB28B),.18f,.800f,.21f,.071f,.035f)
            rect(Color(0xFFB8CAA5),.19f,.856f,.19f,.032f,.012f)
            rect(cream,.205f,.823f,.075f,.038f,.012f)
            line(style.wood,.19f,.898f,.18f,.932f,2f)
            line(style.wood,.38f,.898f,.39f,.932f,2f)
            rect(Color(0xFFA39165),.52f,.866f,.11f,.068f,.012f)
            oval(Color(0xFFC2B084),.50f,.853f,.15f,.031f)
            rect(Color(0xFFBE9171),.79f,.859f,.095f,.049f,.014f)
            for(i in 0..4) {
                val x=.80f+i*.019f
                line(style.accent,.838f,.863f,x,.805f+(i%2)*.017f,1.2f)
                oval(if(i%2==0) style.accent else Color(0xFF89A675),x-.023f,.800f+(i%2)*.017f,.047f,.025f)
            }
        }
        else -> {
            // Reading desk, books, chair and an amber desk lamp.
            oval(Color(0xFFF3E4C7),.10f,.871f,.65f,.092f)
            oval(Color(0xFFD8C29D),.16f,.886f,.53f,.061f)
            rect(Color(0xFFAD835A),.14f,.858f,.47f,.022f,.006f)
            rect(style.wood,.17f,.88f,.022f,.071f,.003f)
            rect(style.wood,.56f,.88f,.022f,.071f,.003f)
            rect(Color(0xFFB99064),.37f,.891f,.11f,.049f,.008f)
            rect(Color(0xFFD4B38B),.37f,.874f,.11f,.035f,.009f)
            rect(style.accent,.22f,.842f,.12f,.016f,.002f)
            rect(cream,.23f,.836f,.11f,.006f,.001f)
            rect(Color(0xFFC8A46B),.48f,.848f,.054f,.01f)
            line(Color(0xFFA78752),.508f,.848f,.508f,.807f,1.6f)
            val shade=Path().apply {
                moveTo(w*.47f,h*.812f); lineTo(w*.546f,h*.812f)
                lineTo(w*.53f,h*.783f); lineTo(w*.486f,h*.783f); close()
            }
            drawPath(shade,Color(0xFFE3BD78))
            rect(Color(0xFFD8C099),.79f,.853f,.08f,.05f)
            line(style.accent,.83f,.855f,.83f,.798f,1.4f)
            oval(Color(0xFF91A27C),.78f,.806f,.055f,.027f)
            oval(Color(0xFF7C956B),.83f,.792f,.049f,.026f)
        }
    }
}

/** Larger theme silhouettes make the choice legible at thumbnail size. */
private fun DrawScope.drawRoomPreview(style: RoomStyle) {
    val w=size.width; val h=size.height
    fun rect(c:Color,x:Float,y:Float,sw:Float,sh:Float,r:Float=.035f) =
        drawRoundRect(c,Offset(w*x,h*y),Size(w*sw,h*sh),CornerRadius(w*r))
    drawRect(style.wall)
    rect(style.wood.copy(alpha=.20f),0f,.79f,1f,.21f,0f)
    when(style.key) {
        "bedroom" -> {
            drawCircle(Color(0xFF626482),w*.17f,Offset(w*.71f,h*.35f))
            drawCircle(Color(0xFFFFECC1),w*.065f,Offset(w*.72f,h*.30f))
            drawCircle(Color(0xFF626482),w*.06f,Offset(w*.75f,h*.27f))
            rect(style.wood,.12f,.43f,.10f,.41f)
            rect(Color(0xFFD1C2E1),.18f,.60f,.62f,.24f)
            rect(Color(0xFFFFFCF3),.21f,.61f,.17f,.13f)
            rect(style.accent.copy(alpha=.60f),.43f,.60f,.37f,.24f)
        }
        "cabin" -> {
            rect(style.wood,.10f,0f,.035f,.85f,.005f)
            rect(style.wood,.89f,0f,.035f,.85f,.005f)
            rect(style.wood,.47f,.13f,.34f,.46f,.008f)
            rect(Color(0xFFF0F2D5),.50f,.17f,.28f,.37f,.003f)
            val tree=Path().apply {
                moveTo(w*.64f,h*.21f); lineTo(w*.52f,h*.50f)
                lineTo(w*.76f,h*.50f); close()
            }
            drawPath(tree,style.accent)
            rect(Color(0xFF9AB28B),.18f,.53f,.25f,.30f,.08f)
            rect(Color(0xFFC6D4AF),.18f,.70f,.25f,.13f)
            rect(Color(0xFFA39165),.56f,.75f,.14f,.12f)
        }
        else -> {
            rect(Color(0xFFFFFAE7),.60f,.10f,.24f,.44f,.10f)
            rect(Color(0xFFEAC88C),.63f,.14f,.18f,.36f,.08f)
            rect(style.wood,.12f,.68f,.70f,.07f,.01f)
            rect(style.wood,.17f,.75f,.04f,.17f,.005f)
            rect(style.wood,.73f,.75f,.04f,.17f,.005f)
            rect(style.accent,.23f,.59f,.18f,.08f,.005f)
            rect(Color(0xFFFFFCF1),.25f,.56f,.16f,.025f,.003f)
            rect(style.accent,.58f,.44f,.022f,.24f,.004f)
            val shade=Path().apply {
                moveTo(w*.49f,h*.47f); lineTo(w*.69f,h*.47f)
                lineTo(w*.64f,h*.27f); lineTo(w*.54f,h*.27f); close()
            }
            drawPath(shade,Color(0xFFD4AA63))
        }
    }
}
