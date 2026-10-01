package com.localgpt.app.util

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object NetworkUtils {

    /**
     * Shared OkHttpClient factory for the entire application.
     * Different use cases require different timeout configurations,
     * so this provides pre-configured clients for common scenarios.
     */
    object HttpClient {
        /** Short-timeout client for web search and quick API calls (3.5s timeout). */
        val searchClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
                .writeTimeout(3500, TimeUnit.MILLISECONDS)
                .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                .followRedirects(true)
                .build()
        }

        /** Long-timeout client for remote AI streaming (120s read timeout). */
        val streamingClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
    /**
     * True for RFC 1918 private LAN addresses. Note that 172.0.0.0–172.15.x.x
     * and 172.32.0.0–172.255.x.x are public ranges, so a bare "172." prefix
     * check would wrongly classify them as LAN addresses.
     */
    private fun isPrivateLanAddress(host: String): Boolean {
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.substringAfter("172.").substringBefore(".").toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /**
     * Attempts to resolve the active local IPv4 address (Wi-Fi, Ethernet, Hotspot, or LAN).
     * Returns null if no suitable LAN interface is found.
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<String>()

            for (nIf in interfaces) {
                if (nIf.isLoopback || !nIf.isUp) continue
                val name = nIf.name.lowercase()
                for (addr in nIf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        // Prioritize common local private subnets
                        if (isPrivateLanAddress(host)) {
                            // Wi-Fi or Ethernet interfaces take top priority
                            if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("ap")) {
                                return host
                            }
                            candidates.add(host)
                        }
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                return candidates.first()
            }
        } catch (_: Exception) {
        }
        return null
    }
}
