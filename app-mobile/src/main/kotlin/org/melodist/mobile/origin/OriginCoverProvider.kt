package org.melodist.mobile.origin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException

/**
 * 面向系统控制中心与 OriginOS 原子组件的只读封面图片 ContentProvider。
 *
 * 通过安全 Base64 编码路径在进程间传递应用内部或外部存储中的封面文件，
 * 并对访问路径执行沙箱与存储根目录白名单校验。
 */
class OriginCoverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        if (!mode.contains("r")) {
            throw SecurityException("Read-only provider")
        }
        val ctx = context ?: throw FileNotFoundException("Context is null")
        val file =
            resolveFileFromUri(ctx, uri)
                ?: throw FileNotFoundException("Cannot resolve target file for URI: $uri")

        if (!file.exists() || !file.isFile || file.length() == 0L) {
            throw FileNotFoundException("File does not exist or empty: ${file.absolutePath}")
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/*"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

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
        private const val SCHEME = "content"
        private const val PATH_PREFIX = "cover"

        /**
         * 将本地封面文件路径编码并构造为外部可访问的 content:// URI。
         *
         * @param packageName 宿主应用包名（用于构造 authority）
         * @param file 目标封面本地文件实体
         * @return 编码后的封面资源 Content URI
         */
        fun buildContentUri(
            packageName: String,
            file: File,
        ): Uri {
            val authority = "$packageName.origin.cover"
            val absolutePath = file.absolutePath
            val encodedPath =
                Base64.encodeToString(
                    absolutePath.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".webp"
            return Uri
                .Builder()
                .scheme(SCHEME)
                .authority(authority)
                .appendPath(PATH_PREFIX)
                .appendPath("$encodedPath$ext")
                .build()
        }

        private fun resolveFileFromUri(
            context: Context,
            uri: Uri,
        ): File? {
            val segments = uri.pathSegments ?: return null
            if (segments.size >= 2 && segments[0] == PATH_PREFIX) {
                val encodedWithExt = segments[1]
                val encoded = encodedWithExt.substringBeforeLast('.')
                return try {
                    val decodedBytes =
                        Base64.decode(
                            encoded,
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                        )
                    val absolutePath = String(decodedBytes, Charsets.UTF_8)
                    val targetFile = File(absolutePath)
                    if (isAllowedPath(context, targetFile)) targetFile else null
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }

        private fun isAllowedPath(
            context: Context,
            file: File,
        ): Boolean {
            val canonical =
                try {
                    file.canonicalPath
                } catch (_: Exception) {
                    file.absolutePath
                }
            val allowedRoots =
                listOfNotNull(
                    context.cacheDir?.canonicalPath,
                    context.filesDir?.canonicalPath,
                    context.getExternalFilesDir(null)?.canonicalPath,
                    context.externalCacheDir?.canonicalPath,
                    "/storage/emulated/0",
                    "/sdcard",
                )
            return allowedRoots.any { root -> canonical.startsWith(root) }
        }
    }
}
