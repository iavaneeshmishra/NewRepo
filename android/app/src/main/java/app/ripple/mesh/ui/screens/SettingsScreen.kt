package app.ripple.mesh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ripple.mesh.R
import app.ripple.mesh.ui.MeshViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MeshViewModel,
    onBack: () -> Unit,
    onOpenSos: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val selfId by vm.selfId.collectAsStateWithLifecycle()
    val savedName by vm.displayName.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    LaunchedEffect(savedName) { if (name.isEmpty()) name = savedName ?: "" }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            title = { Text(stringResource(R.string.settings)) },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.your_identity), style = MaterialTheme.typography.titleMedium)
            Text(selfId?.display ?: "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.identity_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            OutlinedTextField(value = name, onValueChange = { if (it.length <= 32) name = it }, label = { Text(stringResource(R.string.display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setDisplayName(name.trim()) }, enabled = name.isNotBlank() && name.trim() != savedName) { Text(stringResource(R.string.save)) }

            HorizontalDivider()

            Text(stringResource(R.string.mesh_status), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.status_detail, if (status.bluetoothOn) "on" else "off", status.directLinks, status.knownPeers))

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenSos, modifier = Modifier.weight(1f)) { Text("SOS beacon") }
                Button(onClick = onOpenPower, modifier = Modifier.weight(1f)) { Text("Power profile") }
            }
            OutlinedButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.diagnostics)) }
            Text(stringResource(R.string.about_blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
