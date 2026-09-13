package org.melodist.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SearchKeywordHistoryManager {
    private const val PREF_NAME = "melodist_search_keyword_history"
    private const val KEY_KEYWORDS = "search_keywords"
    private const val MAX_HISTORY_ITEMS = 30

    private var prefs: SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getKeywords(): List<String> {
        val raw = prefs?.getString(KEY_KEYWORDS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordKeyword(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        val current = getKeywords().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        if (current.size > MAX_HISTORY_ITEMS) {
            current.removeAt(current.size - 1)
        }

        try {
            val encoded = json.encodeToString(current)
            prefs?.edit()?.putString(KEY_KEYWORDS, encoded)?.apply()
        } catch (_: Exception) {
        }
    }

    fun clearKeywords() {
        prefs?.edit()?.remove(KEY_KEYWORDS)?.apply()
    }
}
