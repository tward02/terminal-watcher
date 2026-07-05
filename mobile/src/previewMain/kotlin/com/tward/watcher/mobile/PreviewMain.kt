package com.tward.watcher.mobile

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tward.watcher.mobile.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop preview of the phone app, sized like a phone. Useful for developing
 * the viewer UI on machines without an Android SDK or Xcode:
 *   gradlew :mobile:run
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob())
    val viewModel = AppViewModel(scope = scope, notifier = ConsoleNotifier())
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Terminal Watcher (preview)",
            state = rememberWindowState(width = 400.dp, height = 800.dp),
        ) {
            App(viewModel)
        }
    }
}
