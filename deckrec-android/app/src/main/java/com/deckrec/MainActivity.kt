package com.deckrec

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deckrec.ui.DeckRecViewModel
import com.deckrec.ui.DetailScreen
import com.deckrec.ui.LibraryScreen
import com.deckrec.ui.RecordScreen
import com.deckrec.ui.SettingsScreen
import com.deckrec.ui.theme.DeckRecTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            DeckRecTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DeckRecRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A mixer plugged in while the app was backgrounded should be there when it comes forward.
        DeckRecApp.from(this).usbAudioScanner.refresh()
    }
}

@Composable
private fun DeckRecRoot() {
    val viewModel: DeckRecViewModel = viewModel()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    RequestRuntimePermissions(onDenied = viewModel::showMessage)
    KeepScreenOn(enabled = uiState.settings.keepScreenOn && uiState.isRecording)

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.RECORD,
            ) {
                composable(Routes.RECORD) {
                    RecordScreen(
                        viewModel = viewModel,
                        contentPadding = padding,
                        onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        viewModel = viewModel,
                        contentPadding = padding,
                        onBack = { navController.popBackStack() },
                        onOpenRecording = { id -> navController.navigate("${Routes.DETAIL}/$id") },
                    )
                }
                composable("${Routes.DETAIL}/{id}") { entry ->
                    DetailScreen(
                        viewModel = viewModel,
                        recordingId = entry.arguments?.getString("id").orEmpty(),
                        contentPadding = padding,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        contentPadding = padding,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

private object Routes {
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
}

@Composable
private fun RequestRuntimePermissions(onDenied: (String) -> Unit) {
    val view = LocalView.current
    val context = view.context
    var requested by remember { mutableStateOf(false) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == false) {
            onDenied("Recording needs microphone access — USB audio input is gated behind the same permission.")
        }
    }

    LaunchedEffect(Unit) {
        if (requested) return@LaunchedEffect
        requested = true
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
