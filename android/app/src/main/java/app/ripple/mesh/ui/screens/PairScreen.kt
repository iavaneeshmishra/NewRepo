package app.ripple.mesh.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.core.Pairing
import app.ripple.mesh.core.hexToBytes
import app.ripple.mesh.core.toHex
import app.ripple.mesh.data.PeerEntity
import app.ripple.mesh.data.VerifiedPeer
import app.ripple.mesh.data.VerifiedPeers
import app.ripple.mesh.ui.MeshViewModel
import app.ripple.mesh.ui.Qr
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Pair & verify (ROADMAP Phase 0.2; see docs/PAIRING.md).
 *
 * Shows this device's identity code as a QR (ZXing, render-only — no camera), lets it
 * be copied/shared as text, imports a peer's code by paste with a parsed summary +
 * 12-digit safety code, and lists the persisted verified peers. A same-id/different-key
 * import is refused via the shared [Pairing.verifyOutcome] rule, never silently re-pinned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(vm: MeshViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selfKeyHex by vm.selfPublicKeyHex.collectAsStateWithLifecycle()
    val selfName by vm.displayName.collectAsStateWithLifecycle()
    val selfId by vm.selfId.collectAsStateWithLifecycle()
    val livePeers by vm.peers.collectAsStateWithLifecycle()
    val verifiedFlow = remember(context) { VerifiedPeers.flow(context) }
    val verified by verifiedFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val identityCode = remember(selfKeyHex, selfName) {
        selfKeyHex?.let { Pairing.encodeIdentityCode(it.hexToBytes(), selfName) }
    }
    val qr = remember(identityCode) { identityCode?.let { Qr.bitmap(it) } }

    var importText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<Pairing.IdentityCode?>(null) }
    var parseFailed by remember { mutableStateOf(false) }
    var pinNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text to isError

    fun copyText(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    fun shareText(value: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Ripple identity code").putExtra(Intent.EXTRA_TEXT, value)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.pair_share)))
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.pair_verify)) },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- own identity -------------------------------------------------------
            Text(stringResource(R.string.your_identity), style = MaterialTheme.typography.titleMedium)
            Text(selfId?.display ?: "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            if (identityCode == null) {
                Text(stringResource(R.string.pair_wait_identity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        qr?.let { Image(bitmap = it, contentDescription = stringResource(R.string.pair_qr_desc), modifier = Modifier.size(220.dp)) }
                        Text(
                            identityCode, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { copyText("Ripple identity code", identityCode) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.copy)) }
                            OutlinedButton(onClick = { shareText(identityCode) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share)) }
                        }
                    }
                }
                Text(stringResource(R.string.pair_own_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            // ---- import a peer's code -------------------------------------------------
            Text(stringResource(R.string.pair_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pair_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it; parsed = null; parseFailed = false; pinNote = null },
                label = { Text(stringResource(R.string.pair_import_field)) },
                isError = parseFailed,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val code = Pairing.decodeIdentityCode(importText.trim())
                    parsed = code
                    parseFailed = code == null
                    pinNote = null
                },
                enabled = importText.isNotBlank(),
            ) { Text(stringResource(R.string.pair_import)) }
            if (parseFailed) {
                Text(stringResource(R.string.pair_import_bad), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            parsed?.let { p ->
                val ownKey = selfKeyHex
                val selfPairing = ownKey != null && p.publicKeyWireHex == ownKey.lowercase(Locale.ROOT)
                val safety = ownKey?.let { runCatching { Pairing.safetyCode(it.hexToBytes(), p.publicKeyWireHex.hexToBytes()) }.getOrNull() }
                val meshPeer = livePeers.firstOrNull { it.nodeId == p.nodeIdHex }
                val meshKeyMismatch = meshPeer != null && meshPeer.publicKeyWire.toHex() != p.publicKeyWireHex
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name ?: stringResource(R.string.pair_unnamed, p.nodeIdHex.takeLast(4)), style = MaterialTheme.typography.titleMedium)
                        Text(NodeId.fromHex(p.nodeIdHex).display, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.pair_key_prefix, p.publicKeyWireHex.take(8), p.publicKeyWireHex.takeLast(8)),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (safety != null) {
                            Text(stringResource(R.string.pair_safety_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(safety, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.pair_safety_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selfPairing) {
                            Text(stringResource(R.string.pair_own_code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (meshKeyMismatch) {
                            Text(stringResource(R.string.pair_mesh_key_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = !selfPairing && ownKey != null && safety != null,
                                onClick = {
                                    val key = ownKey ?: return@Button
                                    val code = safety ?: return@Button
                                    scope.launch {
                                        val (outcome, _) = VerifiedPeers.pin(context, p.nodeIdHex, p.publicKeyWireHex, p.name, code)
                                        val res = when (outcome) {
                                            Pairing.VerifyOutcome.VERIFIED -> R.string.pair_pinned to false
                                            Pairing.VerifyOutcome.ALREADY_VERIFIED -> R.string.pair_repinned to false
                                            Pairing.VerifyOutcome.CONFLICT -> R.string.pair_conflict to true
                                        }
                                        pinNote = context.getString(res.first) to res.second
                                        if (!res.second) { importText = ""; parsed = null }
                                    }
                                },
                            ) { Text(stringResource(R.string.pair_pin)) }
                            OutlinedButton(onClick = { copyText("Ripple identity code", p.encode()) }) { Text(stringResource(R.string.copy)) }
                        }
                        pinNote?.let { (msg, isError) ->
                            Text(msg, color = if (isError) MaterialTheme.colorScheme.error else GreenOk, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            HorizontalDivider()

            // ---- verified peers --------------------------------------------------------
            Text(stringResource(R.string.pair_verified_title), style = MaterialTheme.typography.titleMedium)
            if (verified.isEmpty()) {
                Text(stringResource(R.string.pair_verified_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            verified.forEach { v ->
                VerifiedRow(
                    peer = v,
                    livePeers = livePeers,
                    onUnpin = { scope.launch { VerifiedPeers.unpin(context, v.nodeIdHex) } },
                    onCopy = { copyText("Ripple identity code", it) },
                )
            }
            Text(stringResource(R.string.pair_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val GreenOk = Color(0xFF2E7D32)

@Composable
private fun VerifiedRow(peer: VerifiedPeer, livePeers: List<PeerEntity>, onUnpin: () -> Unit, onCopy: (String) -> Unit) {
    val current = livePeers.firstOrNull { it.nodeId == peer.nodeIdHex }
    // Defence-in-depth: the pinned key vs whatever the mesh peer table currently holds for that id
    // (v1 can only disagree on a ~2^64 id collision — see ROADMAP 0.2 scope note — but show it if so).
    val mismatch = current != null && current.publicKeyWire.toHex() != peer.publicKeyWireHex
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(peer.name ?: "Peer ${peer.nodeIdHex.takeLast(4)}", style = MaterialTheme.typography.titleMedium)
                    Text(NodeId.fromHex(peer.nodeIdHex).display, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onCopy(Pairing.IdentityCode(peer.nodeIdHex, peer.publicKeyWireHex, peer.name).encode()) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                }
                IconButton(onClick = onUnpin) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.pair_unpin)) }
            }
            Text(peer.safetyCode, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(
                "Pinned ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(peer.addedAtMs))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mismatch) {
                Text(stringResource(R.string.pair_mesh_key_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
