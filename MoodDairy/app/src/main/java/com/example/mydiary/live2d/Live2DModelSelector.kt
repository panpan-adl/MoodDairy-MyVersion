package com.example.mydiary.live2d

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Live2D 模型选择器 - 使用配置化版本
 */
@Composable
fun Live2DModelSelector(
    currentModel: String,
    onModelChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val models = Live2DConfig.availableModels

    Column(modifier = modifier) {
        Text(
            text = "选择模型",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            models.forEach { model ->
                OutlinedButton(
                    onClick = { onModelChanged(model.path) },
                    enabled = currentModel != model.path,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (currentModel == model.path)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            Color.Transparent
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.labelLarge
                        )
                        model.description?.let { desc ->
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
