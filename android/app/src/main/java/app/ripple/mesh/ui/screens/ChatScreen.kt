package app.ripple.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ripple.mesh.R
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.data.MessageEntity
import app.ripple.mesh.data.MessageStatus
import app.ripple.mesh.service.MeshService
import app.ripple.mesh.ui.MeshViewModel
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MeshViewModel, conversation: String, onBack: () -> Unit) {
    val isBroadcast = conversation == MeshService.BROADCAST_CONVERSATION
    val messages by remember(conversation) { vm.messages(conversation) }.collectAsStateWithLifecycle(emptyList())
    val peer by remember(conversation) { if (isBroadcast) flowOf(null) else vm.peer(conversation) }.collectAsStateWithLifecycle(null)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(conversation) {
        vm.setVisibleConversation(conversation)
        onDispose { vm.setVisibleConversation(null) }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                title = {
                    Column {
                        Text(if (isBroadcast) stringResource(R.string.broadcast_channel) else peer?.name ?: NodeId.fromHex(conversation).display)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!isBroadcast) Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (isBroadcast) stringResource(R.string.broadcast_subtitle) else stringResource(R.string.direct_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_hint)) }, maxLines = 4,
                )
                IconButton(
                    enabled = draft.isNotBlank(),
                    onClick = { vm.send(conversation, draft.trim()); draft = "" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
            }
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.messageId }) { MessageBubble(it, showSender = isBroadcast) }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageEntity, showSender: Boolean) {
    val mine = m.outgoing
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(bg, RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showSender && !mine) {
                Text(m.fromName ?: NodeId.fromHex(m.fromNodeId).display, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.End)) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.timestamp)), style = MaterialTheme.typography.labelSmall)
                if (mine) Text(
                    when (m.status) {
                        MessageStatus.PENDING -> "🕓"
                        MessageStatus.SENT -> "✓"
                        MessageStatus.DELIVERED -> "✓✓"
                        MessageStatus.FAILED -> "!"
                        MessageStatus.RECEIVED -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                ) else if (!m.verified) Text(stringResource(R.string.unverified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
