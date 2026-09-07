package app.ripple.mesh.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ripple.mesh.core.Identity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

private val Context.settings: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Loads (or creates on first launch) the node identity from the Android Keystore.
 *
 * The Keystore holds the P-256 key non-exportably. Because ECDH is only usable on
 * Keystore keys from API 31, and this app needs ECDH for direct-message decryption,
 * we use the Keystore on API ≥ 31 and fall back to an encrypted-at-rest software key
 * (stored in DataStore, itself protected by app sandbox + file-based encryption) below.
 */
object IdentityStore {
    private const val ALIAS = "ripple-identity-v1"
    private val NAME = stringPreferencesKey("display_name")
    private val SOFT_KEY = stringPreferencesKey("soft_identity_pkcs8")
    private val POWER_PROFILE = intPreferencesKey("power_profile")

    fun load(context: Context): Identity =
        if (android.os.Build.VERSION.SDK_INT >= 31) loadKeystore() else loadSoftware(context)

    private fun loadKeystore(): Identity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return Identity(existing.privateKey, existing.certificate.publicKey)

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        return Identity(pair.private, pair.public)
    }

    private fun loadSoftware(context: Context): Identity {
        val prefs = context.getSharedPreferences("ripple-identity", Context.MODE_PRIVATE)
        val encoded = prefs.getString("pkcs8", null)
        if (encoded != null) {
            val kf = java.security.KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)))
            val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(prefs.getString("x509", "")!!, android.util.Base64.NO_WRAP)))
            return Identity(priv, pub)
        }
        val id = Identity.generate()
        prefs.edit()
            .putString("pkcs8", android.util.Base64.encodeToString(id.privateKey.encoded, android.util.Base64.NO_WRAP))
            .putString("x509", android.util.Base64.encodeToString(id.publicKey.encoded, android.util.Base64.NO_WRAP))
            .apply()
        return id
    }

    fun displayName(context: Context): Flow<String?> = context.settings.data.map { it[NAME] }

    suspend fun setDisplayName(context: Context, name: String) {
        context.settings.edit { it[NAME] = name }
    }

    /** Battery profile persists across restarts (DataStore). */
    fun powerProfile(context: Context): Flow<Int?> = context.settings.data.map { it[POWER_PROFILE] }

    suspend fun setPowerProfile(context: Context, code: Int) {
        context.settings.edit { it[POWER_PROFILE] = code }
    }
}
