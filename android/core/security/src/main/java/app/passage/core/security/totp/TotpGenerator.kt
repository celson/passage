package app.passage.core.security.totp

import android.net.Uri
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

data class TotpConfig(
    val secretBytes: ByteArray,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val algorithm: String = "HmacSHA1",
    val issuer: String? = null,
    val account: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TotpConfig
        return secretBytes.contentEquals(other.secretBytes) &&
                digits == other.digits &&
                periodSeconds == other.periodSeconds &&
                algorithm == other.algorithm
    }

    override fun hashCode(): Int {
        var result = secretBytes.contentHashCode()
        result = 31 * result + digits
        result = 31 * result + periodSeconds
        result = 31 * result + algorithm.hashCode()
        return result
    }
}

object TotpGenerator {

    /**
     * Parses an otpauth URI in the format:
     * otpauth://totp/Issuer:Account?secret=JBSWY3DPEHPK3PXP&issuer=Issuer&period=30&digits=6&algorithm=SHA1
     */
    fun parseUri(rawUri: String): TotpConfig? {
        val trimmed = rawUri.trim()
        if (!trimmed.startsWith("otpauth://totp/", ignoreCase = true)) {
            // Also check if it's just raw secret key
            if (trimmed.length >= 8 && trimmed.all { it.isLetterOrDigit() }) {
                val secretBytes = Base32.decode(trimmed)
                if (secretBytes.isNotEmpty()) {
                    return TotpConfig(secretBytes = secretBytes)
                }
            }
            return null
        }

        return runCatching {
            val uri = Uri.parse(trimmed)
            val secretParam = uri.getQueryParameter("secret") ?: return null
            val secretBytes = Base32.decode(secretParam)
            if (secretBytes.isEmpty()) return null

            val digitsParam = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
            val periodParam = uri.getQueryParameter("period")?.toIntOrNull() ?: 30
            if (!areParamsValid(digitsParam, periodParam)) return null
            val algoParam = when (uri.getQueryParameter("algorithm")?.uppercase()) {
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> "HmacSHA1"
            }

            val path = uri.path?.removePrefix("/") ?: ""
            val (issuerFromPath, accountFromPath) = if (path.contains(":")) {
                val parts = path.split(":")
                parts[0] to parts.getOrNull(1)
            } else {
                null to path
            }

            val issuer = uri.getQueryParameter("issuer") ?: issuerFromPath

            TotpConfig(
                secretBytes = secretBytes,
                digits = digitsParam,
                periodSeconds = periodParam,
                algorithm = algoParam,
                issuer = issuer,
                account = accountFromPath
            )
        }.getOrNull()
    }

    /**
     * The URI comes from secret content, which a repository writer can forge: a zero period would divide
     * by zero when the secret is opened and unbounded digits make no sense for a code.
     */
    internal fun areParamsValid(digits: Int, periodSeconds: Int): Boolean =
        digits in MIN_DIGITS..MAX_DIGITS && periodSeconds in 1..MAX_PERIOD_SECONDS

    private const val MIN_DIGITS = 6
    private const val MAX_DIGITS = 8
    private const val MAX_PERIOD_SECONDS = 300

    /**
     * Generates a TOTP code for the specified config and timestamp.
     */
    fun generateCode(
        config: TotpConfig,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val currentStep = (timestampMs / 1000L) / config.periodSeconds

        val buffer = ByteBuffer.allocate(8).putLong(currentStep).array()
        val keySpec = SecretKeySpec(config.secretBytes, config.algorithm)

        val mac = Mac.getInstance(config.algorithm)
        mac.init(keySpec)
        val hash = mac.doFinal(buffer)

        // Dynamic truncation (RFC 4226)
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val modulus = 10.0.pow(config.digits).toInt()
        val code = binary % modulus

        return code.toString().padStart(config.digits, '0')
    }

    /**
     * Returns remaining seconds in the current time window.
     */
    fun getRemainingSeconds(
        periodSeconds: Int = 30,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        val seconds = (timestampMs / 1000L) % periodSeconds
        return (periodSeconds - seconds).toInt()
    }

    /**
     * Returns the elapsed progress of the current step from 0.0f to 1.0f.
     */
    fun getProgress(
        periodSeconds: Int = 30,
        timestampMs: Long = System.currentTimeMillis()
    ): Float {
        val rem = getRemainingSeconds(periodSeconds, timestampMs)
        return rem.toFloat() / periodSeconds.toFloat()
    }
}
