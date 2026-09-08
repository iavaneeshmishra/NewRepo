package app.ripple.mesh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ripple.mesh.core.Pairing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.verifiedPeersData: DataStore<Preferences> by preferencesDataStore("verified_peers")

/**
 * A peer whose *full* public key this device pinned after out-of-band verification
 * (docs/PAIRING.md §3). The pin is the key; the 8-byte node id is only the index.
 */
data class VerifiedPeer(
    val nodeIdHex: String,
    val publicKeyWireHex: String,
    val name: String?,
    val safetyCode: String,
    val addedAtMs: Long,
)

/**
 * Persistent verified-peer store (ROADMAP Phase 0.2, app layer — nothing here touches
 * the wire). Pins live as a JSON blob in a DataStore preferences file: app-private
 * storage, at rest inside the sandbox/FBE. Public keys are not secrets, but the store
 * is still never logged (docs/PAIRING.md §6).
 *
 * The pinning *rule* is not duplicated here — it is the pure shared
 * [Pairing.verifyOutcome], identical on iOS, so both platforms refuse a same-id
 * different-key pin the same way.
 */
object VerifiedPeers {
    private val KEY = stringPreferencesKey("verified_peers_v1")
    private const val F_ID = "id"
    private const val F_KEY = "key"
    private const val F_NAME = "name"
    private const val F_SAFETY = "safety"
    private const val F_ADDED = "addedAt"

    /** Live list for the UI (re-emits on every write). */
    fun flow(context: Context): Flow<List<VerifiedPeer>> =
        context.verifiedPeersData.data.map { parse(it[KEY]) }

    suspend fun all(context: Context): List<VerifiedPeer> = parse(context.verifiedPeersData.data.first()[KEY])

    /**
     * Pin (or re-confirm) a peer key. On [Pairing.VerifyOutcome.CONFLICT] nothing is
     * written — the existing pin stays and the UI must surface the conflict.
     * Returns the outcome plus the resulting (or kept) record.
     */
    suspend fun pin(
        context: Context,
        nodeIdHex: String,
        publicKeyWireHex: String,
        name: String?,
        safetyCode: String,
    ): Pair<Pairing.VerifyOutcome, VerifiedPeer> {
        val id = nodeIdHex.lowercase()
        val key = publicKeyWireHex.lowercase()
        val peers = all(context)
        val existing = peers.firstOrNull { it.nodeIdHex == id }
        val outcome = Pairing.verifyOutcome(existing?.publicKeyWireHex, key)
        if (outcome == Pairing.VerifyOutcome.CONFLICT && existing != null) return outcome to existing

        val record = VerifiedPeer(id, key, name ?: existing?.name, safetyCode, System.currentTimeMillis())
        context.verifiedPeersData.edit { it[KEY] = serialize(peers.filterNot { p -> p.nodeIdHex == id } + record) }
        return outcome to record
    }

    /** Un-pin a peer (e.g. contact says their phone was wiped and re-paired). */
    suspend fun unpin(context: Context, nodeIdHex: String) {
        val id = nodeIdHex.lowercase()
        context.verifiedPeersData.edit { prefs ->
            prefs[KEY]?.let { prefs[KEY] = serialize(parse(it).filterNot { p -> p.nodeIdHex == id }) }
        }
    }

    private fun parse(json: String?): List<VerifiedPeer> {
        if (json.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                VerifiedPeer(
                    nodeIdHex = o.optString(F_ID).lowercase(),
                    publicKeyWireHex = o.optString(F_KEY).lowercase(),
                    name = if (o.isNull(F_NAME)) null else o.optString(F_NAME).ifBlank { null },
                    safetyCode = o.optString(F_SAFETY),
                    addedAtMs = o.optLong(F_ADDED),
                )
            }.filter { it.nodeIdHex.length == 16 && it.publicKeyWireHex.length == 130 }
        }.getOrDefault(emptyList())
    }

    private fun serialize(peers: List<VerifiedPeer>): String {
        val arr = JSONArray()
        for (p in peers) {
            arr.put(
                JSONObject()
                    .put(F_ID, p.nodeIdHex)
                    .put(F_KEY, p.publicKeyWireHex)
                    .put(F_NAME, p.name ?: JSONObject.NULL)
                    .put(F_SAFETY, p.safetyCode)
                    .put(F_ADDED, p.addedAtMs),
            )
        }
        return arr.toString()
    }
}
