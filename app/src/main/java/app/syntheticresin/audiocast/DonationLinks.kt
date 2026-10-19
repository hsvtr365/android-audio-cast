package app.syntheticresin.audiocast

import java.net.URI

object DonationLinks {
    fun validated(value:String):String? {
        val url=value.trim()
        val uri=runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme.equals("https",ignoreCase=true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo==null && uri.port in listOf(-1,443) }
    }
}
