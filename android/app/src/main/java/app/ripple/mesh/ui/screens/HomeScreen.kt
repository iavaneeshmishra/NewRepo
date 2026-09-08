package app.ripple.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ripple.mesh.R
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.data.PeerEntity
import app.ripple.mesh.service.MeshService
import app.ripple.mesh.ui.MeshViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MeshViewModel, onOpenChat: (String) -> Unit, onOpenSettings: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name))
                    Text(
                        when {
                            !status.running -> stringResource(R.string.status_starting)
                            !status.bluetoothOn -> stringResource(R.string.status_bt_off)
                            else -> pluralStringResource(R.plurals.status_links, status.directLinks, status.directLinks, status.knownPeers)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.bluetoothOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_chats)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_peers, peers.size)) })
            }
            if (tab == 0) {
                LazyColumn {
                    val broadcast = conversations.firstOrNull { it.conversation == MeshService.BROADCAST_CONVERSATION }
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.broadcast_channel)) },
                            supportingContent = { Text(broadcast?.lastText ?: stringResource(R.string.broadcast_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { if ((broadcast?.unread ?: 0) > 0) UnreadBadge(broadcast!!.unread) },
                            modifier = Modifier.clickable { onOpenChat(MeshService.BROADCAST_CONVERSATION) },
                        )
                    }
                    items(conversations.filter { it.conversation != MeshService.BROADCAST_CONVERSATION }, key = { it.conversation }) { c ->
                        val peer = peers.firstOrNull { it.nodeId == c.conversation }
                        ListItem(
                            headlineContent = { Text(peer?.name ?: NodeId.fromHex(c.conversation).display) },
                            supportingContent = { Text(c.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Avatar(c.conversation) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(c.lastTimestamp)), style = MaterialTheme.typography.labelSmall)
                                    if (c.unread > 0) UnreadBadge(c.unread)
                                }
                            },
                            modifier = Modifier.clickable { onOpenChat(c.conversation) },
                        )
                    }
                }
            } else {
                if (peers.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.peers_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.peers_empty_cta)) }
                    }
                } else {
                    LazyColumn { items(peers, key = { it.nodeId }) { PeerRow(it) { onOpenChat(it.nodeId) } } }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerEntity, onClick: () -> Unit) {
    val recent = System.currentTimeMillis() - peer.lastSeen < 5 * 60_000
    val dotLabel = stringResource(if (recent) R.string.peer_online_desc else R.string.peer_offline_desc)
    ListItem(
        headlineContent = { Text(peer.name) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(NodeId.fromHex(peer.nodeId).display, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text("· " + pluralStringResource(R.plurals.hops, peer.hops, peer.hops), style = MaterialTheme.typography.labelSmall)
            }
        },
        leadingContent = { Avatar(peer.nodeId) },
        trailingContent = {
            Box(
                Modifier.size(10.dp)
                    .background(if (recent) Color(0xFF2ECC71) else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .semantics { contentDescription = dotLabel },
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    // Resolve the plural in composable scope before entering the semantics lambda.
    val unreadLabel = pluralStringResource(R.plurals.unread_badge, count, count)
    Badge(Modifier.clearAndSetSemantics { contentDescription = unreadLabel }) { Text("$count") }
}

/** Deterministic colored circle derived from the node id. */
@Composable
fun Avatar(nodeIdHex: String) {
    val hue = (nodeIdHex.take(6).toLong(16) % 360).toFloat()
    val color = Color.hsv(hue, 0.45f, 0.75f)
    Box(Modifier.size(40.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(nodeIdHex.takeLast(2).uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
