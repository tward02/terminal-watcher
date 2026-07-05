package com.tward.watcher.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tward.watcher.core.client.ConnectionState
import com.tward.watcher.core.client.WatcherClientApi
import com.tward.watcher.core.model.TerminalLine

/** Live terminal view: status bar, scrolling monospace output, disconnect action. */
@Composable
fun TerminalScreen(
    client: WatcherClientApi,
    onDisconnect: () -> Unit,
) {
    val state by client.state.collectAsState()
    val lines by client.lines.collectAsState()
    val status by client.processStatus.collectAsState()
    val listState = rememberLazyListState()

    // Follow the output as it arrives.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusLabel(state, status),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101418)),
        ) {
            items(lines, key = { it.seq }) { line ->
                TerminalLineRow(line)
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    Text(
        text = line.text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = when (line.stream) {
            TerminalLine.Stream.STDOUT -> Color(0xFFE6EDF3)
            TerminalLine.Stream.STDERR -> Color(0xFFFF7B72)
            TerminalLine.Stream.SYSTEM -> Color(0xFF7EE787)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

private fun statusLabel(
    state: ConnectionState,
    status: com.tward.watcher.core.protocol.ProcessStatus?,
): String = when (state) {
    is ConnectionState.Connecting -> "Connecting..."
    is ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Failed ->
        if (state.willRetry) "Reconnecting: ${state.reason}" else "Failed: ${state.reason}"
    is ConnectionState.Connected -> {
        val process = when {
            status == null || status.running -> "running"
            else -> "exited (${status.exitCode})"
        }
        "${state.hello.description} - $process"
    }
}
