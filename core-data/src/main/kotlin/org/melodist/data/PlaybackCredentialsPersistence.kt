package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.api.PlaybackCredentialsManager

object PlaybackCredentialsPersistence {
    private const val PREFS_NAME = "melodist_playback_creds"
    private const val KEY_CREDS_JSON = "playback_creds_json"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_CREDS_JSON, null)
            if (!jsonStr.isNullOrBlank()) {
                PlaybackCredentialsManager.fromJson(jsonStr)
            }
        } catch (_: Exception) {
            PlaybackCredentialsManager.clearCredentials()
        }

        scope.launch {
            PlaybackCredentialsManager.credentialsFlow.collect { creds ->
                try {
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (creds != null) {
                        val json = PlaybackCredentialsManager.toJson()
                        if (json != null) {
                            prefs.edit().putString(KEY_CREDS_JSON, json).apply()
                        }
                    } else {
                        prefs.edit().remove(KEY_CREDS_JSON).apply()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
