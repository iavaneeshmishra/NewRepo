package app.ripple.mesh.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ripple.mesh.R
import app.ripple.mesh.core.Backup
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.ui.MeshViewModel
import app.ripple.mesh.ui.Qr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Backup & restore (ROADMAP Phase 0.3; see docs/BACKUP.md).
 *
 * Creates a passphrase-encrypted `RIPPLE-BKP:v1` blob of this device's identity
 * (shown as QR + copy/share for offline transport) and restores one, re-deriving the
 * same node id and key. The heavy KDF runs off the main thread; nothing here touches
 * the mesh wire format. A leaked passphrase = lost identity — the screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: MeshViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selfId by vm.selfId.collectAsStateWithLifecycle()

    var passphrase by remember { mutableStateOf("") }
    var passphrase2 by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf(false) }

    var restoreText by remember { mutableStateOf("") }
    var restorePassphrase by remember { mutableStateOf("") }
    var restorePayload by remember { mutableStateOf<ByteArray?>(null) }
    var restoreFailure by remember { mutableStateOf<Backup.Failure?>(null) }
    var restoredNodeId by remember { mutableStateOf<String?>(null) }

    val qr = remember(blob) { blob?.let { Qr.bitmap(it) } }
    val exportable = remember(selfId) { vm.identityExportable() }

    fun copyText(value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Ripple backup", value))
    }

    fun shareText(value: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Ripple identity backup").putExtra(Intent.EXTRA_TEXT, value)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share)))
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.backup_title)) },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(R.string.backup_warning), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }

            // ---- create ----------------------------------------------------------------
            Text(stringResource(R.string.backup_create_title), style = MaterialTheme.typography.titleMedium)
            if (!exportable) {
                Text(stringResource(R.string.backup_legacy_keystore),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.backup_create_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it; blob = null; createError = false },
                    label = { Text(stringResource(R.string.backup_passphrase)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase2, onValueChange = { passphrase2 = it; blob = null; createError = false },
                    label = { Text(stringResource(R.string.backup_passphrase_confirm)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = passphrase.length >= 8 && passphrase == passphrase2,
                    onClick = {
                        scope.launch {
                            // PBKDF2 150k rounds — keep it off the UI thread.
                            val result = withContext(Dispatchers.Default) { vm.createBackupBlob(passphrase) }
                            blob = result
                            createError = result == null
                        }
                    },
                ) { Text(stringResource(R.string.backup_create)) }
                if (createError) Text(stringResource(R.string.backup_create_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                blob?.let { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            qr?.let { Image(bitmap = it, contentDescription = stringResource(R.string.backup_qr_desc), modifier = Modifier.size(220.dp)) }
                            Text(b, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { copyText(b) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.copy)) }
                                OutlinedButton(onClick = { shareText(b) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.share)) }
                            }
                            Text(stringResource(R.string.backup_created_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            HorizontalDivider()

            // ---- restore -----------------------------------------------------------------
            Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_restore_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = restoreText,
                onValueChange = { restoreText = it; restorePayload = null; restoreFailure = null; restoredNodeId = null },
                label = { Text(stringResource(R.string.backup_restore_field)) },
                isError = restoreFailure != null,
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = restorePassphrase,
                onValueChange = { restorePassphrase = it; restorePayload = null; restoreFailure = null; restoredNodeId = null },
                label = { Text(stringResource(R.string.backup_passphrase)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = restoreText.isNotBlank() && restorePassphrase.isNotEmpty(),
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { Backup.readBlob(restoreText, restorePassphrase) }
                        restorePayload = result.payload
                        restoreFailure = result.failure
                    }
                },
            ) { Text(stringResource(R.string.backup_check)) }
            restoreFailure?.let {
                Text(stringResource(if (it == Backup.Failure.PASSPHRASE) R.string.backup_bad_passphrase else R.string.backup_bad_format),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            restorePayload?.let { payload ->
                val target = NodeId.fromPublicKey(Backup.publicKeyOf(payload))
                val replacing = selfId != null && selfId!!.hex != target.hex
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.backup_restores_to, target.display),
                            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        if (replacing) {
                            Text(stringResource(R.string.backup_replace_caution), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (restoredNodeId == null) {
                            Button(onClick = {
                                scope.launch {
                                    val reason = withContext(Dispatchers.IO) { vm.restoreIdentity(payload) }
                                    if (reason == null) restoredNodeId = target.hex
                                }
                            }) { Text(stringResource(R.string.backup_install)) }
                        } else {
                            Text(stringResource(R.string.backup_restored), color = GreenOk, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                // Identity is read once at service start; restart to activate it.
                                Process.killProcess(Process.myPid())
                                kotlin.system.exitProcess(0)
                            }) { Text(stringResource(R.string.backup_restart_now)) }
                        }
                    }
                }
            }
            Text(stringResource(R.string.backup_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val GreenOk = androidx.compose.ui.graphics.Color(0xFF2E7D32)
