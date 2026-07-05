package com.tward.watcher.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.tward.watcher.mobile.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

/**
 * Entry point consumed by the iOS app's Swift code:
 *   MainViewControllerKt.MainViewController()
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val scope = CoroutineScope(SupervisorJob())
    val notifier = IosNotifier().also { it.requestAuthorization() }
    val viewModel = AppViewModel(scope = scope, notifier = notifier)
    return ComposeUIViewController {
        App(viewModel)
    }
}
