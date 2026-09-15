package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.api.UserSession

object UserSessionManager {
    private const val PREFS_NAME = "melodist_user_session"
    private const val KEY_PROFILE = "user_profile_json"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isObserving = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        PlaybackCredentialsPersistence.init(appContext)
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_PROFILE, null)
            if (!jsonStr.isNullOrBlank()) {
                UserSession.fromJson(jsonStr)
            }
        } catch (_: Exception) {
            UserSession.clear()
        }

        if (!isObserving) {
            isObserving = true
            scope.launch {
                UserSession.profileFlow.collect { profile ->
                    try {
                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        if (UserSession.isLoggedIn) {
                            prefs.edit().putString(KEY_PROFILE, UserSession.toJson()).commit()
                        } else if (profile.uin.isBlank() && profile.cookies.isEmpty()) {
                            prefs.edit().remove(KEY_PROFILE).commit()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun save(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = UserSession.toJson()
            prefs.edit().putString(KEY_PROFILE, jsonStr).apply()
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PROFILE).apply()
        } catch (_: Exception) {
        }
        UserSession.clear()
    }
}
