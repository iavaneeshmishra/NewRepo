package app.ripple.mesh.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ripple.mesh.R
import app.ripple.mesh.core.EventLog
import app.ripple.mesh.service.LinkInfo
import app.ripple.mesh.ui.MeshViewModel
import kotlinx.coroutines.delay

/**
 * Field-debugging screen: adapter/role state, live links with RSSI and throughput,
 * the rolling event log, loopback toggle, and a share-sheet export for bug reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: MeshViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    var links by remember { mutableStateOf<List<LinkInfo>>(emptyList()) }
    var events by remember { mutableStateOf<List<EventLog.Event>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            links = vm.linkInfos()
            events = EventLog.global.snapshot()
            delay(1_000)
        }
    }
    LaunchedEffect(events.size) { if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.diagnostics)) },
            actions = {
                IconButton(onClick = { EventLog.global.clear() }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear_log)) }
                IconButton(onClick = {
                    val text = EventLog.global.export(vm.diagnosticsHeader())
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Ripple diagnostics").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_log)))
                }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_log)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // --- state summary
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusRow(stringResource(R.string.diag_bluetooth), if (status.bluetoothOn) "on" else "off", status.bluetoothOn)
                    StatusRow(stringResource(R.string.diag_advertising), if (status.advertising) "yes" else "no", status.advertising)
                    StatusRow(stringResource(R.string.diag_links), "${links.size} open · ${status.directLinks} identified", links.isNotEmpty())
                    StatusRow(stringResource(R.string.diag_peers), "${status.knownPeers}", status.knownPeers > 0)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.loopback_title), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.loopback_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = status.loopback, onCheckedChange = { vm.setLoopback(it) })
                    }
                }
            }

            // --- links
            if (links.isNotEmpty()) {
                Text(stringResource(R.string.diag_links), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                links.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${l.peerName ?: l.peerShort ?: "identifying…"}  ·  ${l.role}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${l.id}  frame ${l.frameSize}  ↓${l.packetsIn}p/${l.bytesIn}B  ↑${l.packetsOut}p/${l.bytesOut}B  age ${l.ageMs / 1000}s",
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        l.rssi?.let { RssiBadge(it) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            // --- event log
            Text(stringResource(R.string.diag_events, events.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(events) { e ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(EventLog.formatTime(e.at).take(8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(e.tag, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                        Text(
                            e.message, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = when (e.level) {
                                EventLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                EventLog.Level.WARN -> Color(0xFFB26A00)
                                EventLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                EventLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (good) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RssiBadge(rssi: Int) {
    val color = when {
        rssi > -60 -> Color(0xFF2E7D32)
        rssi > -75 -> Color(0xFFB26A00)
        else -> MaterialTheme.colorScheme.error
    }
    Text("$rssi dBm", style = MaterialTheme.typography.labelMedium, color = color, fontFamily = FontFamily.Monospace)
}
