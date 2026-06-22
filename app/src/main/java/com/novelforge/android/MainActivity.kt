package com.novelforge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelforge.android.ui.AppRoot
import com.novelforge.android.ui.AppViewModel
import com.novelforge.android.ui.theme.NovelForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
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
