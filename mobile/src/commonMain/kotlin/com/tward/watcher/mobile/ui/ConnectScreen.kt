package com.tward.watcher.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tward.watcher.mobile.AppViewModel

/**
 * Entry form for the desktop watcher's connection details, exactly as printed
 * in its startup banner: LAN address, port and token.
 */
@Composable
fun ConnectScreen(viewModel: AppViewModel) {
    val form by viewModel.form.collectAsState()
    val error by viewModel.formError.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Terminal Watcher", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Enter the address, port and token printed by the desktop watcher.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = form.host,
            onValueChange = { viewModel.updateForm(form.copy(host = it)) },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.port,
            onValueChange = { viewModel.updateForm(form.copy(port = it)) },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.token,
            onValueChange = { viewModel.updateForm(form.copy(token = it)) },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = viewModel::connect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}
