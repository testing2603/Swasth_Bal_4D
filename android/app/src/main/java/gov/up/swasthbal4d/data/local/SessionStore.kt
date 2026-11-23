package gov.up.swasthbal4d.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import gov.up.swasthbal4d.data.net.AppJson
import gov.up.swasthbal4d.data.net.UserDto
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore by preferencesDataStore("session")

/**
 * Login state. The token is kept for offline use; the PIN is checked locally against a PBKDF2 hash
 * so a worker can unlock the app with no network (design 3.2: PIN / fingerprint offline, OTP every 30 days).
 */
class SessionStore(private val context: Context) {
    private val kToken = stringPreferencesKey("token")
    private val kUser = stringPreferencesKey("user")
    private val kPin = stringPreferencesKey("pin_hash")

    @Volatile var token: String? = null
        private set
    @Volatile var user: UserDto? = null
        private set

    suspend fun load() {
        val p = context.dataStore.data.first()
        token = p[kToken]
        user = p[kUser]?.let { runCatching { AppJson.decodeFromString(UserDto.serializer(), it) }.getOrNull() }
    }

    suspend fun hasPin(): Boolean = context.dataStore.data.first()[kPin] != null

    suspend fun save(token: String, user: UserDto) {
        this.token = token
        this.user = user
        context.dataStore.edit {
            it[kToken] = token
            it[kUser] = AppJson.encodeToString(UserDto.serializer(), user)
        }
    }

    suspend fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        context.dataStore.edit { it[kPin] = salt.hex() + "$" + pbkdf2(pin, salt).hex() }
    }

    suspend fun checkPin(pin: String): Boolean {
        val stored = context.dataStore.data.first()[kPin] ?: return false
        val (salt, hash) = stored.split("$")
        return MessageDigest.isEqual(pbkdf2(pin, salt.unhex()), hash.unhex())
    }

    suspend fun logout() {
        token = null
        user = null
        context.dataStore.edit { it.clear() }
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)).encoded

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
