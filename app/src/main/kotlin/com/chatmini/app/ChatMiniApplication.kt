package com.chatmini.app

import android.app.Application
import com.chatmini.app.data.SettingsRepository

class ChatMiniApplication : Application() {

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
