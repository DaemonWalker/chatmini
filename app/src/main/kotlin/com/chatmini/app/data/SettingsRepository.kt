package com.chatmini.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun getSettingsSnapshot(): AppSettings = loadSettings()

    fun saveUrls(urls: List<UrlItem>) {
        prefs.edit().putString(KEY_URLS, urlsToJson(urls)).apply()
        _settings.value = loadSettings()
    }

    fun saveAutoStartProxy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        _settings.value = loadSettings()
    }

    fun saveLastUrlId(id: String?) {
        prefs.edit().putString(KEY_LAST_URL, id).apply()
        _settings.value = loadSettings()
    }

    fun saveCurrentNode(node: String?) {
        prefs.edit().putString(KEY_CURRENT_NODE, node).apply()
        _settings.value = loadSettings()
    }

    fun addUrl(name: String, url: String) {
        val urls = loadSettings().urls.toMutableList()
        val newItem = UrlItem(
            id = System.currentTimeMillis().toString(),
            name = name,
            url = url,
            order = urls.size
        )
        urls.add(newItem)
        saveUrls(urls)
    }

    fun updateUrl(id: String, name: String, url: String) {
        val urls = loadSettings().urls.map {
            if (it.id == id) it.copy(name = name, url = url) else it
        }
        saveUrls(urls)
    }

    fun updateUrlIcon(id: String, iconPath: String?) {
        val urls = loadSettings().urls.map {
            if (it.id == id) it.copy(iconPath = iconPath) else it
        }
        saveUrls(urls)
    }

    fun deleteUrl(id: String) {
        val urls = loadSettings().urls.filter { it.id != id }
            .mapIndexed { index, item -> item.copy(order = index) }
        saveUrls(urls)
    }

    private fun loadSettings(): AppSettings {
        return AppSettings(
            urls = jsonToUrls(prefs.getString(KEY_URLS, "[]") ?: "[]"),
            autoStartProxy = prefs.getBoolean(KEY_AUTO_START, false),
            lastUrlId = prefs.getString(KEY_LAST_URL, null),
            currentNode = prefs.getString(KEY_CURRENT_NODE, null)
        )
    }

    private fun urlsToJson(urls: List<UrlItem>): String {
        val array = JSONArray()
        urls.sortedBy { it.order }.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", item.url)
                put("order", item.order)
                put("iconPath", item.iconPath ?: JSONObject.NULL)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToUrls(json: String): List<UrlItem> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                UrlItem(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    order = obj.optInt("order", i),
                    iconPath = obj.optString("iconPath", "").takeIf { it.isNotBlank() && it != "null" }
                )
            }.sortedBy { it.order }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "chatmini_settings"
        private const val KEY_URLS = "urls"
        private const val KEY_AUTO_START = "auto_start_proxy"
        private const val KEY_LAST_URL = "last_url_id"
        private const val KEY_CURRENT_NODE = "current_node"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
