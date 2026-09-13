package org.melodist.data

import android.content.Context

@Deprecated("Use SearchKeywordHistoryManager instead")
object SearchPlaybackHistoryManager {
    fun init(context: Context) {
        SearchKeywordHistoryManager.init(context)
    }
}
