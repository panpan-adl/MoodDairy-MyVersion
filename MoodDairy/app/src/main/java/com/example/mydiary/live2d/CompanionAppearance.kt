package com.example.mydiary.live2d

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One persisted appearance for the preview, chat avatars and floating companion. */
object CompanionAppearance {
    private val selected = MutableStateFlow(Live2DConfig.defaultModel)
    val current = selected.asStateFlow()
    private var initialized = false

    internal fun resolve(id: String?) = Live2DConfig.getModelById(id.orEmpty()) ?: Live2DConfig.defaultModel

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        selected.value = resolve(context.applicationContext
            .getSharedPreferences("companion", Context.MODE_PRIVATE).getString("appearance", null))
        initialized = true
    }

    @Synchronized
    fun select(context: Context, id: String) {
        initialize(context)
        val model = resolve(id)
        context.applicationContext.getSharedPreferences("companion", Context.MODE_PRIVATE)
            .edit().putString("appearance", model.id).apply()
        selected.value = model
    }
}

@Composable
fun rememberCompanionAppearance(): Live2DConfig.ModelInfo {
    val context = LocalContext.current
    LaunchedEffect(context.applicationContext) { CompanionAppearance.initialize(context) }
    return CompanionAppearance.current.collectAsState().value
}
