package org.ddev.nabucidclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.multiAddress,
            onValueChange = viewModel::onMultiAddressChanged,
            label = { Text("Multiaddress ноды") }
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.cid,
            onValueChange = viewModel::onCidChanged,
            label = { Text("CID") }
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.pingIntervalSeconds,
            onValueChange = viewModel::onPingIntervalChanged,
            label = { Text("Интервал пинга, сек (>=1)") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::connect) { Text("Connect") }
            Button(onClick = viewModel::fetchCid) { Text("Fetch CID") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::startPing, enabled = !state.isPingRunning) { Text("Start Ping") }
            Button(onClick = viewModel::stopPing, enabled = state.isPingRunning) { Text("Stop Ping") }
        }

        if (state.isBusy) {
            CircularProgressIndicator()
        }

        Text("Статус: ${state.connectionStatus}", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Latency: ${state.latencyMs?.let { "$it ms" } ?: "—"}",
            style = MaterialTheme.typography.bodyLarge
        )

        state.errorMessage?.let { error ->
            Text(
                text = "Ошибка: $error",
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Результат по CID:", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (state.cidResult.isBlank()) "Пока нет данных" else state.cidResult,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
