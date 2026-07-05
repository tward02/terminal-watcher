package com.tward.watcher.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.tward.watcher.mobile.AppViewModel

/**
 * Root of the viewer UI: the connect screen until a connection is initiated,
 * then the live terminal screen.
 */
@Composable
fun App(viewModel: AppViewModel) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface {
            val client by viewModel.client.collectAsState()
            val activeClient = client
            if (activeClient == null) {
                ConnectScreen(viewModel)
            } else {
                TerminalScreen(
                    client = activeClient,
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }
}
