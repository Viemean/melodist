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

    suspend fun checkUpdateDaily(
        context: android.content.Context,
        force: Boolean = false,
    ): UpdateResult? =
        CoreUpdateChecker.checkUpdateDaily(
            context = context,
            currentVersion = BuildConfig.VERSION_NAME,
            targetKeyword = "tv",
            force = force,
        )

    fun isNewerVersion(
        remoteTag: String,
        localVersion: String,
    ): Boolean = CoreUpdateChecker.isNewerVersion(remoteTag, localVersion)
}
