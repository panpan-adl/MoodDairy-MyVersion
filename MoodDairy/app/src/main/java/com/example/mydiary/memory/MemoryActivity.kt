package com.example.mydiary.memory

import android.Manifest
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.app.DatePickerDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.network.ApiEndpoints
import com.example.mydiary.ui.theme.MyDiaryTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MemoryActivity : ComponentActivity() {
    @Inject lateinit var session: SessionManager
    private lateinit var store: MemoryStore
    private var page by mutableStateOf("inbox")
    private var letters by mutableStateOf<List<JSONObject>>(emptyList())
    private var materials by mutableStateOf<List<JSONObject>>(emptyList())
    private var room by mutableStateOf(JSONObject())
    private var current by mutableStateOf<JSONObject?>(null)
    private var draft by mutableStateOf(JSONObject())
    private var selected by mutableStateOf<JSONObject?>(null)
    private var placement by mutableStateOf<JSONObject?>(null)
    private var proposals by mutableStateOf<List<JSONObject>>(emptyList())
    private var message by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var playing by mutableStateOf<String?>(null)
    private var imageBytes by mutableStateOf<ByteArray?>(null)
    private var sealConfirm by mutableStateOf(false)
    private var deleteConfirm by mutableStateOf(false)
    private var discardDraft by mutableStateOf<JSONObject?>(null)
    private var recording by mutableStateOf(false)
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingLimit: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!session.isLoggedIn()) { finish(); return }
        store=MemoryStore(this,session)
        page=intent.getStringExtra("page") ?: "inbox"
        draft=store.draft() ?: fresh()
        intent.getLongExtra("diary_id",-1).takeIf { it>0 }?.let {
            draft=fresh().put("diary_ids",JSONArray().put(it)); store.save(draft)
            if (page!="room") page="edit" else placement=JSONObject().put("source_type","diary").put("source_id",it.toString()).put("kind","book").put("slot",0).put("title","").put("description","")
        }
        setContent {
            com.example.mydiary.live2d.CompanionHost(active = session.isLoggedIn(), assistant = false)
 MyDiaryTheme {
            MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=Color(0xFF356F5B),onPrimary=Color.White,secondary=Color(0xFF58744C))) { Screen() }
        } }
        task { refresh() }
    }
    private fun fresh(parent: String? = null)=JSONObject().put("request_id",UUID.randomUUID().toString()).put("title",if(parent==null) "给未来的自己" else "现在的回应")
        .put("text","").put("open_date",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString()).put("timezone","Asia/Shanghai")
        .put("music",true).put("segments",JSONArray()).put("diary_ids",JSONArray()).put("media_ids",JSONArray()).put("files",JSONArray()).apply { if(parent!=null) put("parent_id",parent) }
    private fun change(key: String,value: Any) { draft=JSONObject(draft.toString()).put(key,value); store.save(draft) }
    private fun task(block:suspend ()->Unit) {
        if(busy) return
        busy=true; message=""
        lifecycleScope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:MemorySessionExpired) {
                playing=null; imageBytes=null
                if(session.getUserId()==store.user) {
                    session.clearSession()
                    android.widget.Toast.makeText(this@MemoryActivity,e.message,android.widget.Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@MemoryActivity,com.example.mydiary.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } else finish()
            }
            catch(e:java.net.SocketTimeoutException) { message="服务器响应超时，请稍后重试。手机草稿已保留。" }
            catch(e:java.io.IOException) { message="连接已中断，请确认电脑后台正在运行、数据线或网络连接正常后重试。手机草稿已保留。" }
            catch(e:Exception) { message=e.message ?: "连接失败，草稿已保留" }
            finally { busy=false }
        }
    }
    private suspend fun refresh() {
        letters=(store.api("future-letters") as JSONArray).objects()
        room=store.api("emotion-space") as JSONObject
        materials=(store.api("memory-materials") as JSONArray).objects()
    }
    private suspend fun detail(id:String) { current=store.api("future-letters/$id") as JSONObject; page="detail" }
    private suspend fun saveRemote():JSONObject {
        store.save(draft)
        var id=draft.optString("server_id")
        var version=draft.optInt("version",1)
        val payload=JSONObject(draft.toString())
        if(id.isEmpty()) {
            val path=if(draft.has("parent_id")) "future-letters/${draft.getString("parent_id")}/respond" else "future-letters"
            val created=store.api(path,"POST",payload) as JSONObject
            id=created.getString("id"); version=created.getInt("version")
            change("server_id",id); change("version",version)
        } else {
            val remote=store.api("future-letters/$id") as JSONObject
            if(remote.getInt("version")!=version) {
                val uploaded=(remote.optJSONArray("assets")?:JSONArray()).objects().map{it.getString("id")}.toSet()
                val files=(draft.optJSONArray("files")?:JSONArray()).objects()
                val matched=files.filter{it.getString("name") in uploaded}
                if(matched.isNotEmpty() && remote.getInt("version")-version==matched.size && remote.optString("state")=="draft") {
                    change("files",JSONArray(files.filter{it.getString("name") !in uploaded}))
                    for(file in matched) store.deleteMedia(file.getString("name"))
                    version=remote.getInt("version"); change("version",version)
                }
            }
            payload.put("version",version)
            val updated=store.api("future-letters/$id","PUT",payload) as JSONObject
            version=updated.getInt("version"); change("version",version)
        }
        val pending=draft.optJSONArray("files") ?: JSONArray()
        while(pending.length()>0) {
            val file=pending.getJSONObject(0)
            val uploaded=store.upload(id,version,file.getString("name"),file.getString("kind"))
            version=uploaded.getInt("version"); pending.remove(0)
            change("files",pending); change("version",version); store.deleteMedia(file.getString("name"))
        }
        return store.api("future-letters/$id") as JSONObject
    }
    private fun addFile(name:String,kind:String) {
        val files=JSONArray(draft.optJSONArray("files")?.toString() ?: "[]"); files.put(JSONObject().put("name",name).put("kind",kind)); change("files",files)
    }
    private fun stopRecording() {
        if(!recording) return
        recording=false; recordingLimit?.cancel()
        val file=recordingFile
        runCatching { recorder?.stop() }; recorder?.release(); recorder=null
        if(file!=null) {
            runCatching { addFile(store.writeMedia(file.readBytes()),"audio") }.onFailure { message="录音未保存，请重新录制" }
            file.delete()
        }
    }
    private fun startRecording() {
        try {
            val file=File(cacheDir,"memory-record-${UUID.randomUUID()}.m4a"); recordingFile=file
            @Suppress("DEPRECATION") val r=MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setOutputFile(file.absolutePath); r.setMaxDuration(180000); r.prepare(); r.start()
            recorder=r; recording=true
            recordingLimit=lifecycleScope.launch { delay(178000); stopRecording() }
        } catch(e:Exception) { message="无法开始录音，请检查麦克风权限" }
    }
    override fun onStop() { stopRecording(); playing=null; imageBytes=null; super.onStop() }
    override fun onResume() { super.onResume(); if(::store.isInitialized && session.getUserId()!=store.user) finish() }

    @Composable private fun Screen() {
        LaunchedEffect(message) { if(message.isNotBlank()) android.widget.Toast.makeText(this@MemoryActivity,message,android.widget.Toast.LENGTH_LONG).show() }
        val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            task { for(uri in uris) addFile(store.importMedia(uri),"image"); message="素材已加密保存在手机，等待上传" }
        }
        val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if(uri!=null) task { addFile(store.importMedia(uri),"audio") }
        }
        val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) startRecording() else message="未获得录音权限" }
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> message=if(granted) "到期后将尽量提醒你，打开应用也会检查" else "提醒权限未开启，仍可在信箱查看到期状态" }
        val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            val id=current?.optString("id")
            if(uri!=null && id!=null) task {
                val bytes=store.bytes("future-letters/$id/play?download=true")
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }; message="视频已导出到你选择的位置"
            }
        }
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFCBF6E4),Color(0xFFF7F8E7))))
            .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TextButton(onClick={ stopRecording(); playing=null; if(page in listOf("edit","detail")) { page="inbox"; task { refresh() } } else finish() }) { Text("返回",color=Color(0xFF2C3E50)) }
                Text(if(page=="room") "我的情绪空间" else "时光信箱",style=MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick={stopRecording(); page="inbox"; task { refresh() }},enabled=!busy) { Text("时光信箱") }
                OutlinedButton(onClick={stopRecording(); page="room"; task { refresh() }},enabled=!busy) { Text("情绪空间") }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(message.isNotEmpty()) Card { Text(message,Modifier.padding(16.dp)) }
            when(page) {
                "inbox" -> {
                    Text("把今天的话留给未来，也给以后的自己留一个回应的位置。")
                    Button(onClick={ draft=fresh(); store.save(draft); page="edit" },enabled=!busy) { Text("写一封视频信") }
                    for(local in store.drafts()) Row {
                        OutlinedButton(onClick={draft=local; page="edit"},modifier=Modifier.weight(1f)) { Text("继续草稿：${local.optString("title")}") }
                        TextButton(onClick={discardDraft=local}) {Text("移除")}
                    }
                    for(l in letters) Card(onClick={task { detail(l.getString("id")) }}) { Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(l.getString("title"),style=MaterialTheme.typography.titleMedium)
                        Text("${status(l)} · 开启 ${date(l)}")
                        if(l.has("parent_id") && !l.isNull("parent_id")) Text("当时与现在的双时间线")
                    } }
                    OutlinedButton(onClick={task { refresh() }},enabled=!busy) { Text("刷新信箱") }
                }
                "edit" -> {
                    Text("素材会保存独立副本；原日记修改或删除不会改变这封信。",style=MaterialTheme.typography.bodySmall)
                    if(draft.has("parent_id")) Text("双时间线依次播放当时与现在。每段超过90秒时，会在合成版中适度加速以保持总长不超过3分钟；原信不变。",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.optString("title"),{change("title",it)},label={Text("信件标题")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
                    OutlinedTextField(draft.optString("text"),{change("text",it)},label={Text("想对未来的自己说什么？")},modifier=Modifier.fillMaxWidth().testTag("letter-body"),minLines=5,enabled=!busy)
                    OutlinedButton(onClick={
                        val d=runCatching{LocalDate.parse(draft.optString("open_date"))}.getOrDefault(LocalDate.now())
                        DatePickerDialog(this@MemoryActivity,{_,y,m,day->change("open_date",LocalDate.of(y,m+1,day).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()
                    },enabled=!busy) { Text("开启日期：${draft.optString("open_date")}（北京时间）") }
                    Row { Checkbox(draft.optBoolean("music",true),{change("music",it)},enabled=!busy); Text("轻柔背景音乐") }
                    Text("选择日记与已有素材",style=MaterialTheme.typography.titleMedium)
                    if(draft.optString("server_id").isEmpty()) MaterialChoices(draft) { key,value->change(key,value) }
                    else Text("来源已制作快照。要改选日记，请新建一封信；正文和字幕仍可修改。")
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick={photoPicker.launch(arrayOf("image/*"))},enabled=!busy) { Text("选照片") }
                        OutlinedButton(onClick={audioPicker.launch(arrayOf("audio/*"))},enabled=!busy) { Text("选录音") }
                    }
                    OutlinedButton(onClick={if(recording) stopRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO)},enabled=!busy) { Text(if(recording) "停止并保存原声" else "录一段原声（≤3分钟）") }
                    for((index,file) in (draft.optJSONArray("files")?:JSONArray()).objects().withIndex()) Row {
                        Text("待上传：${if(file.getString("kind")=="image") "照片" else "录音"}",Modifier.weight(1f))
                        TextButton(onClick={val files=JSONArray(draft.getJSONArray("files").toString()); files.remove(index); change("files",files); store.deleteMedia(file.getString("name"))}) {Text("移除")}
                    }
                    val segments=draft.optJSONArray("segments")?:JSONArray()
                    if(segments.length()>0) {
                        Text("字幕顺序（可修改）")
                        OutlinedTextField((0 until segments.length()).joinToString("\n\n"){segments.getString(it)}, {value-> change("segments",JSONArray(value.split("\n\n").filter{it.isNotBlank()}))},modifier=Modifier.fillMaxWidth(),minLines=4,enabled=!busy)
                    }
                    Button(onClick={task { current=saveRemote(); message="已保存到账号" }},enabled=!busy&&!recording) {Text("保存到账号")}
                    OutlinedButton(onClick={task {
                        val l=saveRemote(); val outline=store.api("future-letters/${l.getString("id")}/outline","POST") as JSONObject
                        change("segments",outline.getJSONArray("segments")); message="整理结果已放入字幕草稿，请检查并修改；所选日记快照会随信保存。"
                    }},enabled=!busy&&!recording) {Text("AI 帮我整理字幕")}
                    Button(onClick={task {
                        val l=saveRemote(); store.api("future-letters/${l.getString("id")}/render","POST",JSONObject().put("version",l.getInt("version"))); detail(l.getString("id"))
                    }},enabled=!busy&&!recording) {Text("生成视频并预览")}
                    Text("最多12张照片、1段录音，视频最长3分钟。断网时编辑会保留在手机，恢复连接后再保存。",style=MaterialTheme.typography.bodySmall)
                }
                "detail" -> current?.let { l ->
                    val id=l.getString("id"); val state=l.getString("state")
                    Text(l.getString("title"),style=MaterialTheme.typography.headlineSmall)
                    Text("${status(l)} · 开启 ${date(l)}")
                    if(state=="sealed") {
                        Text("信件已封存，正文、素材和视频暂不可见。")
                        if(l.optBoolean("due")) Button(onClick={task { store.api("future-letters/$id/open","POST"); detail(id) }},enabled=!busy) {Text("打开未来来信")}
                    } else {
                        l.optJSONObject("content")?.let { c ->
                            Text(c.optString("text"))
                            for(d in (c.optJSONArray("diaries")?:JSONArray()).objects()) Text("来源：${d.optString("date")} · ${d.optString("title")}",style=MaterialTheme.typography.bodySmall)
                        }
                        if(state in listOf("ready","opened")) {
                            Button(onClick={playing="future-letters/$id/play"},enabled=!busy) {Text("观看视频")}
                            OutlinedButton(onClick={export.launch("未来视频信.mp4")},enabled=!busy) {Text("导出视频")}
                        }
                        if(state=="ready") OutlinedButton(onClick={task {
                            playing=null
                            store.api("future-letters/$id/render","POST",JSONObject().put("version",l.getInt("version")).put("regenerate",true))
                            detail(id)
                        }},enabled=!busy) {Text("重新生成信纸滚动版")}
                        if(state in listOf("draft","ready","failed")) {
                            OutlinedButton(onClick={
                                val c=l.getJSONObject("content"); draft=JSONObject(c.toString()).put("request_id",UUID.randomUUID().toString()).put("server_id",id).put("version",l.getInt("version"))
                                    .put("open_date",java.time.Instant.parse(l.getString("opens_at")).atZone(ZoneId.of(l.getString("timezone"))).toLocalDate().toString()).put("timezone",l.getString("timezone"))
                                    .put("files",JSONArray()).put("diary_ids",JSONArray()).put("media_ids",JSONArray())
                                store.save(draft); page="edit"
                            },enabled=!busy) {Text("编辑正文、字幕与素材")}
                            if(state=="ready") Button(onClick={sealConfirm=true},enabled=!busy) {Text("确认封存")}
                        }
                        for(a in (l.optJSONArray("assets")?:JSONArray()).objects()) Row {
                            Text("${if(a.getString("kind")=="image") "照片" else "原声"}素材",Modifier.weight(1f))
                            if(state in listOf("draft","ready","failed")) TextButton(onClick={task {
                                store.api("future-letters/$id/assets/${a.getString("id")}?version=${l.getInt("version")}","DELETE"); detail(id)
                            }},enabled=!busy) {Text("移除")}
                        }
                        if(state=="opened" && l.isNull("parent_id")) Button(onClick={draft=fresh(id); store.save(draft); page="edit"},enabled=!busy) {Text("留下现在的回应")}
                    }
                    if(state=="rendering") {
                        Text("正在合成视频，可以离开后再回来查看。")
                        LaunchedEffect(id,state) { while(isActive) { delay(4000); if(!busy) task { detail(id) } } }
                    }
                    l.optJSONObject("job")?.optString("error")?.takeUnless{it.isBlank()||it=="null"}?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    if(state!="rendering") {
                        OutlinedButton(onClick={placement=JSONObject().put("source_type","letter").put("source_id",id).put("kind","mailbox").put("slot",firstFree()).put("title","一封视频信").put("description","")},enabled=!busy) {Text("放进情绪空间的信箱")}
                        TextButton(onClick={deleteConfirm=true},enabled=!busy) {Text("删除这封信")}
                    }
                    OutlinedButton(onClick={task { detail(id) }},enabled=!busy) {Text("刷新状态")}
                }
                "room" -> RoomContent()
            }
            playing?.let { path -> PrivatePlayer(path) }
            imageBytes?.let { bytes ->
                val bitmap=remember(bytes) {android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)}
                AlertDialog(onDismissRequest={imageBytes=null},title={Text("原照片")},text={if(bitmap!=null) androidx.compose.foundation.Image(bitmap.asImageBitmap(),contentDescription="原照片",modifier=Modifier.fillMaxWidth().heightIn(max=440.dp))},confirmButton={TextButton(onClick={imageBytes=null}) {Text("关闭照片")}})
            }
        }
        if(sealConfirm) AlertDialog(onDismissRequest={sealConfirm=false},title={Text("确认封存这封信？")},text={Text("封存后到期才能查看，不能修改日期。原日记和照片仍保留；你封存前自行录屏或导出的副本无法收回。")},confirmButton={TextButton(onClick={sealConfirm=false; task {
            val l=current!!; val sealed=store.api("future-letters/${l.getString("id")}/seal","POST",JSONObject().put("version",l.getInt("version"))) as JSONObject
            playing=null; store.clear(l.getString("id")); draft=fresh(); current=sealed
            MemoryReminder.schedule(this@MemoryActivity)
            if(android.os.Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }}) {Text("封存")}},dismissButton={TextButton(onClick={sealConfirm=false}){Text("再看看")}})
        if(deleteConfirm) AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("删除视频信及专属副本？")},text={Text("原日记和原素材不受影响，空间中的关联物品将显示来源已删除。")},confirmButton={TextButton(onClick={deleteConfirm=false; task { val id=current!!.getString("id"); store.api("future-letters/$id","DELETE"); store.clear(id); playing=null; page="inbox"; refresh() }}){Text("删除")}},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("取消")}})
        discardDraft?.let {local -> AlertDialog(onDismissRequest={discardDraft=null},title={Text("移除手机上的这份草稿？")},text={Text("未上传的文字和素材将从手机删除；已保存到账号的信件不受影响。")},confirmButton={TextButton(onClick={store.discard(local); discardDraft=null; draft=store.draft()?:fresh()}){Text("移除")}},dismissButton={TextButton(onClick={discardDraft=null}){Text("取消")}}) }
        selected?.let { item -> ItemDialog(item) }
        placement?.let { item -> PlacementDialog(item) }
    }
    private fun status(l:JSONObject)=when(l.optString("state")){"sealed"->if(l.optBoolean("due")) "已到期，等待你开启" else "已封存";"opened"->"已开启";"rendering"->"生成中";"ready"->"可预览";"failed"->"生成未完成";else->"草稿"}
    private fun date(l:JSONObject)=runCatching{java.time.Instant.parse(l.getString("opens_at")).atZone(ZoneId.of(l.getString("timezone"))).toLocalDate().toString()}.getOrDefault("")
    private fun firstFree():Int {val used=(room.optJSONArray("items")?:JSONArray()).objects().map{it.getInt("slot")}; return (0..11).firstOrNull{it !in used}?:0}

    @Composable private fun MaterialChoices(value:JSONObject,onChange:(String,JSONArray)->Unit) {
        for(d in materials) {
            val ids=value.optJSONArray("diary_ids")?:JSONArray(); val selected=(0 until ids.length()).map{ids.getLong(it)}
            Row { Checkbox(d.getLong("id") in selected,{check->
                val changed=selected.toMutableList(); if(check&&changed.size<5) changed.add(d.getLong("id")) else changed.remove(d.getLong("id")); onChange("diary_ids",JSONArray(changed))
                if(!check) {
                    val removed=d.getJSONArray("media").objects().map{it.getLong("id")}.toSet()
                    val mids=value.optJSONArray("media_ids")?:JSONArray()
                    onChange("media_ids",JSONArray((0 until mids.length()).map{mids.getLong(it)}.filter{it !in removed}))
                }
            },enabled=!busy); Text("${d.getString("date")} · ${d.getString("title")}",Modifier.padding(top=10.dp)) }
            if(d.getLong("id") in selected) for(m in d.getJSONArray("media").objects()) {
                val mids=value.optJSONArray("media_ids")?:JSONArray(); val picked=(0 until mids.length()).map{mids.getLong(it)}
                Row(Modifier.padding(start=24.dp)) {
                    Checkbox(m.getLong("id") in picked,{check->val changed=picked.toMutableList(); if(check) changed.add(m.getLong("id")) else changed.remove(m.getLong("id")); onChange("media_ids",JSONArray(changed))},enabled=!busy)
                    Text(if(m.getString("kind")=="image") "附上照片 #${m.getLong("id")}" else "附上原声 #${m.getLong("id")}",Modifier.weight(1f).padding(top=10.dp))
                    TextButton(onClick={task {val path="memory-materials/${m.getLong("id")}/preview"; if(m.getString("kind")=="image") imageBytes=store.bytes(path) else playing=path}},enabled=!busy) {Text("预览")}
                }
            }
        }
    }

    @Composable private fun RoomContent() {
        Text("你的经历可以有一个安放的位置。空间风格由你决定。")
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            for((key,label) in listOf("study" to "温暖书房","bedroom" to "安静卧室","cabin" to "自然小屋")) {
                FilterChip(selected=room.optString("theme")==key,onClick={task {store.api("emotion-space","PUT",JSONObject().put("version",room.getInt("version")).put("theme",key)); refresh()}},label={Text(label)},enabled=!busy)
            }
        }
        val items=(room.optJSONArray("items")?:JSONArray()).objects()
        MemoryRoom(room.optString("theme","study"),items) { selected=it }
        Button(onClick={placement=JSONObject().put("source_type","diary").put("source_id","").put("kind","book").put("slot",firstFree()).put("title","").put("description","")},enabled=!busy) {Text("放入一段经历")}
        var choices by remember {mutableStateOf(JSONObject().put("diary_ids",JSONArray()))}
        Text("选一至五篇日记，让 AI 提出摆放草稿",style=MaterialTheme.typography.titleMedium)
        MaterialChoices(choices) {key,v->choices=JSONObject(choices.toString()).put(key,v)}
        OutlinedButton(onClick={task {
            val answer=store.api("emotion-space/suggest","POST",choices) as JSONObject
            proposals=answer.getJSONArray("items").objects()
        }},enabled=!busy&&(choices.optJSONArray("diary_ids")?.length()?:0)>0) {Text("推荐摆放（不会自动保存）")}
        for(p in proposals) Card {Column(Modifier.padding(14.dp)) {
            Text("${p.optString("title")} · 位置 ${p.optInt("slot")+1}"); Text(p.optString("description"))
            TextButton(onClick={placement=JSONObject(p.toString()).put("source_type","diary").put("source_id",p.getLong("diary_id").toString())}) {Text("编辑并确认放入")}
        }}
        Text("物品列表",style=MaterialTheme.typography.titleMedium)
        for(item in items) OutlinedButton(onClick={selected=item},modifier=Modifier.fillMaxWidth()) {Text("位置 ${item.getInt("slot")+1} · ${item.optString("title").ifBlank{item.getString("kind")}}")}
        OutlinedButton(onClick={task {refresh()}},enabled=!busy) {Text("刷新空间")}
    }

    @Composable private fun ItemDialog(item:JSONObject) {
        AlertDialog(onDismissRequest={selected=null},title={Text(item.optString("title","一段经历"))},text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState())) {
            if(item.optBoolean("missing")) Text("来源已删除，可移除这个物品。")
            else {
                Text(item.optString("description"))
                item.optJSONObject("source")?.let {source->Text(source.optString("title")); Text(source.optString("text"))}
                item.optJSONObject("letter")?.let {Text("${status(it)} · ${date(it)}")}
            }
        }},confirmButton={Column {
            if(!item.optBoolean("missing")) TextButton(onClick={selected=null; task {
                when(item.getString("source_type")) {
                    "letter"->detail(item.getString("source_id"))
                    "media"->{
                        val path="emotion-space/items/${item.getString("id")}/media"
                        if(item.optString("media_kind")=="image") imageBytes=store.bytes(path) else playing=path
                        page="room"
                    }
                    "diary"->{
                        val src=item.getJSONObject("source")
                        startActivity(Intent(this@MemoryActivity,com.example.mydiary.MainActivity::class.java).putExtra("memory_diary_id",src.getLong("id")).putExtra("memory_diary_date",src.getString("date")))
                    }
                }
            }}) {Text("打开来源")}
            TextButton(onClick={selected=null; placement=JSONObject(item.toString()).put("replace_id",item.getString("id"))}){Text("修改位置或替换")}
            TextButton(onClick={selected=null; task{store.api("emotion-space/items/${item.getString("id")}?version=${room.getInt("version")}","DELETE"); refresh()}}){Text("从空间移除")}
        }},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})
    }

    @Composable private fun PlacementDialog(initial:JSONObject) {
        var edit by remember(initial) {mutableStateOf(JSONObject(initial.toString()))}
        fun changeItem(key:String,value:Any) { edit=JSONObject(edit.toString()).put(key,value) }
        AlertDialog(onDismissRequest={placement=null},title={Text("布置一件物品")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.error)
            OutlinedTextField(edit.optString("title"),{changeItem("title",it)},label={Text("你给它的名字")})
            OutlinedTextField(edit.optString("description"),{changeItem("description",it)},label={Text("想留下的话")})
            Text("位置：${edit.optInt("slot")+1}（占用位置需要明确替换）")
            for(row in 0..2) Row {for(col in 0..3) {val slot=row*4+col; TextButton(onClick={changeItem("slot",slot)}) {Text(if(slot==edit.optInt("slot")) "[${slot+1}]" else "${slot+1}")}}}
            if(edit.optString("source_type")=="letter") {
                Text("关联视频信")
                for(l in letters) TextButton(onClick={changeItem("source_id",l.getString("id")); changeItem("kind","mailbox")}) {Text(l.getString("title"))}
            } else {
                Text("选择来源（不会修改原记录）")
                for(d in materials) {
                    TextButton(onClick={changeItem("source_type","diary"); changeItem("source_id",d.getLong("id").toString()); changeItem("kind","book")}) {Text("${d.getString("date")} · ${d.getString("title")}")}
                    for(m in d.getJSONArray("media").objects()) TextButton(onClick={changeItem("source_type","media"); changeItem("source_id",m.getLong("id").toString()); changeItem("kind",if(m.getString("kind")=="image") "frame" else "recorder")}) {Text(if(m.getString("kind")=="image") "照片 → 相框" else "录音 → 录音机")}
                }
                if(edit.optString("source_type")=="diary") Row {for((key,label) in listOf("book" to "本子","keepsake" to "纪念物")) FilterChip(selected=edit.optString("kind")==key,onClick={changeItem("kind",key)},label={Text(label)})}
            }
            Text("替换物品只改变空间摆放，原日记和视频信不受影响。",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={task {
            val body=JSONObject(edit.toString()).put("room_version",room.getInt("version"))
            store.api("emotion-space/items","PUT",body); placement=null; proposals=proposals.filter{it.optLong("diary_id").toString()!=body.optString("source_id")}; page="room"; refresh()
        }},enabled=!busy&&edit.optString("source_id").isNotBlank()) {Text("确认放入")}},dismissButton={TextButton(onClick={placement=null}){Text("取消")}})
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable private fun PrivatePlayer(path:String) {
        val player=remember(path) {
            val factory=DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(90000).setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${session.getAccessToken()}","Cache-Control" to "no-store","Connection" to "close"))
            ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                setMediaItem(MediaItem.fromUri(ApiEndpoints.apiBaseUrl+path)); prepare(); playWhenReady=false
            }
        }
        DisposableEffect(player) { onDispose {player.release()} }
        androidx.compose.ui.window.Dialog(onDismissRequest={playing=null},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFFFFFCF3)) {
                Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("私人预览",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(12.dp))
                        TextButton(onClick={playing=null}) {Text("关闭")}
                    }
                    AndroidView(factory={PlayerView(it).apply {this.player=player}},modifier=Modifier.fillMaxWidth().weight(1f))
                    Text("视频仅供自己查看",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(10.dp))
                }
            }
        }
    }
}
