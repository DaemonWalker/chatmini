package com.chatmini.app.data

data class AppSettings(
    val urls: List<UrlItem> = emptyList(),
    val autoStartProxy: Boolean = false,
    val lastUrlId: String? = null,
    val currentNode: String? = null
)
