package com.chatmini.app.data

import android.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder

object SubscriptionParser {

    data class ProxyNode(
        val name: String,
        val type: String,
        val config: Map<String, Any?>
    )

    fun parseBase64Subscription(content: String): List<ProxyNode> {
        val decoded = try {
            String(Base64.decode(content.trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            // Maybe it's not base64, try as plain text
            content
        }

        return decoded.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseProxyUrl(it) }
    }

    private fun parseProxyUrl(url: String): ProxyNode? {
        return when {
            url.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(url)
            url.startsWith("vmess://", ignoreCase = true) -> parseVmess(url)
            url.startsWith("trojan://", ignoreCase = true) -> parseTrojan(url)
            url.startsWith("vless://", ignoreCase = true) -> parseVless(url)
            url.startsWith("anytls://", ignoreCase = true) -> parseAnytls(url)
            else -> null
        }
    }

    private fun parseShadowsocks(url: String): ProxyNode? {
        return try {
            // ss://BASE64(method:password)@server:port#name
            val withoutPrefix = url.removePrefix("ss://")
            val hashIndex = withoutPrefix.indexOf('#')
            val name = if (hashIndex >= 0) {
                URLDecoder.decode(withoutPrefix.substring(hashIndex + 1), "UTF-8")
            } else {
                "SS Node"
            }
            val mainPart = if (hashIndex >= 0) withoutPrefix.substring(0, hashIndex) else withoutPrefix

            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex < 0) return null

            val userInfoEncoded = mainPart.substring(0, atIndex)
            val serverPart = mainPart.substring(atIndex + 1)

            val userInfo = String(Base64.decode(userInfoEncoded, Base64.DEFAULT), Charsets.UTF_8)
            val colonIndex = userInfo.indexOf(':')
            if (colonIndex < 0) return null

            val method = userInfo.substring(0, colonIndex)
            val password = userInfo.substring(colonIndex + 1)

            val portIndex = serverPart.lastIndexOf(':')
            if (portIndex < 0) return null
            val server = serverPart.substring(0, portIndex)
            val port = serverPart.substring(portIndex + 1).toIntOrNull() ?: return null

            ProxyNode(
                name = name,
                type = "ss",
                config = mapOf(
                    "name" to name,
                    "type" to "ss",
                    "server" to server,
                    "port" to port,
                    "cipher" to method,
                    "password" to password
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(url: String): ProxyNode? {
        return try {
            val encoded = url.removePrefix("vmess://")
            val json = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            val obj = org.json.JSONObject(json)

            val name = obj.optString("ps", "VMess Node")
            ProxyNode(
                name = name,
                type = "vmess",
                config = mapOf(
                    "name" to name,
                    "type" to "vmess",
                    "server" to obj.getString("add"),
                    "port" to obj.getInt("port"),
                    "uuid" to obj.getString("id"),
                    "alterId" to obj.optInt("aid", 0),
                    "cipher" to obj.optString("scy", "auto"),
                    "tls" to (obj.optString("tls", "") == "tls"),
                    "network" to obj.optString("net", "tcp"),
                    "ws-opts" to if (obj.optString("net", "") == "ws") {
                        mapOf("path" to obj.optString("path", "/"))
                    } else null
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrojan(url: String): ProxyNode? {
        return try {
            val withoutPrefix = url.removePrefix("trojan://")
            val hashIndex = withoutPrefix.indexOf('#')
            val name = if (hashIndex >= 0) {
                URLDecoder.decode(withoutPrefix.substring(hashIndex + 1), "UTF-8")
            } else {
                "Trojan Node"
            }
            val mainPart = if (hashIndex >= 0) withoutPrefix.substring(0, hashIndex) else withoutPrefix

            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex < 0) return null

            val password = URLDecoder.decode(mainPart.substring(0, atIndex), "UTF-8")
            val serverPart = mainPart.substring(atIndex + 1)

            val portIndex = serverPart.lastIndexOf(':')
            if (portIndex < 0) return null
            val server = serverPart.substring(0, portIndex)
            val port = serverPart.substring(portIndex + 1).toIntOrNull() ?: return null

            ProxyNode(
                name = name,
                type = "trojan",
                config = mapOf(
                    "name" to name,
                    "type" to "trojan",
                    "server" to server,
                    "port" to port,
                    "password" to password
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVless(url: String): ProxyNode? {
        return try {
            val withoutPrefix = url.removePrefix("vless://")
            val hashIndex = withoutPrefix.indexOf('#')
            val name = if (hashIndex >= 0) {
                URLDecoder.decode(withoutPrefix.substring(hashIndex + 1), "UTF-8")
            } else {
                "VLESS Node"
            }
            val mainPart = if (hashIndex >= 0) withoutPrefix.substring(0, hashIndex) else withoutPrefix

            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex < 0) return null

            val uuid = mainPart.substring(0, atIndex)
            val serverPart = mainPart.substring(atIndex + 1)

            val portIndex = serverPart.lastIndexOf(':')
            if (portIndex < 0) return null
            val server = serverPart.substring(0, portIndex)
            val port = serverPart.substring(portIndex + 1).toIntOrNull() ?: return null

            ProxyNode(
                name = name,
                type = "vless",
                config = mapOf(
                    "name" to name,
                    "type" to "vless",
                    "server" to server,
                    "port" to port,
                    "uuid" to uuid,
                    "tls" to false,
                    "network" to "tcp"
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAnytls(url: String): ProxyNode? {
        return try {
            val withoutPrefix = url.removePrefix("anytls://")
            val hashIndex = withoutPrefix.indexOf('#')
            val name = if (hashIndex >= 0) {
                URLDecoder.decode(withoutPrefix.substring(hashIndex + 1), "UTF-8")
            } else {
                "AnyTLS Node"
            }
            val mainPart = if (hashIndex >= 0) withoutPrefix.substring(0, hashIndex) else withoutPrefix

            val atIndex = mainPart.lastIndexOf('@')
            if (atIndex < 0) return null

            val password = URLDecoder.decode(mainPart.substring(0, atIndex), "UTF-8")
            val serverAndQuery = mainPart.substring(atIndex + 1)

            val queryIndex = serverAndQuery.indexOf('?')
            val serverPart = if (queryIndex >= 0) serverAndQuery.substring(0, queryIndex) else serverAndQuery
            val queryString = if (queryIndex >= 0) serverAndQuery.substring(queryIndex + 1) else ""

            val portIndex = serverPart.lastIndexOf(':')
            if (portIndex < 0) return null
            val server = serverPart.substring(0, portIndex)
            val port = serverPart.substring(portIndex + 1).toIntOrNull() ?: return null

            val params = parseQueryString(queryString)

            val config = mutableMapOf<String, Any?>(
                "name" to name,
                "type" to "anytls",
                "server" to server,
                "port" to port,
                "password" to password
            )

            params["sni"]?.let { config["sni"] = it }
            params["host"]?.let { config["sni"] = it }
            params["peer"]?.let { config["sni"] = it }

            val tls = params["security"].equals("tls", ignoreCase = true) ||
                    params["tls"].equals("true", ignoreCase = true) ||
                    params["tls"].equals("1", ignoreCase = true)
            config["tls"] = tls

            val skipVerify = params["allowinsecure"].equals("true", ignoreCase = true) ||
                    params["allowinsecure"].equals("1", ignoreCase = true) ||
                    params["insecure"].equals("true", ignoreCase = true) ||
                    params["insecure"].equals("1", ignoreCase = true)
            if (skipVerify) {
                config["skip-cert-verify"] = true
            }

            params["fingerprint"]?.let { config["fingerprint"] = it }

            params["idlesessioncheckinterval"]?.toIntOrNull()?.let {
                config["idle-session-check-interval"] = it
            }
            params["idlesessiontimeout"]?.toIntOrNull()?.let {
                config["idle-session-timeout"] = it
            }
            params["minidlesession"]?.toIntOrNull()?.let {
                config["min-idle-session"] = it
            }

            ProxyNode(name = name, type = "anytls", config = config)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        return query.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val eqIndex = it.indexOf('=')
                if (eqIndex < 0) {
                    URLDecoder.decode(it, "UTF-8").lowercase() to ""
                } else {
                    URLDecoder.decode(it.substring(0, eqIndex), "UTF-8").lowercase() to
                            URLDecoder.decode(it.substring(eqIndex + 1), "UTF-8")
                }
            }
            .toMap()
    }
}
