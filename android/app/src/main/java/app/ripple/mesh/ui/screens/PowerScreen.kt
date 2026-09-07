package app.ripple.mesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ripple.mesh.core.BatteryProfile
import app.ripple.mesh.ui.MeshViewModel

private data class ProfileOption(val code: Int, val title: String, val detail: String)

private val PROFILES = listOf(
    ProfileOption(BatteryProfile.PERFORMANCE, "Performance", "Relays everything at full speed. Shortest battery life."),
    ProfileOption(BatteryProfile.BALANCED, "Balanced", "Relays chat and SOS. Best trade-off for everyday use."),
    ProfileOption(BatteryProfile.POWER_SAVER, "Battery saver", "Stops relaying ordinary chat to save battery, but still forwards SOS beacons and stays reachable."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(vm: MeshViewModel, onBack: () -> Unit) {
    val selected by vm.powerProfile.collectAsState(BatteryProfile.BALANCED)

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            title = { Text("Power profile") },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "How aggressively Ripple forwards messages and manages battery. SOS beacons are always relayed regardless of profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PROFILES.forEach { p ->
                Column(Modifier.fillMaxWidth().clickable { vm.setPowerProfile(p.code) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium)
                            Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected == p.code) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
