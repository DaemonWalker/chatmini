package com.chatmini.app.data

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object ProxyConfig {
    // Browser uses SOCKS5 proxy because GeckoView does not support HTTP proxy configuration.
    const val SOCKS_PROXY_PORT = 1080
    const val CONTROL_API_PORT = 9090
    const val CONTROL_API_SECRET = "chatmini"
    const val CONFIG_FILE_NAME = "config.yaml"
    const val MIHOMO_BINARY_NAME = "mihomo"
    const val MIHOMO_LIB_NAME = "libmihomo.so"
    const val WORK_DIR_NAME = "mihomo"

    /**
     * Creates an OkHttpClient that routes traffic through the local mihomo SOCKS5 proxy.
     * Use this for any out-of-browser HTTP requests (e.g. favicon download, subscription import)
     * so they obey the same proxy rules as GeckoView.
     */
    fun createProxyOkHttpClient(
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10
    ): OkHttpClient {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", SOCKS_PROXY_PORT)
        )
        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }
}
