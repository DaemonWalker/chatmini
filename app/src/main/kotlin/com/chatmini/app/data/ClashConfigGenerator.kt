package com.chatmini.app.data

object ClashConfigGenerator {

    fun generateConfig(nodes: List<SubscriptionParser.ProxyNode>): String {
        val sb = StringBuilder()

        // GeckoView only supports SOCKS proxy, so we expose a SOCKS5 port.
        sb.appendLine("port: 0")
        sb.appendLine("socks-port: ${ProxyConfig.SOCKS_PROXY_PORT}")
        sb.appendLine("mixed-port: 0")
        sb.appendLine("allow-lan: false")
        sb.appendLine("bind-address: \"127.0.0.1\"")
        sb.appendLine("mode: rule")
        sb.appendLine("log-level: info")
        sb.appendLine("external-controller: \"127.0.0.1:${ProxyConfig.CONTROL_API_PORT}\"")
        sb.appendLine("secret: \"${ProxyConfig.CONTROL_API_SECRET}\"")
        sb.appendLine("ipv6: false")
        sb.appendLine()

        // DNS: required for remote DNS resolution over SOCKS5 to work reliably.
        appendDnsConfig(sb)
        sb.appendLine()

        // Proxies
        sb.appendLine("proxies:")
        nodes.forEach { node ->
            sb.appendLine("  - name: \"${escapeYaml(node.name)}\"")
            appendMap(sb, node.config, indent = 4)
        }
        sb.appendLine()

        // Proxy groups
        val proxyNames = nodes.map { "\"${escapeYaml(it.name)}\"" }
        sb.appendLine("proxy-groups:")
        sb.appendLine("  - name: \"Proxy\"")
        sb.appendLine("    type: select")
        sb.appendLine("    proxies: [${proxyNames.joinToString(", ")}]")
        sb.appendLine()

        // Rules
        sb.appendLine("rules:")
        sb.appendLine("  - DOMAIN-SUFFIX,local,DIRECT")
        sb.appendLine("  - IP-CIDR,127.0.0.0/8,DIRECT")
        sb.appendLine("  - IP-CIDR,172.16.0.0/12,DIRECT")
        sb.appendLine("  - IP-CIDR,192.168.0.0/16,DIRECT")
        sb.appendLine("  - IP-CIDR,10.0.0.0/8,DIRECT")
        sb.appendLine("  - MATCH,Proxy")

        return sb.toString()
    }

    private fun appendDnsConfig(sb: StringBuilder) {
        sb.appendLine("dns:")
        sb.appendLine("  enable: true")
        sb.appendLine("  ipv6: false")
        sb.appendLine("  listen: \"127.0.0.1:11053\"")
        sb.appendLine("  enhanced-mode: fake-ip")
        sb.appendLine("  fake-ip-range: \"198.18.0.1/16\"")
        sb.appendLine("  default-nameserver:")
        sb.appendLine("    - 223.5.5.5")
        sb.appendLine("    - 119.29.29.29")
        sb.appendLine("  nameserver:")
        sb.appendLine("    - https://doh.pub/dns-query")
        sb.appendLine("    - https://dns.alidns.com/dns-query")
        sb.appendLine("  fallback:")
        sb.appendLine("    - https://1.1.1.1/dns-query")
        sb.appendLine("    - https://8.8.8.8/dns-query")
        // Disable GeoIP-based fallback filtering so mihomo does not try to
        // download the MMDB database on startup (often blocked in some regions).
        sb.appendLine("  fallback-filter:")
        sb.appendLine("    geoip: false")
    }

    private fun appendMap(sb: StringBuilder, map: Map<String, Any?>, indent: Int) {
        val prefix = " ".repeat(indent)
        map.forEach { (key, value) ->
            if (key == "name") return@forEach
            when (value) {
                is Map<*, *> -> {
                    sb.appendLine("$prefix$key:")
                    @Suppress("UNCHECKED_CAST")
                    appendMap(sb, value as Map<String, Any?>, indent + 2)
                }
                is List<*> -> {
                    sb.appendLine("$prefix$key: [${value.filterNotNull().joinToString(", ") { formatYamlValue(it) }}]")
                }
                else -> {
                    sb.appendLine("$prefix$key: ${formatYamlValue(value)}")
                }
            }
        }
    }

    private fun formatYamlValue(value: Any?): String {
        return when (value) {
            is String -> "\"${escapeYaml(value)}\""
            is Boolean -> value.toString()
            is Number -> value.toString()
            else -> "\"${escapeYaml(value?.toString() ?: "")}\""
        }
    }

    private fun escapeYaml(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun parseNodeNames(configYaml: String): List<String> {
        return configYaml.lines()
            .filter { it.trim().startsWith("- name:") }
            .map { it.substringAfter("- name:").trim().trim('"') }
    }
}
