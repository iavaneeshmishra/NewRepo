package app.ripple.mesh.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ripple.mesh.core.Crypto
import app.ripple.mesh.core.EventLog
import app.ripple.mesh.core.Identity
import app.ripple.mesh.core.NodeId
import app.ripple.mesh.core.hexToBytes
import app.ripple.mesh.core.toHex
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyGenerator
import java.security.KeyStore
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Loads (or creates on first launch) the node identity.
 *
 * Storage model since Phase 0.3 (see docs/BACKUP.md §5):
 *
 * * **New installs:** the P-256 key pair is created by the platform provider and its
 *   private *scalar* is persisted — wrapped with a non-exportable Android Keystore
 *   AES-GCM key — next to the public-key wire form, and rebuilt from the scalar at every
 *   launch. The scalar being recoverable (by this app on this device, through the
 *   Keystore wrap) is exactly what makes identity *backup & restore* possible; the old
 *   Keystore-only storage could never export the key at all.
 * * **Legacy Keystore installs** (alias `ripple-identity-v1`, API ≥ 31): keep working
 *   from the Keystore. Their key is non-exportable *by design*, so backup cannot be
 *   created from them (docs/BACKUP.md §6); restoring a backup *does* migrate them onto
 *   the new model.
 * * **Legacy software installs** (API < 31): load the stored PKCS#8 as before; backup
 *   works there because the key is a regular JCA key.
 *
 * Display name and power profile persist in the `settings` DataStore, as before.
 */
object IdentityStore {
    private const val LEGACY_KEYSTORE_ALIAS = "ripple-identity-v1"
    private const val WRAP_ALIAS = "ripple-identity-wrap-v1"
    private const val PREFS = "ripple-identity"
    private const val VERSION_SEEDED = 2
    private const val K_VERSION = "id_version"
    private const val K_WIRE = "pub_wire"                 // public key wire form, hex (not secret)
    private const val K_SCALAR = "scalar_plain"           // only when Keystore wrap unavailable
    private const val K_SCALAR_WRAPPED = "scalar_wrapped" // AES-GCM(seed) via Keystore key
    private const val K_SCALAR_IV = "scalar_iv"
    private const val K_PKCS8 = "pkcs8"                    // legacy software install
    private const val K_X509 = "x509"

    private val NAME = stringPreferencesKey("display_name")
    private val POWER_PROFILE = intPreferencesKey("power_profile")

    fun load(context: Context): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(K_VERSION, 0) >= VERSION_SEEDED) return loadSeeded(prefs)
        if (prefs.contains(K_PKCS8)) return loadLegacySoftware(prefs)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            legacyKeystoreIdentity()?.let { return it }
        }
        return createSeeded(prefs)
    }

    // ---- seeded model (Phase 0.3) ------------------------------------------------------

    private fun createSeeded(prefs: SharedPreferences): Identity {
        val pair = Crypto.generateKeyPair()
        val identity = Identity(pair.private, pair.public)
        val scalar = exportScalar(identity)
            ?: error("platform EC key unexpectedly hides its private scalar")
        writeSeeded(prefs, scalar, identity.publicKeyWire)
        return identity
    }

    /**
     * Rebuilds the identity from the stored scalar + public wire form. A Keystore
     * failure here *throws* — silently generating a fresh identity would change the node
     * id and strand every peer's pin, which is worse than a visible crash (docs/BACKUP.md §5).
     */
    private fun loadSeeded(prefs: SharedPreferences): Identity {
        val scalar = readScalar(prefs)
        val wireHex = prefs.getString(K_WIRE, null) ?: error("seeded identity missing public key")
        val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), Crypto.p256Params))
        return Identity(priv, Crypto.publicKeyFromWire(wireHex.hexToBytes()))
    }

    private fun writeSeeded(prefs: SharedPreferences, scalar: ByteArray, wire: ByteArray) {
        val editor = prefs.edit()
        editor.putInt(K_VERSION, VERSION_SEEDED).putString(K_WIRE, wire.toHex())
        editor.remove(K_SCALAR).remove(K_SCALAR_WRAPPED).remove(K_SCALAR_IV)
        // Remove the legacy fields so load() can never fall back to the old key after a restore.
        editor.remove(K_PKCS8).remove(K_X509)
        val key = runCatching { wrapKey() }.getOrNull()
        val wrapped = key?.let {
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, it)
                b64encode(cipher.doFinal(scalar)) to b64encode(cipher.iv)
            }.getOrNull()
        }
        if (wrapped != null) {
            editor.putString(K_SCALAR_WRAPPED, wrapped.first).putString(K_SCALAR_IV, wrapped.second)
        } else {
            editor.putString(K_SCALAR, b64encode(scalar))
            EventLog.global.w("identity", "Keystore wrap unavailable; identity scalar stored in app-private prefs only")
        }
        editor.apply()
    }

    private fun readScalar(prefs: SharedPreferences): ByteArray {
        prefs.getString(K_SCALAR, null)?.let { return b64decode(it) }
        val wrapped = prefs.getString(K_SCALAR_WRAPPED, null) ?: error("seeded identity has no stored scalar")
        val iv = prefs.getString(K_SCALAR_IV, null)?.let { b64decode(it) } ?: error("seeded identity missing wrap IV")
        val key = wrapKey() ?: error("Keystore wrap key missing; identity scalar unreadable on this device")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(b64decode(wrapped))
    }

    /**
     * Non-exportable AES-256-GCM key in the Android Keystore used only to wrap the identity
     * scalar at rest. Returns null only if the Keystore itself is unavailable.
     */
    private fun wrapKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    // ---- backup / restore API (Phase 0.3) ----------------------------------------------

    /**
     * The 32-byte private scalar when this identity is exportable (software JCA keys),
     * or null for the legacy Keystore-only identity (hardware: the scalar never leaves).
     * The result is key material: never log it, never cache it outside this call.
     */
    fun exportScalar(identity: Identity): ByteArray? =
        runCatching { fixed32((identity.privateKey as ECPrivateKey).s) }.getOrNull()

    /** True when the identity currently loaded on this device can be backed up. */
    fun isExportable(identity: Identity): Boolean = exportScalar(identity) != null

    /**
     * Installs a restored identity (scalar + public key from a decrypted backup payload).
     * Verifies the pair is self-consistent before touching storage; the app must restart
     * to activate it (docs/BACKUP.md §3).
     * @throws IllegalArgumentException if the payload is inconsistent.
     */
    fun installRestored(context: Context, scalar: ByteArray, publicKeyWire: ByteArray) {
        require(scalar.size == 32 && publicKeyWire.size == 65 && publicKeyWire[0] == 0x04.toByte()) { "bad backup payload" }
        val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), Crypto.p256Params))
        val pub = Crypto.publicKeyFromWire(publicKeyWire)
        val identity = Identity(priv, pub)
        val challenge = Crypto.randomBytes(32)
        require(Crypto.verify(pub, challenge, identity.sign(challenge))) { "restored key does not match the embedded public key" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        writeSeeded(prefs, scalar, publicKeyWire)
        // Log at most the 4-hex suffix — full ids are never logged.
        EventLog.global.i("identity", "identity restored from backup (node ${NodeId.fromPublicKey(publicKeyWire).short}); restart to activate")
    }

    // ---- legacy paths --------------------------------------------------------------------

    private fun legacyKeystoreIdentity(): Identity? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(LEGACY_KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
        entry?.let { Identity(it.privateKey, it.certificate.publicKey) }
    } catch (e: Exception) {
        null
    }

    private fun loadLegacySoftware(prefs: SharedPreferences): Identity {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(b64decode(prefs.getString(K_PKCS8, "")!!)))
        val pub = kf.generatePublic(X509EncodedKeySpec(b64decode(prefs.getString(K_X509, "")!!)))
        return Identity(priv, pub)
    }

    // ---- misc ---------------------------------------------------------------------------

    fun displayName(context: Context): Flow<String?> = context.settings.data.map { it[NAME] }

    suspend fun setDisplayName(context: Context, name: String) {
        context.settings.edit { it[NAME] = name }
    }

    /** Battery profile persists across restarts (DataStore). */
    fun powerProfile(context: Context): Flow<Int?> = context.settings.data.map { it[POWER_PROFILE] }

    suspend fun setPowerProfile(context: Context, code: Int) {
        context.settings.edit { it[POWER_PROFILE] = code }
    }

    private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    /** Big-endian, exactly 32 bytes (strips the sign byte / left-pads). */
    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
