package app.ripple.mesh.ui

import androidx.lifecycle.ViewModel
import app.ripple.mesh.fieldtest.FieldTestResult
import app.ripple.mesh.fieldtest.FieldTestSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the current field-test run. Kept separate from [MeshViewModel] because
 * it has no dependency on the mesh service or Room — a session is just an
 * immutable in-memory record of verdicts and notes, resettable at any time.
 */
class FieldTestViewModel : ViewModel() {
    private val _session = MutableStateFlow(FieldTestSession())
    val session: StateFlow<FieldTestSession> = _session

    /** Convenience for screens: any scenario a tester may still run (NOT_RUN). */
    val unfinishedIds: List<String>
        get() = app.ripple.mesh.fieldtest.FieldTestCatalog.all.map { it.id }.filter { _session.value.entry(it).result == FieldTestResult.NOT_RUN }

    fun record(id: String, result: FieldTestResult, note: String? = null) {
        _session.update { it.record(id, result, note) }
    }

    fun setNote(id: String, note: String) {
        _session.update { it.setNote(id, note) }
    }

    fun reset() {
        _session.value = FieldTestSession()
    }
}
