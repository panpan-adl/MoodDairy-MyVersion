package com.example.mydiary.ui.profile

import com.example.mydiary.ui.components.pageVerticalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.mydiary.ui.theme.PageTopBackground
import com.example.mydiary.ui.theme.PageMiddleBackground
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.mydiary.BuildConfig
import com.example.mydiary.ui.theme.DividerColor
import com.example.mydiary.ui.theme.Error
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.PrimaryDark
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary

@Composable
fun ProfileScreen(
    onNavigateToEditProfile: () -> Unit,
    onLogout: () -> Unit,
    shouldRefresh: Boolean = false,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }

    // 使用 rememberSaveable 保存滚动位置，导航返回时恢复
    val scrollPosition = rememberSaveable { mutableStateOf(0) }
    val scrollState = rememberScrollState(scrollPosition.value)

    // 同步滚动位置
    LaunchedEffect(scrollState.value) {
        scrollPosition.value = scrollState.value
    }

    // 从编辑页面返回时强制刷新数据
    LaunchedEffect(shouldRefresh) {
        if (shouldRefresh) {
            viewModel.loadProfile(forceRefresh = true)
        }
    }

    LaunchedEffect(profileState) {
        if (profileState is ProfileState.LoggedOut) {
            onLogout()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        PageTopBackground,
                        PageMiddleBackground,
                        SoftWhite,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "个人中心",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryDark,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        when (val state = profileState) {
            is ProfileState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            }

            is ProfileState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "error",
                            tint = Error,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = state.message, color = Error, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = viewModel::loadProfile,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }

            else -> {
                val currentUser = user
                if (currentUser != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pageVerticalScroll(scrollState)
                            .padding(16.dp),
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Brush.linearGradient(listOf(Color(0xFFF6FFFA), Color(0xFFE1F5EA))))
                                    .padding(20.dp),
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = 3.dp,
                                            brush = Brush.linearGradient(listOf(MintGreen, PaleYellow)),
                                            shape = CircleShape,
                                        ),
                                ) {
                                    if (!currentUser.avatar.isNullOrBlank()) {
                                        AsyncImage(
                                            model = currentUser.avatar,
                                            contentDescription = "user_avatar",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MintGreen.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = "default_avatar",
                                                modifier = Modifier.size(40.dp),
                                                tint = Primary,
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                Text("很高兴遇见你", fontSize = 12.sp, color = PrimaryDark)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = currentUser.getDisplayNickname(),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = currentUser.signature?.takeIf { it.isNotBlank() }
                                        ?: "写一句话，介绍此刻的自己",
                                    fontSize = 14.sp,
                                    color = TextSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                }
                                Box {
                                    IconButton(onClick = { showProfileMenu = true }) {
                                        Icon(Icons.Default.MoreHoriz, contentDescription = "更多资料选项", tint = PrimaryDark)
                                    }
                                    DropdownMenu(
                                        expanded = showProfileMenu,
                                        onDismissRequest = { showProfileMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("编辑资料") },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                            onClick = {
                                                showProfileMenu = false
                                                onNavigateToEditProfile()
                                            },
                                        )
                                    }
                                }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                            ) {
                                Text(
                                    text = "个人信息",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                Text("关于你的专属小档案", fontSize = 12.sp, color = TextSecondary)
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ProfileFactTile(Icons.Default.Person, "性别", currentUser.getGenderText(), Color(0xFFEAF7F0), PrimaryDark, Modifier.weight(1f))
                                    ProfileFactTile(Icons.Default.Cake, "生日", currentUser.getDisplayBirthday(), Color(0xFFFFF5E6), Color(0xFF946B35), Modifier.weight(1.25f))
                                }
                                Spacer(modifier = Modifier.height(22.dp))
                                Text("联系方式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Spacer(modifier = Modifier.height(14.dp))
                                ProfileInfoItem(Icons.Default.Phone, "手机", currentUser.getDisplayPhone(), Color(0xFFEAF2FF), Color(0xFF527BA1))
                                Divider(modifier = Modifier.padding(start = 56.dp, top = 14.dp, bottom = 14.dp), color = DividerColor)
                                ProfileInfoItem(Icons.Default.Email, "邮箱", currentUser.getDisplayEmail(), Color(0xFFF2EEFA), Color(0xFF8570A5))
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                            border = ButtonDefaults.outlinedButtonBorder,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "logout",
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("退出登录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }

                        Text(
                            text = "心语 v${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Text(text = "确认退出", fontWeight = FontWeight.Bold)
            },
            text = { Text("确定要退出登录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                ) {
                    Text(text = "确定", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@Composable
private fun ProfileInfoItem(
    icon: ImageVector,
    label: String,
    value: String,
    background: Color,
    tint: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).background(background, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 12.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 16.sp, color = TextPrimary)
        }
    }
}

@Composable
private fun ProfileFactTile(
    icon: ImageVector,
    label: String,
    value: String,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(background, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 12.sp, color = tint)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}
