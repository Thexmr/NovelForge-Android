package com.novelforge.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelforge.android.data.ProjectRepository
import com.novelforge.android.ui.AppRoot
import com.novelforge.android.ui.AppViewModel
import com.novelforge.android.ui.theme.NovelForgeTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* egal: Service läuft auch ohne */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Gespeicherte Bücher laden, damit erzeugte Werke App-Neustarts überleben.
        ProjectRepository.init(applicationContext)
        requestNotificationPermissionIfNeeded()
        setContent { App() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun App() {
    NovelForgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: AppViewModel = viewModel()
            AppRoot(vm)
        }
    }
}
