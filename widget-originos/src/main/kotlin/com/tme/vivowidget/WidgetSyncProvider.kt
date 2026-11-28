package com.tme.vivowidget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 接收 Melodist 主应用通过 ContentProvider.call() 发起的同步状态更新。
 * 相比 sendBroadcast，Binder 直连调用可在进程被系统冻结时立即唤醒并刷新小组件。
 */
class WidgetSyncProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val ctx = context ?: return null
        if (method == METHOD_SYNC_STATE && extras != null) {
            WidgetProvider.syncFromBundle(ctx, extras)
            return Bundle.EMPTY
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val METHOD_SYNC_STATE = "sync_state"
    }
}
