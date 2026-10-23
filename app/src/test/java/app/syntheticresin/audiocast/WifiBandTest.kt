package app.syntheticresin.audiocast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBandTest {
    @Test fun recognizesSupportedWifiBands() {
        assertEquals(WifiBand.TWO_POINT_FOUR, WifiBand.fromFrequency(2412))
        assertEquals(WifiBand.TWO_POINT_FOUR, WifiBand.fromFrequency(2484))
        assertEquals(WifiBand.FIVE, WifiBand.fromFrequency(5180))
        assertEquals(WifiBand.FIVE, WifiBand.fromFrequency(5825))
        assertEquals(WifiBand.SIX, WifiBand.fromFrequency(5935))
        assertEquals(WifiBand.SIX, WifiBand.fromFrequency(7115))
        assertEquals(WifiBand.SIXTY, WifiBand.fromFrequency(60480))
    }

    @Test fun unavailableOrUnrecognizedFrequencyDoesNotInventAWifiBand() {
        for (frequency in listOf(-1, 0, 2501, 4800, 5910, 7200)) {
            assertNull(WifiBand.fromFrequency(frequency))
        }
    }

    @Test fun warnsOnlyOutsideTwoPointFourAndFiveOrWithoutWifi() {
        assertFalse(WifiNetworkInfo.needsWarning(true, WifiBand.TWO_POINT_FOUR))
        assertFalse(WifiNetworkInfo.needsWarning(true, WifiBand.FIVE))
        assertTrue(WifiNetworkInfo.needsWarning(true, WifiBand.SIX))
        assertTrue(WifiNetworkInfo.needsWarning(true, null))
        assertTrue(WifiNetworkInfo.needsWarning(false, WifiBand.FIVE))
    }
}
