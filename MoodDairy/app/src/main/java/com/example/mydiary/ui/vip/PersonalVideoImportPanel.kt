package com.example.mydiary.ui.vip

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.network.DiaryApiService
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalVideoViewModel @Inject constructor(private val api: DiaryApiService) : ViewModel() {
    val message = MutableStateFlow("尚未查询导入记录")
    val busy = MutableStateFlow(false)
    val cursor = MutableStateFlow("0")
    val hasMore = MutableStateFlow(false)
    val stopRequested = MutableStateFlow(false)
    val completed = MutableStateFlow(false)
    private var sourceIdentity = ""
    fun reset() { cursor.value = "0"; hasMore.value = false; completed.value = false; sourceIdentity = "" }
    fun stop() { stopRequested.value = true }
    fun status() { viewModelScope.launch {
        try {
            val response = api.personalVideoStatus()
            message.value = if (response.isSuccessful) "已导入：抖音 ${response.body()?.get("douyin")?.asInt ?: 0} 条，B站 ${response.body()?.get("bilibili")?.asInt ?: 0} 条" else "查询失败，请确认已运行支持个人视频的后端"
        } catch (e: CancellationException) { throw e } catch (_: Exception) { message.value = "无法连接服务器" }
    } }
    fun importPages(platform: String, account: String, cookie: String, folder: String, pages: Int) {
        if (busy.value) return
        val source = VideoImportSource.parse(platform, if (platform == "douyin") account else folder)
        if (source == null) { message.value = "请填写完整主页 / 收藏夹链接或编号"; return }
        val identity = "$platform:$source"
        if (sourceIdentity != identity) { reset(); sourceIdentity = identity }
        if (completed.value) { message.value = "已到列表末尾，若有新记录请点重新扫描"; return }
        val request = JsonObject().apply {
            addProperty("platform", platform); addProperty("account", if (platform == "douyin") source else "")
            addProperty("cookie", cookie.trim()); addProperty("folder_id", if (platform == "bilibili") source else "")
            addProperty("accept_provider_charge", true)
        }
        busy.value = true
        stopRequested.value = false
        viewModelScope.launch {
            var done = 0
            var imported = 0
            try {
                val seen = mutableSetOf<String>()
                for (page in 1..pages.coerceIn(1, 10)) {
                    if (stopRequested.value) break
                    val previous = cursor.value
                    seen.add(previous)
                    request.addProperty("cursor", previous)
                    message.value = "正在读取第 $page 页，已读取 $imported 条…"
                    val response = api.importPersonalVideos(request)
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        val detail = runCatching { com.google.gson.JsonParser.parseString(response.errorBody()?.string() ?: "{}").asJsonObject.get("detail")?.asString }.getOrNull()
                        message.value = "已完成 $done 页，读取 $imported 条。" + (detail ?: "导入失败，请检查密钥、登录信息和收藏夹权限")
                        return@launch
                    }
                    val next = body.get("next_cursor").asString
                    val more = body.get("has_more").asBoolean
                    imported += body.get("imported").asInt
                    done++
                    if (more && next in seen) {
                        message.value = "分页没有前进，已停止。已完成 $done 页，读取 $imported 条。"
                        return@launch
                    }
                    cursor.value = next
                    hasMore.value = more
                    completed.value = !more
                    if (!more) break
                }
                message.value = "已完成 $done 页，读取 $imported 条（重复记录会更新）。" + when {
                    completed.value -> "已到列表末尾。"
                    stopRequested.value -> "已停止，可从下一页继续。"
                    else -> "还有更多记录，可继续导入。"
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { message.value = "已完成 $done 页，读取 $imported 条。当前页请求未完成；重试会去重，但接口可能再次计费。" }
            finally { busy.value = false; request.remove("cookie") }
        }
    }
    fun delete(platform: String) { if (busy.value) return; busy.value = true; viewModelScope.launch {
        try { val response = api.deletePersonalVideos(platform); if (response.isSuccessful) { reset(); message.value = "已删除该平台导入记录" } else message.value = "删除失败" }
        catch (e: CancellationException) { throw e } catch (_: Exception) { message.value = "无法连接服务器" }
        finally { busy.value = false }
    } }
}

@Composable
fun PersonalVideoImportPanel(keyBound: Boolean = false, viewModel: PersonalVideoViewModel = hiltViewModel()) {
    var platform by remember { mutableStateOf("douyin") }
    var account by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf(1) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val completed by viewModel.completed.collectAsState()
    val stopping by viewModel.stopRequested.collectAsState()
    val source = VideoImportSource.parse(platform, if (platform == "douyin") account else folder)
    LaunchedEffect(Unit) { viewModel.status() }
    DisposableEffect(Unit) { onDispose { cookie = ""; viewModel.stop() } }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("个人视频记忆库", style = MaterialTheme.typography.titleMedium)
            Text("TikHub 是本次读取的接口服务商；抖音 / B站才是视频账号。绑定 TikHub 密钥不会自动连接你的平台账号。")
            Text(if (keyBound) "① TikHub 密钥已绑定　② 选择本人记录　③ 确认导入" else "① 先在上方绑定 TikHub 密钥，再导入本人记录")
            Text("导入后可在聊天中描述大概内容，找回视频。首版搜索标题、简介和作者，尚不识别视频口播。")
            Row {
                FilterChip(selected = platform == "douyin", enabled = !busy, onClick = { platform = "douyin"; consent = false; viewModel.reset() }, label = { Text("抖音喜欢") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = platform == "bilibili", enabled = !busy, onClick = { platform = "bilibili"; cookie = ""; consent = false; viewModel.reset() }, label = { Text("B站收藏") })
            }
            if (platform == "douyin") {
                Text("这不是抖音官方扫码绑定。导入需要本人的网页版登录信息，并会将其发送给 TikHub；后端不保存它。")
                OutlinedTextField(account, { account = it; consent = false; viewModel.reset() }, label = { Text("抖音主页链接或账号编号") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(cookie, { cookie = it }, label = { Text("本人的抖音网页登录 Cookie") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            } else {
                Text("仅导入可访问的收藏夹，不能读取私密收藏夹或完整点赞历史。收藏夹编号可从收藏夹网页的 fid 参数查看。")
                OutlinedTextField(folder, { folder = it; consent = false; viewModel.reset() }, label = { Text("B站收藏夹网页链接或编号") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            Row {
                TextButton(enabled = !busy, onClick = { clipboard.getText()?.text?.let { if (platform == "douyin") account = it else folder = it; consent = false; viewModel.reset() } }) { Text("粘贴链接") }
                TextButton(onClick = { showGuide = true }) { Text("如何获取？") }
            }
            if (source != null) Text("已识别：$source")
            else if ((if (platform == "douyin") account else folder).isNotBlank()) Text("链接无法识别，请使用完整网页链接，短链接暂不支持。", color = MaterialTheme.colorScheme.error)
            Text("每次最多导入多少页？")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 10).forEach { count -> FilterChip(selected = pages == count, enabled = !busy, onClick = { pages = count; consent = false }, label = { Text("$count 页") }) }
            }
            Row { Checkbox(consent, { consent = it }, enabled = !busy); Text("我确认导入本人记录，并同意最多 $pages 次 TikHub 请求按实际接口价格收费。") }
            Text(message)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(enabled = !stopping, onClick = viewModel::stop) { Text(if (stopping) "将在当前页完成后停止" else "停止后续导入") }
            }
            Button(enabled = !busy && !completed && keyBound && consent && source != null && (platform != "douyin" || cookie.isNotBlank()), onClick = { confirmImport = true }) {
                Text(if (busy) "正在导入…" else if (hasMore) "继续导入下一页" else "导入视频")
            }
            Row {
                TextButton(enabled = !busy, onClick = viewModel::reset) { Text("重新扫描") }
                TextButton(enabled = !busy, onClick = viewModel::status) { Text("查询数量") }
                TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("删除该平台记录") }
            }
            if (platform == "douyin" && cookie.isNotBlank()) TextButton(enabled = !busy, onClick = { cookie = "" }) { Text("清除本次登录信息") }
        }
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("确认导入本人视频") }, text = { Text("本次最多读取 $pages 页，最多发起 $pages 次 TikHub 请求；实际费用以 TikHub 为准。遇到失败或列表末尾会停止。" + if (platform == "douyin") "本次登录信息将发送给 TikHub，后端不保存。" else "请确认这是你本人的可访问收藏夹。") }, confirmButton = { TextButton(onClick = { confirmImport = false; viewModel.importPages(platform, account, cookie, folder, pages) }) { Text("确认并开始") } }, dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
    if (showGuide) AlertDialog(onDismissRequest = { showGuide = false }, title = { Text(if (platform == "douyin") "准备抖音喜欢列表" else "准备 B站收藏夹") }, text = {
        Column {
            Text(if (platform == "douyin") "1. 在电脑浏览器登录你自己的抖音账号。\n2. 打开个人主页，复制完整的 /user/MS4w… 网页地址。\n3. 在浏览器开发者工具的网络面板，查看抖音网页请求的请求头，复制 Cookie 内容到此页。不要复制给他人或发到聊天里。\n4. 手机粘贴时选择本人的登录信息，退出此页后需重新填写。\n此处是一次性读取信息，不是官方扫码绑定，也不会后台自动同步。" else "1. 登录自己的 B站账号，打开个人空间的收藏夹。\n2. 选中要导入的可访问收藏夹。\n3. 复制浏览器地址中包含 fid=数字 的完整链接；也可以只填写该数字。\n此处不需要 B站 Cookie，仅支持可访问的收藏夹。")
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (platform == "douyin") "https://www.douyin.com/" else "https://space.bilibili.com/"))) } }) { Text("打开平台网页") }
        }
    }, confirmButton = { TextButton(onClick = { showGuide = false }) { Text("知道了") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除导入记录？") }, text = { Text("只删除情绪日记中的记录，原平台视频不受影响。") }, confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(platform) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
