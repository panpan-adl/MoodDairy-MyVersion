package com.example.mydiary.ui.profile

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.mydiary.ui.theme.DividerColor
import com.example.mydiary.ui.theme.Error
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val editState by viewModel.editState.collectAsStateWithLifecycle()
    val avatarUploadState by viewModel.avatarUploadState.collectAsStateWithLifecycle()

    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val email by viewModel.email.collectAsStateWithLifecycle()
    val gender by viewModel.gender.collectAsStateWithLifecycle()
    val birthday by viewModel.birthday.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()

    val nicknameError by viewModel.nicknameError.collectAsStateWithLifecycle()
    val phoneError by viewModel.phoneError.collectAsStateWithLifecycle()
    val emailError by viewModel.emailError.collectAsStateWithLifecycle()

    var showGenderDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            uriToFile(context, it)?.let(viewModel::uploadAvatar)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            tempPhotoUri?.let { uri ->
                uriToFile(context, uri)?.let(viewModel::uploadAvatar)
            }
        }
    }

    LaunchedEffect(editState) {
        if (editState is EditState.Success) {
            viewModel.resetState()
            onNavigateBack()
        }
    }

    LaunchedEffect(avatarUploadState) {
        if (avatarUploadState is AvatarUploadState.Success) {
            viewModel.resetAvatarUploadState()
        }
    }

    if (showDatePicker) {
        LaunchedEffect(showDatePicker) {
            showBirthdayPickerDialog(
                context = context,
                currentBirthday = birthday,
                onDateSelected = { selected ->
                    viewModel.onBirthdayChange(selected)
                    showDatePicker = false
                },
                onDismiss = { showDatePicker = false },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑资料", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "back",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::saveProfile,
                        enabled = editState !is EditState.Loading,
                    ) {
                        if (editState is EditState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.1f),
                            PaleYellow.copy(alpha = 0.1f),
                            SoftWhite,
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 3.dp,
                                    brush = Brush.linearGradient(listOf(MintGreen, PaleYellow)),
                                    shape = CircleShape,
                                )
                                .clickable { showImageSourceDialog = true },
                        ) {
                            if (!avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = "avatar",
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
                                        modifier = Modifier.size(60.dp),
                                        tint = Primary,
                                    )
                                }
                            }

                            if (avatarUploadState is AvatarUploadState.Loading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(TextPrimary.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = Surface, strokeWidth = 3.dp)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Primary)
                                    .border(2.dp, Surface, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "change_avatar",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("点击更换头像", fontSize = 14.sp, color = TextSecondary)

                        if (avatarUploadState is AvatarUploadState.Error) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = (avatarUploadState as AvatarUploadState.Error).message,
                                fontSize = 12.sp,
                                color = Error,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = viewModel::onNicknameChange,
                            label = { Text("昵称") },
                            placeholder = { Text("请输入昵称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                focusedLabelColor = Primary,
                            ),
                            isError = nicknameError != null,
                            supportingText = nicknameError?.let { { Text(it, color = Error) } },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        ReadOnlyPickerField(
                            label = "性别",
                            value = when (gender) {
                                1 -> "男"
                                2 -> "女"
                                else -> "未知"
                            },
                            trailingIcon = Icons.Default.ArrowDropDown,
                            onClick = { showGenderDialog = true },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        ReadOnlyPickerField(
                            label = "生日",
                            value = birthday ?: "未设置",
                            trailingIcon = Icons.Default.CalendarToday,
                            onClick = { showDatePicker = true },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = phone,
                            onValueChange = viewModel::onPhoneChange,
                            label = { Text("手机号") },
                            placeholder = { Text("请输入手机号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                focusedLabelColor = Primary,
                            ),
                            isError = phoneError != null,
                            supportingText = phoneError?.let { { Text(it, color = Error) } },
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = viewModel::onEmailChange,
                            label = { Text("邮箱") },
                            placeholder = { Text("请输入邮箱") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                focusedLabelColor = Primary,
                            ),
                            isError = emailError != null,
                            supportingText = emailError?.let { { Text(it, color = Error) } },
                        )
                    }
                }

                if (editState is EditState.Error) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "error",
                                tint = Error,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = (editState as EditState.Error).message,
                                color = Error,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showGenderDialog) {
        AlertDialog(
            onDismissRequest = { showGenderDialog = false },
            title = { Text("选择性别") },
            text = {
                Column {
                    listOf(0 to "未知", 1 to "男", 2 to "女").forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onGenderChange(value)
                                    showGenderDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = gender == value,
                                onClick = {
                                    viewModel.onGenderChange(value)
                                    showGenderDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Primary),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = Surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text("选择图片来源") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImageSourceDialog = false
                                imagePickerLauncher.launch("image/*")
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "gallery",
                            tint = Primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("从相册选择")
                    }

                    Divider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImageSourceDialog = false
                                val uri = createTempImageUri(context)
                                tempPhotoUri = uri
                                cameraLauncher.launch(uri)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "camera",
                            tint = Primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("拍照")
                    }
                }
            },
            confirmButton = {},
            containerColor = Surface,
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@Composable
private fun ReadOnlyPickerField(
    label: String,
    value: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(imageVector = trailingIcon, contentDescription = null)
            },
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor = DividerColor,
                disabledLabelColor = TextSecondary,
                disabledTextColor = TextPrimary,
                disabledTrailingIconColor = TextSecondary,
            ),
        )
    }
}

private fun showBirthdayPickerDialog(
    context: Context,
    currentBirthday: String?,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val initialDate = runCatching { LocalDate.parse(currentBirthday ?: "", formatter) }
        .getOrDefault(LocalDate.now())

    val dialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selected = LocalDate.of(year, month + 1, dayOfMonth).format(formatter)
            onDateSelected(selected)
        },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth,
    )
    dialog.setOnDismissListener { onDismiss() }
    dialog.show()
}

private fun createTempImageUri(context: Context): Uri {
    val imageFile = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile,
    )
}

private fun uriToFile(context: Context, uri: Uri): File? {
    return runCatching {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        file
    }.getOrNull()
}
