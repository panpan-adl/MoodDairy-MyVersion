package com.example.mydiary.live2d

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** An app-window companion needs no draw-over-other-apps permission. */
@Composable
fun CompanionHost(active: Boolean, assistant: Boolean = false, showcase: Boolean = false) {
    val activity = LocalContext.current.findActivity() ?: return
    val owner = LocalLifecycleOwner.current
    val assistantNow by androidx.compose.runtime.rememberUpdatedState(assistant)
    VoiceWakeSettings.initialize(activity)
    val wakeEnabled by VoiceWakeSettings.enabled.collectAsState()
    val companionEnabled by PetOverlayService.enabled.collectAsState()
    var autoRequested by remember { mutableStateOf(false) }
    val wakePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PetOverlayService.refreshVoiceWake()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PetOverlayService.onMicrophoneResult(it)
    }
    androidx.compose.runtime.LaunchedEffect(assistant) { PetOverlayService.setAssistantPage(assistant) }
    LaunchedEffect(showcase) { PetOverlayService.setShowcasePage(showcase) }
    LaunchedEffect(active, assistant, wakeEnabled, companionEnabled) {
        if (active && assistant && wakeEnabled && companionEnabled &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !autoRequested && !activity.getSharedPreferences("companion", Context.MODE_PRIVATE).getBoolean("wake_permission_asked", false)) {
            autoRequested = true
            activity.getSharedPreferences("companion", Context.MODE_PRIVATE).edit().putBoolean("wake_permission_asked", true).apply()
            wakePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(activity, owner, active) {
        fun resume() {
            if (active) {
                PetOverlayService.setAssistantPage(assistantNow)
                PetOverlayService.requestMicrophone = { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                PetOverlayService.attach(activity)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> PetOverlayService.detach(activity)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            PetOverlayService.detach(activity)
            PetOverlayService.requestMicrophone = null
        }
    }
}

@Composable
fun CompanionToggle() {
    val context = LocalContext.current
    val enabled by PetOverlayService.enabled.collectAsState()
    TextButton(onClick = { PetOverlayService.setEnabled(context, !enabled) }) {
        Text(if (enabled) "关闭然然" else "开启然然", color = com.example.mydiary.ui.theme.TextPrimary)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
