package app.passage.core.security.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// android.net.Uri is not available in JVM unit tests, so the validation is tested through its helper
class TotpGeneratorTest {

    @Test
    fun acceptsDefaultsAndBounds() {
        assertTrue(TotpGenerator.areParamsValid(digits = 6, periodSeconds = 30))
        assertTrue(TotpGenerator.areParamsValid(digits = 8, periodSeconds = 1))
        assertTrue(TotpGenerator.areParamsValid(digits = 7, periodSeconds = 300))
    }

    @Test
    fun rejectsZeroOrNegativePeriod() {
        assertFalse(TotpGenerator.areParamsValid(digits = 6, periodSeconds = 0))
        assertFalse(TotpGenerator.areParamsValid(digits = 6, periodSeconds = -30))
    }

    @Test
    fun rejectsPeriodAboveLimit() {
        assertFalse(TotpGenerator.areParamsValid(digits = 6, periodSeconds = 301))
    }

    @Test
    fun rejectsDigitsOutOfRange() {
        assertFalse(TotpGenerator.areParamsValid(digits = 5, periodSeconds = 30))
        assertFalse(TotpGenerator.areParamsValid(digits = 9, periodSeconds = 30))
        assertFalse(TotpGenerator.areParamsValid(digits = 0, periodSeconds = 30))
        assertFalse(TotpGenerator.areParamsValid(digits = Int.MAX_VALUE, periodSeconds = 30))
    }

    @Test
    fun generatesRfc6238Vector() {
        // RFC 6238 SHA1 test vector, T = 59s, truncated to 6 digits
        val config = TotpConfig(secretBytes = "12345678901234567890".toByteArray())
        assertEquals("287082", TotpGenerator.generateCode(config, timestampMs = 59_000L))
    }
}
