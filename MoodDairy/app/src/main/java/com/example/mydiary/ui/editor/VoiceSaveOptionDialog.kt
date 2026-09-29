package com.example.mydiary.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary

/**
 * 语音保存选项数据类
 * 
 * 定义用户选择的保存方式，并计算对应的 save_mode 值
 * 
 * 需求: 1.2, 1.3, 1.4
 * - save_mode=1: 仅保存原语音
 * - save_mode=2: 仅转换成流畅文字
 * - save_mode=3: 两者都保存
 * - save_mode=0: 无效（未选择任何选项）
 */
data class VoiceSaveOptions(
    val saveAudio: Boolean = false,
    val convertToText: Boolean = false
) {
    /**
     * 检查选项是否有效（至少选择一项）
     * 需求: 1.5
     */
    val isValid: Boolean
        get() = saveAudio || convertToText
    
    /**
     * 计算 save_mode 值
     * 需求: 1.2, 1.3, 1.4
     */
    val saveMode: Int
        get() = when {
            saveAudio && convertToText -> 3  // 两者都保存
            convertToText -> 2               // 仅转换成流畅文字
            saveAudio -> 1                   // 仅保存原语音
            else -> 0                        // 无效
        }
}

/**
 * 语音保存选项对话框
 * 
 * 录音完成后显示，让用户选择保存方式：
 * - 保存原语音：保留原始音频文件
 * - 转换成流畅文字：进行 ASR + 去填充词 + LLM 优化 + 情感分析
 * 
 * 需求: 1.1, 1.5
 * 
 * @param onDismiss 关闭对话框回调
 * @param onConfirm 确认选项回调，传递 saveAudio 和 convertToText 两个布尔值
 */
@Composable
fun VoiceSaveOptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (saveAudio: Boolean, convertToText: Boolean) -> Unit
) {
    // 选项状态
    var options by remember { mutableStateOf(VoiceSaveOptions()) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                Text(
                    text = "选择保存方式",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 提示文字
                Text(
                    text = "请选择您希望如何保存这段录音",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 选项1：保存原语音
                SaveOptionCheckbox(
                    checked = options.saveAudio,
                    onCheckedChange = { checked ->
                        options = options.copy(saveAudio = checked)
                    },
                    title = "保存原语音",
                    description = "在日记中保留原始语音播放器"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 选项2：转换成流畅文字
                SaveOptionCheckbox(
                    checked = options.convertToText,
                    onCheckedChange = { checked ->
                        options = options.copy(convertToText = checked)
                    },
                    title = "转换成流畅文字",
                    description = "自动去除口语填充词，优化成书面文字，并分析情绪"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 未选择任何选项时的提示
                if (!options.isValid) {
                    Text(
                        text = "请至少选择一项",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // 取消按钮
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "取消",
                            color = TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 确认按钮 - 需求 1.5: 未选择任何选项时禁用
                    Button(
                        onClick = {
                            onConfirm(options.saveAudio, options.convertToText)
                        },
                        enabled = options.isValid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            disabledContainerColor = Primary.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "确认",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 保存选项复选框组件
 * 
 * @param checked 是否选中
 * @param onCheckedChange 选中状态变化回调
 * @param title 选项标题
 * @param description 选项描述
 */
@Composable
private fun SaveOptionCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                Primary.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        onClick = { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = Primary,
                    uncheckedColor = TextSecondary
                )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
