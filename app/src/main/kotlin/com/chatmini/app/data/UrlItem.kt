package com.chatmini.app.data

data class UrlItem(
    val id: String,
    val name: String,
    val url: String,
    val order: Int = 0,
    val iconPath: String? = null
)
