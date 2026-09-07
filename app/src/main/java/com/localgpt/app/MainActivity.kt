package com.localgpt.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.localgpt.app.ui.chat.ChatScreen
import com.localgpt.app.ui.chat.ChatViewModel
import com.localgpt.app.ui.theme.LiteChatTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            Root(viewModel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun Root(viewModel: ChatViewModel) {
    val settings by viewModel.settings.collectAsState()
    val darkTheme =
        when (settings.themeMode) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    LiteChatTheme(
        darkTheme = darkTheme,
        pureBlack = settings.pureBlack,
        themeColor = androidx.compose.ui.graphics.Color(settings.themeColor.toInt()),
    ) {
        com.localgpt.app.ui.LiteChatApp(viewModel)
    }
}
