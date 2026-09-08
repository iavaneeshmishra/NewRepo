package app.ripple.mesh.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import app.ripple.mesh.fieldtest.FieldTestCatalog
import app.ripple.mesh.fieldtest.FieldTestResult
import app.ripple.mesh.fieldtest.FieldTestScenario
import app.ripple.mesh.fieldtest.FieldTestSession
import app.ripple.mesh.ui.FieldTestViewModel
import app.ripple.mesh.ui.MeshViewModel

/**
 * Guided field-test mode (ROADMAP Phase 0.1): a phone-based walkthrough of the
 * docs/FIELD_TESTING.md checklist. Verdicts and notes live in the
 * FieldTestViewModel session; the share button exports a report pre-formatted
 * for the "Field test report" issue template, with a Diagnostics snapshot
 * appended for the maintainers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTestScreen(mesh: MeshViewModel, ft: FieldTestViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val session by ft.session.collectAsStateWithLifecycle()
    val status by mesh.status.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(FieldTestCatalog.all.first().id) }
    var confirmReset by remember { mutableStateOf(false) }

    // Free-form note drafts live here so typing is instant; they are committed
    // into the session when a verdict is tapped or the report is shared.
    val drafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(session.startedAtMillis) { drafts.clear() }

    // After a verdict, jump to the next scenario that still needs a run.
    LaunchedEffect(session, expandedId) {
        val current = expandedId?.let { FieldTestCatalog.byId(it) }
        if (current != null && session.entry(current.id).result != FieldTestResult.NOT_RUN) {
            val order = FieldTestCatalog.all
            val next = order.dropWhile { it.id != current.id }.drop(1).firstOrNull { session.entry(it.id).result == FieldTestResult.NOT_RUN }
            expandedId = next?.id
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            title = { Text(stringResource(R.string.field_test)) },
            actions = {
                IconButton(onClick = { confirmReset = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ft_reset)) }
                IconButton(onClick = {
                    drafts.forEach { (id, note) -> if (note.isNotBlank()) ft.setNote(id, note) }
                    val text = ft.session.value.export(meshSnapshot = mesh.diagnosticsHeader())
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Ripple field-test report")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.ft_share)))
                }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.ft_share)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SummaryCard(session, status.directLinks)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                for (tier in FieldTestCatalog.tiers) {
                    item(key = "tier-${tier.number}") {
                        Text(
                            tier.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(FieldTestCatalog.scenariosIn(tier.number).size) { i ->
                        val s = FieldTestCatalog.scenariosIn(tier.number)[i]
                        ScenarioCard(
                            scenario = s,
                            entry = session.entry(s.id),
                            expanded = expandedId == s.id,
                            draft = drafts[s.id] ?: session.entry(s.id).note,
                            onToggle = { expandedId = if (expandedId == s.id) null else s.id },
                            onDraft = { drafts[s.id] = it },
                            onVerdict = { r ->
                                ft.record(s.id, r, drafts[s.id]?.takeIf { it.isNotBlank() })
                                drafts.remove(s.id)
                            },
                        )
                    }
                }
                item(key = "bottom") { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.ft_reset)) },
            text = { Text(stringResource(R.string.ft_reset_confirm)) },
            confirmButton = { TextButton(onClick = { ft.reset(); confirmReset = false; expandedId = FieldTestCatalog.all.first().id }) { Text(stringResource(R.string.ft_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SummaryCard(session: FieldTestSession, directLinks: Int) {
    val total = FieldTestCatalog.all.size
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ft_progress, session.attemptedCount, total), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ft_progress_detail, session.passedCount, session.failedCount, session.skippedCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ft_direct_links, directLinks), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ft_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: FieldTestScenario,
    entry: app.ripple.mesh.fieldtest.ScenarioEntry,
    expanded: Boolean,
    draft: String,
    onToggle: () -> Unit,
    onDraft: (String) -> Unit,
    onVerdict: (FieldTestResult) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(scenario.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(scenario.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                VerdictMark(entry.result)
            }
            if (expanded) {
                Text(stringResource(R.string.ft_steps, scenario.steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ft_expected, scenario.expected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ft_record, scenario.record), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    label = { Text(stringResource(R.string.ft_notes)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VerdictChip(selected = entry.result == FieldTestResult.PASSED, color = Color(0xFF2E7D32), label = "PASS", onClick = { onVerdict(FieldTestResult.PASSED) })
                    VerdictChip(selected = entry.result == FieldTestResult.FAILED, color = MaterialTheme.colorScheme.error, label = "FAIL", onClick = { onVerdict(FieldTestResult.FAILED) })
                    VerdictChip(selected = entry.result == FieldTestResult.SKIPPED, color = MaterialTheme.colorScheme.outline, label = "SKIP", onClick = { onVerdict(FieldTestResult.SKIPPED) })
                }
            }
        }
    }
}

@Composable
private fun VerdictChip(selected: Boolean, color: Color, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color,
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
private fun VerdictMark(result: FieldTestResult) {
    if (result == FieldTestResult.NOT_RUN) return
    val color = when (result) {
        FieldTestResult.PASSED -> Color(0xFF2E7D32)
        FieldTestResult.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(markLabel(result), style = MaterialTheme.typography.labelMedium, color = color)
}

private fun markLabel(result: FieldTestResult): String = when (result) {
    FieldTestResult.PASSED -> "PASS ✅"
    FieldTestResult.FAILED -> "FAIL ❌"
    FieldTestResult.SKIPPED -> "SKIP ⏭"
    FieldTestResult.NOT_RUN -> ""
}
