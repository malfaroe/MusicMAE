package com.mae.musicmae

import android.content.Context

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("musicmae", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()
}
