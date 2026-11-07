package org.melodist.data.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppUpdateDownloaderTest {
    @Test
    fun testCleanOldApks(
        @TempDir tempDir: File,
    ) {
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

    @Test
    fun testCandidateDownloadUrls() {
        val githubUrl = "https://github.com/Viemean/melodist/releases/download/v1.7.1/melodist-mobile.apk"
        val candidates = AppUpdateDownloader.getCandidateDownloadUrls(githubUrl)
        org.junit.jupiter.api.Assertions
            .assertEquals(3, candidates.size)
        org.junit.jupiter.api.Assertions
            .assertEquals("https://gh-proxy.com/$githubUrl", candidates[0])
        org.junit.jupiter.api.Assertions
            .assertEquals("https://ghproxy.net/$githubUrl", candidates[1])
        org.junit.jupiter.api.Assertions
            .assertEquals(githubUrl, candidates[2])

        val nonGithubUrl = "https://example.com/download.apk"
        val nonGithubCandidates = AppUpdateDownloader.getCandidateDownloadUrls(nonGithubUrl)
        org.junit.jupiter.api.Assertions
            .assertEquals(listOf(nonGithubUrl), nonGithubCandidates)
    }

    @Test
    fun testApkZipMagicValidation() {
        val validZipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        val htmlErrorBytes = "<html>502 Bad Gateway</html>".toByteArray(Charsets.UTF_8)

        fun isValidApk(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            return bytes[0] == 0x50.toByte() &&
                bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() &&
                bytes[3] == 0x04.toByte()
        }

        assertTrue(isValidApk(validZipBytes))
        assertFalse(isValidApk(htmlErrorBytes))
    }
}
