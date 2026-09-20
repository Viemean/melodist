package org.melodist.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object CoverCompressor {
    private const val TAG = "CoverCompressor"
    const val TARGET_DIMENSION = 500
    private const val COMPRESS_QUALITY = 85

    fun isLowResolution(file: File, minDimension: Int = TARGET_DIMENSION): Boolean {
        if (!file.exists() || file.length() == 0L) return true
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val w = boundsOpts.outWidth
            val h = boundsOpts.outHeight
            w > 0 && h > 0 && (w < minDimension || h < minDimension)
        } catch (_: Exception) {
            false
        }
    }

    fun compressToWebp(
        bytes: ByteArray,
        targetFile: File,
        targetDimension: Int = TARGET_DIMENSION,
    ): Boolean {
        if (bytes.isEmpty()) return false
        targetFile.parentFile?.mkdirs()

        val boundsOpts =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return false

        var inSample = 1
        val minDim = minOf(origW, origH)
        while ((minDim / (inSample * 2)) >= targetDimension) {
            inSample *= 2
        }

        val decodeOpts =
            BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

        val decodedBmp =
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "OOM decoding cover bytes", e)
                null
            } ?: return false

        val squareBmp = cropToSquare(decodedBmp)
        val finalBmp = scaleToTarget(squareBmp, targetDimension)

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp_${System.nanoTime()}")
        return try {
            FileOutputStream(tempFile).use { fos ->
                val format =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                finalBmp.compress(format, COMPRESS_QUALITY, fos)
                fos.flush()
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compress cover to webp", e)
            tempFile.delete()
            false
        } finally {
            if (finalBmp != squareBmp && finalBmp != decodedBmp) {
                finalBmp.recycle()
            }
            if (squareBmp != decodedBmp) {
                squareBmp.recycle()
            }
            decodedBmp.recycle()
        }
    }

    private fun cropToSquare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w == h) return src
        val edge = minOf(w, h)
        val x = (w - edge) / 2
        val y = (h - edge) / 2
        return Bitmap.createBitmap(src, x, y, edge, edge)
    }

    private fun scaleToTarget(
        src: Bitmap,
        target: Int,
    ): Bitmap {
        val w = src.width
        val h = src.height
        if (w == target && h == target) return src
        return Bitmap.createScaledBitmap(src, target, target, true)
    }
}
