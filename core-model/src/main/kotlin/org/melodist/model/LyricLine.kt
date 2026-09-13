package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
data class WordSpan(
    val word: String,
    val offsetMs: Long,
    val durationMs: Long,
)

@Serializable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val transText: String = "",
    val words: List<WordSpan> = emptyList(),
) {
    val hasTranslation: Boolean
        get() = transText.isNotBlank()

    val isWordSynced: Boolean
        get() = words.isNotEmpty()
}
