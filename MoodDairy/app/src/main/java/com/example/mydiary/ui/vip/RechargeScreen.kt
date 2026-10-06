package com.example.mydiary.ui.vip

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.models.BindTikHubKeyRequest
import com.example.mydiary.data.models.SocialSearchStatus
import com.example.mydiary.data.network.DiaryApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RechargeViewModel @Inject constructor(
    private val apiService: DiaryApiService,
) : ViewModel() {

    private val _status = MutableStateFlow<SocialSearchStatus?>(null)
    val status: StateFlow<SocialSearchStatus?> = _status.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _binding = MutableStateFlow(false)
    val binding: StateFlow<Boolean> = _binding.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val resp = apiService.getSocialSearchStatus()
                if (resp.isSuccessful) {
                    _status.value = resp.body()
                } else {
                    _toast.tryEmit("状态查询失败（HTTP ${resp.code()}），请确认后端已启动")
                }
            } catch (e: Exception) {
                _toast.tryEmit("无法连接服务器：${e.message ?: "请稍后重试"}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun bind(rawApiKey: String) {
        val apiKey = rawApiKey.trim()
        if (apiKey.length < 16) {
            _toast.tryEmit("密钥看起来不完整，请重新复制粘贴")
            return
        }
        viewModelScope.launch {
            _binding.value = true
            try {
                val resp = apiService.bindSocialSearchKey(BindTikHubKeyRequest(apiKey))
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    _status.value = body
                    if (body.bound) {
                        _toast.tryEmit(
                            if (body.unlocked) "密钥绑定成功，可以开始全网搜索啦"
                            else "密钥绑定成功，账户余额不足，请先充值",
                        )
                    } else {
                        _toast.tryEmit(body.message ?: "密钥验证失败，请检查后重试")
                    }
                } else {
                    _toast.tryEmit("绑定失败（HTTP ${resp.code()}）")
                }
            } catch (e: Exception) {
                _toast.tryEmit("无法连接服务器：${e.message ?: "请稍后重试"}")
            } finally {
                _binding.value = false
            }
        }
    }

    fun unbind() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val resp = apiService.unbindSocialSearchKey()
                if (resp.isSuccessful) {
                    _status.value = resp.body()
                    _toast.tryEmit("已解绑，可以更换其他密钥")
                } else {
                    _toast.tryEmit("解绑失败（HTTP ${resp.code()}）")
                }
            } catch (e: Exception) {
                _toast.tryEmit("无法连接服务器：${e.message ?: "请稍后重试"}")
            } finally {
                _loading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(
    onNavigateBack: () -> Unit,
    viewModel: RechargeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status by viewModel.status.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val binding by viewModel.binding.collectAsState()
    var apiKeyInput by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toast.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全网搜索 · 账户", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFF6B81)),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .background(Color(0xFFF8F8FA)),
        ) {
            val bound = status?.bound == true
            val unlocked = status?.unlocked == true

            // 顶部状态 Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(listOf(Color(0xFFFF8FA3), Color(0xFFFF6B81))),
                    )
                    .padding(24.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (unlocked) Icons.Default.CheckCircle else Icons.Default.VpnKey,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                unlocked -> "TikHub 已连接 · 可全网搜索"
                                bound -> "TikHub 已连接 · 余额不足"
                                else -> "绑定你的 TikHub 密钥"
                            },
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            bound && !status?.email.isNullOrBlank() -> "账户：${status?.email}"
                            bound -> "已绑定 API 密钥"
                            else -> "使用你自己的 TikHub 账号，免费注册，充多少用多少"
                        },
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                    )
                    if (!bound) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "搜索由 TikHub 官方按次计费（约 $0.01/次），心语不经手费用",
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            if (bound) {
                BoundAccountContent(
                    status = status,
                    loading = loading,
                    onRefresh = viewModel::refresh,
                    onBilling = { openUrl(status?.billingUrl) },
                    onUnbind = viewModel::unbind,
                )
            } else {
                UnboundContent(
                    status = status,
                    apiKeyInput = apiKeyInput,
                    onApiKeyChange = { apiKeyInput = it },
                    onPaste = {
                        clipboard.getText()?.text?.let { apiKeyInput = it.trim() }
                    },
                    onBind = { viewModel.bind(apiKeyInput) },
                    binding = binding,
                    onOpenRegister = { openUrl(status?.registerUrl) },
                    onOpenBilling = { openUrl(status?.billingUrl) },
                    onOpenApiKeys = { openUrl(status?.apiKeysUrl) },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun BoundAccountContent(
    status: SocialSearchStatus?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onBilling: () -> Unit,
    onUnbind: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 余额卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                if (status?.unlocked != true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.WarningAmber,
                            null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "付费余额为 0，暂时无法搜索（免费体验额度不能用于该接口）",
                            fontSize = 12.sp,
                            color = Color(0xFFB26A00),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                BalanceRow("付费余额（可用于搜索）", "$${"%.4f".format(status?.balance ?: 0.0)}", true)
                Spacer(Modifier.height(10.dp))
                BalanceRow("免费体验额度", "$${"%.4f".format(status?.freeCredit ?: 0.0)}", false)
            }
        }

        Button(
            onClick = onBilling,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B81)),
        ) {
            Icon(Icons.Default.Payment, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("去充值（跳转 TikHub 官方页）", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(23.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFFFF6B81), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("我已支付，刷新余额", color = Color(0xFFFF6B81), fontSize = 14.sp)
        }

        TextButton(onClick = onUnbind, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("解绑 / 更换密钥", color = Color(0xFF999999), fontSize = 12.sp)
        }

        Text(
            "充值后等待约 1 分钟到账，再点上方「刷新余额」。支付由 TikHub 官方收取，支持支付宝 / 微信。",
            fontSize = 11.sp,
            color = Color(0xFF999999),
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun BalanceRow(label: String, value: String, highlight: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF666666))
        Text(
            value,
            fontSize = if (highlight) 20.sp else 15.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) Color(0xFFFF6B81) else Color(0xFF333333),
        )
    }
}

@Composable
private fun UnboundContent(
    status: SocialSearchStatus?,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onBind: () -> Unit,
    binding: Boolean,
    onOpenRegister: () -> Unit,
    onOpenBilling: () -> Unit,
    onOpenApiKeys: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 三步引导
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("三步完成绑定（约 2 分钟）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                Spacer(Modifier.height(12.dp))
                GuideStep(
                    step = 1,
                    title = "注册 / 登录 TikHub 账号",
                    desc = "免费注册，新账号送 $0.05 体验额度",
                    actionText = "去注册",
                    onAction = onOpenRegister,
                )
                GuideStep(
                    step = 2,
                    title = "给账户充值",
                    desc = "搜索约 $0.01/次，充 $5 约可搜 500 次，支持支付宝 / 微信",
                    actionText = "去充值",
                    onAction = onOpenBilling,
                )
                GuideStep(
                    step = 3,
                    title = "创建 API 密钥并复制",
                    desc = "在后台「API 令牌 / API token」菜单点创建，复制生成的那串密钥",
                    actionText = "创建密钥",
                    onAction = onOpenApiKeys,
                )
            }
        }

        // 粘贴绑定
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("把 API 密钥粘贴到这里", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("形如 q88jMNtM… 的一长串字符", fontSize = 13.sp) },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("一键粘贴", fontSize = 13.sp)
                    }
                    if (apiKeyInput.isNotBlank()) {
                        TextButton(onClick = { onApiKeyChange("") }) {
                            Text("清空", fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onBind,
                    enabled = !binding && apiKeyInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B81)),
                ) {
                    if (binding) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.VpnKey, null, tint = Color.White, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (binding) "正在验证绑定…" else "绑定并验证",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!status?.message.isNullOrBlank() && status?.bound != true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ ${status.message}",
                        fontSize = 12.sp,
                        color = Color(0xFFE53935),
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        // 安全说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7F9)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFFFF6B81), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("安全与费用说明", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB23A55))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "· 密钥经加密后保存在心语服务器，仅用于以你的账户调用搜索接口\n" +
                        "· 搜索费用全部由 TikHub 官方从你的账户余额扣取，心语不碰你的钱\n" +
                        "· 你可以随时回到本页解绑或更换密钥",
                    fontSize = 11.sp,
                    color = Color(0xFF8A6A72),
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun GuideStep(
    step: Int,
    title: String,
    desc: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFF6B81)),
            contentAlignment = Alignment.Center,
        ) {
            Text(step.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF333333))
            Text(desc, fontSize = 11.sp, color = Color(0xFF888888), lineHeight = 16.sp)
        }
        TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text(actionText, fontSize = 12.sp, color = Color(0xFFFF6B81))
            Icon(Icons.Default.OpenInNew, null, tint = Color(0xFFFF6B81), modifier = Modifier.size(13.dp))
        }
    }
}
