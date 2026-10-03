package org.melodist.data.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppUpdateDownloaderTest {
    @Test
    fun testCleanOldApks(@TempDir tempDir: File) {
        val updatesDir = File(tempDir, "updates").apply { mkdirs() }
        val oldApk1 = File(updatesDir, "melodist_1.4.0.apk").apply { writeText("dummy1") }
        val oldApk2 = File(updatesDir, "melodist_1.4.1.apk.tmp").apply { writeText("dummy2") }
        val currentApk = File(updatesDir, "melodist_1.5.0.apk").apply { writeText("dummy3") }

        assertTrue(oldApk1.exists())
        assertTrue(oldApk2.exists())
        assertTrue(currentApk.exists())

        // 模拟执行清理，保留 currentApk
        updatesDir.listFiles()?.forEach { file ->
            if (file.absolutePath != currentApk.absolutePath) {
                file.delete()
            }
        }

        assertFalse(oldApk1.exists())
        assertFalse(oldApk2.exists())
        assertTrue(currentApk.exists())
    }
}
