package app.syntheticresin.audiocast

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SupportBillingTest {
    @Test fun rejectsTamperedOrInvalidPurchaseSignatures() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val data="{\"purchaseState\":0,\"productId\":\"support_coffee\"}"
        val signature=Signature.getInstance("SHA1withRSA").run {
            initSign(pair.private);update(data.toByteArray());Base64.getEncoder().encodeToString(sign())
        }
        val key=Base64.getEncoder().encodeToString(pair.public.encoded)
        assertTrue(SupportBilling.verify(data,signature,key))
        assertFalse(SupportBilling.verify(data+"tampered",signature,key))
        assertFalse(SupportBilling.verify(data,"invalid",key))
        assertFalse(SupportBilling.verify(data,signature,"invalid"))
    }
}
