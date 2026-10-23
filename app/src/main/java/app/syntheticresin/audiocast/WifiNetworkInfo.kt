package app.syntheticresin.audiocast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

enum class WifiBand(val label: String) {
    TWO_POINT_FOUR("2.4 GHz"), FIVE("5 GHz"), SIX("6 GHz"), SIXTY("60 GHz");

    companion object {
        fun fromFrequency(mhz: Int): WifiBand? = when (mhz) {
            in 2400..2500 -> TWO_POINT_FOUR
            in 4900..5900 -> FIVE
            in 5925..7125 -> SIX
            in 58320..70200 -> SIXTY
            else -> null
        }
    }
}

object WifiNetworkInfo {
    fun needsWarning(connected: Boolean, band: WifiBand?): Boolean =
        !connected || (band != WifiBand.TWO_POINT_FOUR && band != WifiBand.FIVE)

    @Suppress("DEPRECATION")
    fun band(context: Context): WifiBand? {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val wifi = manager.allNetworks.asSequence().mapNotNull { manager.getNetworkCapabilities(it) }
                .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: return null
            val frequency = (wifi.transportInfo as? WifiInfo)?.frequency?.takeIf { it > 0 }
                ?: context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.frequency
                ?: return null
            WifiBand.fromFrequency(frequency)
        } catch (_: SecurityException) { null }
    }
}
