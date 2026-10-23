package app.syntheticresin.audiocast

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class SupportBilling(private val activity:Activity, private val changed:()->Unit) {
    companion object {
        val ids=listOf("support_coffee","support_five_coffees","support_coffee_cake")
        // Play Console licensing PUBLIC key, not a credential.
        private const val key="MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr2cWXTTi0iYGdP87D0OX/LkGspIDS75x4k39FxgVcGymTG8ObWllMazv+lP/4d0h7zi4LogBTezonUGeHToahW/9hfa1SOopwqLkLvXEriHFp4xFm6iJCHY9C3kPDfZGxxfEgOtutwUZfaDzoMCkhtFFFQj8aIV9yLIAU9wTgu94+TYWAlzvmYsGv+tUITI8l9HWekddEhDfxYK3EOffLs1BSl4s9m+mKt1XtISBC2tjEXoJAgjm49PWzDjuVkYO7/WdSQHGxIXhgZ6y1f71JKzXN2jxJZKP+gEFv61TcunlqYT5GtuseFbhiR3Ig5NfOWxvYV86Rj+xOoGbjAbKBwIDAQAB"
        fun verify(data:String,signature:String,publicKey:String=key):Boolean=runCatching {
            val rsa=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            Signature.getInstance("SHA1withRSA").run { initVerify(rsa);update(data.toByteArray(Charsets.UTF_8));verify(Base64.getDecoder().decode(signature)) }
        }.getOrDefault(false)
    }
    private val main=Handler(Looper.getMainLooper())
    private val prefs=activity.getSharedPreferences("support",0)
    var owned:Set<String> = prefs.getStringSet("owned",emptySet())!!.toSet(); private set
    var products=emptyMap<String,ProductDetails>(); private set
    var message=R.string.billing_loading; private set
    private var connecting=false
    private var closed=false
    private val acknowledging=mutableSetOf<String>()
    private fun notifyUi() { if(!closed) main.post { if(!closed) changed() } }
    private val client=BillingClient.newBuilder(activity.applicationContext)
        .setListener { result,purchases ->
            when(result.responseCode) {
                BillingClient.BillingResponseCode.OK -> purchases?.let { process(it,false) }
                BillingClient.BillingResponseCode.USER_CANCELED -> { message=R.string.billing_cancelled;notifyUi() }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                else -> { message=R.string.billing_unavailable;notifyUi() }
            }
        }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection().build()
    fun refresh() {
        if(closed||connecting)return
        if(client.isReady) { query();return }
        connecting=true
        client.startConnection(object:BillingClientStateListener {
            override fun onBillingSetupFinished(result:BillingResult) {
                connecting=false
                if(closed)return
                if(result.responseCode==BillingClient.BillingResponseCode.OK) query()
                else { message=R.string.billing_unavailable;notifyUi() }
            }
            override fun onBillingServiceDisconnected() { message=R.string.billing_unavailable;notifyUi() }
        })
    }
    private fun query() {
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()) { result,details ->
            if(!closed) {
                products=if(result.responseCode==BillingClient.BillingResponseCode.OK) details.productDetailsList.associateBy { it.productId } else emptyMap()
                message=if(products.isEmpty()) R.string.billing_unavailable else R.string.billing_description
                notifyUi()
            }
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result,purchases ->
            if(!closed&&result.responseCode==BillingClient.BillingResponseCode.OK) process(purchases,true)
        }
    }
    private fun process(purchases:List<Purchase>,replace:Boolean) {
        if(closed)return
        val valid=purchases.filter { verify(it.originalJson,it.signature) }
        // shortcut: client signature verification; use a backend for authoritative refund/revocation checks at scale.
        val current=valid.filter { it.purchaseState==Purchase.PurchaseState.PURCHASED }.flatMap { it.products }.filter { it in ids }.toSet()
        owned=if(replace) current else owned+current
        prefs.edit().putStringSet("owned",owned).apply()
        if(valid.any { it.purchaseState==Purchase.PurchaseState.PENDING }) message=R.string.billing_pending
        else if(current.isNotEmpty()) message=R.string.billing_thanks
        for(purchase in valid) {
            if(purchase.purchaseState!=Purchase.PurchaseState.PURCHASED || purchase.products.none { it in ids } || purchase.isAcknowledged || !acknowledging.add(purchase.purchaseToken))continue
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
                acknowledging.remove(purchase.purchaseToken)
                if(result.responseCode!=BillingClient.BillingResponseCode.OK) message=R.string.billing_ack_retry
                notifyUi()
            }
        }
        notifyUi()
    }
    fun price(id:String)=products[id]?.oneTimePurchaseOfferDetails?.formattedPrice
    fun buy(id:String) {
        val product=products[id] ?: return
        if(id in owned || !client.isReady) { refresh();return }
        val item=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        product.oneTimePurchaseOfferDetails?.offerToken?.let { item.setOfferToken(it) }
        val result=client.launchBillingFlow(activity,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build())
        if(result.responseCode!=BillingClient.BillingResponseCode.OK) { message=R.string.billing_unavailable;notifyUi() }
    }
    fun close() { closed=true;main.removeCallbacksAndMessages(null);client.endConnection() }
}
