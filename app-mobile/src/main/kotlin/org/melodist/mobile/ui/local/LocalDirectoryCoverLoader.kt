package org.melodist.mobile.ui.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LocalDirectoryCoverLoader {
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private val negativeCache = ConcurrentHashMap<String, Boolean>()

    fun getCached(path: String): Bitmap? {
        val bmp = memoryCache[path]
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun isCached(path: String): Boolean = memoryCache.containsKey(path) || negativeCache.containsKey(path)

    suspend fun loadEmbeddedCover(path: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val existing = memoryCache[path]
            if (existing != null && !existing.isRecycled) {
                return@withContext existing
            }
            if (negativeCache.containsKey(path)) {
                return@withContext null
            }

            val file = File(path)
            if (!file.exists() || !file.isFile || file.length() < 1024L) {
                negativeCache[path] = true
                return@withContext null
            }

            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val picture = retriever.embeddedPicture
                if (picture != null && picture.isNotEmpty()) {
                    val options =
                        BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                    BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
                    val targetSize = 500
                    var sampleSize = 1
                    while (options.outWidth / (sampleSize * 2) >= targetSize &&
                        options.outHeight / (sampleSize * 2) >= targetSize
                    ) {
                        sampleSize *= 2
                    }
                    val decodeOptions =
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size, decodeOptions)
                    if (bitmap != null) {
                        memoryCache[path] = bitmap
                        bitmap
                    } else {
                        negativeCache[path] = true
                        null
                    }
                } else {
                    negativeCache[path] = true
                    null
                }
            } catch (_: Exception) {
                negativeCache[path] = true
                null
            } finally {
                try {
                    retriever?.release()
                } catch (_: Exception) {
                }
            }
        }

    fun clear() {
        memoryCache.values.forEach { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        memoryCache.clear()
        negativeCache.clear()
    }
}
