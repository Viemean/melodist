package org.melodist.tv.update

import org.melodist.tv.BuildConfig
import org.melodist.data.update.UpdateChecker as CoreUpdateChecker
import org.melodist.data.update.UpdateResult as CoreUpdateResult

typealias UpdateResult = CoreUpdateResult

object UpdateChecker {
    const val REPO_WEB_URL = CoreUpdateChecker.REPO_WEB_URL

    suspend fun checkUpdate(): UpdateResult =
        CoreUpdateChecker.checkUpdate(
            currentVersion = BuildConfig.VERSION_NAME,
            targetKeyword = "tv",
        )

    fun isNewerVersion(
        remoteTag: String,
        localVersion: String,
    ): Boolean = CoreUpdateChecker.isNewerVersion(remoteTag, localVersion)
}
