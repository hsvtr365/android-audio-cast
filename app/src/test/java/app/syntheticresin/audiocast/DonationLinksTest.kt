package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Test

class DonationLinksTest {
    @Test fun missingOrUnsafeDestinationCannotEnablePayment() {
        listOf("", " ", "http://example.com", "javascript:alert(1)", "intent://pay", "https:///pay", "https://user:pass@example.com/pay", "https://example.com:8080/pay")
            .forEach { assertNull(it,DonationLinks.validated(it)) }
    }
    @Test fun preservesHttpsCheckoutParameters() {
        val url="https://example.com/support?amount=2&currency=USD"
        assertEquals(url,DonationLinks.validated(" $url "))
    }
}
