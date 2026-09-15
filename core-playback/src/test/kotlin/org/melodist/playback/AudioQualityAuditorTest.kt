package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import kotlin.math.PI
import kotlin.math.sin

class AudioQualityAuditorTest {
    @Test
    fun testPowerSpectrumComputationOnPureTone() {
        val sampleRate = 44100
        val freq = 1000.0 // 1kHz 正弦波
        val samples = FloatArray(4096)
        for (i in samples.indices) {
            samples[i] = (0.8 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }

        val powerSpec = AudioQualityAuditor.computeLinearPowerSpectrum(samples)
        assertEquals(2048, powerSpec.size)

        val dbSpec = AudioQualityAuditor.convertPowerSpectrumsToDb(listOf(powerSpec))
        assertEquals(2048, dbSpec.size)

        val binRes = (sampleRate / 2f) / dbSpec.size
        val targetBin = (freq / binRes).toInt()

        var maxBin = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in dbSpec.indices) {
            if (dbSpec[i] > maxVal) {
                maxVal = dbSpec[i]
                maxBin = i
            }
        }
        assertTrue(kotlin.math.abs(maxBin - targetBin) <= 2, "Max peak should be near 1kHz")
    }

    @Test
    fun testFakeLosslessDetectionAt16kHzWithPrecedingNotchEq() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造频谱：在 11kHz 处有一个 20dB 的陷波凹陷（随后在 13kHz 又反弹回 -20dB），而在 16kHz 处发生真正的持续死寂低通截断
        for (i in 0 until bins) {
            val f = i * binRes
            when {
                f in 10800.0..11500.0 -> spectrum[i] = -42f // 11kHz 陷波
                f < 16000 -> spectrum[i] = -20f // 正常活跃乐声
                else -> spectrum[i] = -75f // 16kHz 永久死寂低通
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 900,
                tier = AudioQualityTier.SQ,
            )

        // 不应被 11kHz 陷波劫持，必须准确命中 16kHz 假无损
        assertEquals(AudioQualityVerdict.FAKE_LOSSLESS, result.verdict)
        assertTrue(result.cutoffFrequencyHz in 15000..16800)
    }

    @Test
    fun testAuthenticLosslessDetection() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造真无损平滑衰减频谱 (2kHz=-20dB, 20kHz=-32dB, 22kHz=-38dB)
        for (i in 0 until bins) {
            val f = i * binRes
            spectrum[i] = -20f - (f / 22050f) * 18f
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 950,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
    }

    @Test
    fun testCdNativeAntiAliasingFilterNotMisclassifiedAs320kFake() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造正版 CD 在 20.5kHz~22.05kHz 处的抗混叠滤波滚降 (20.5kHz 以前 -25dB，20.5kHz~22.05kHz 跌落至 -80dB)
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 20500) {
                spectrum[i] = -25f - (f / 20500f) * 5f
            } else {
                spectrum[i] = -80f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 900,
                tier = AudioQualityTier.SQ,
            )

        // 必须判定为真无损，绝不能被误判为 320k 假无损
        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
    }

    @Test
    fun testAuthenticHiResWithoutUltrasoundRecognizedCorrectly() {
        val sampleRate = 96000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造 96k 录音但乐器（大提琴/木吉他）在 22kHz 以上无泛音的母带（自然衰减到底噪 -85dB，无任何 44.1k/48k 升频断崖）
        for (i in 0 until bins) {
            val f = i * binRes
            spectrum[i] = -22f - (f / 22000f) * 60f
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 2800,
                tier = AudioQualityTier.HiRes,
            )

        // 必须承认其为 Hi-Res
        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
        assertTrue(result.description.contains("高解析"))
    }

    @Test
    fun testUpsampledHiResDetection() {
        val sampleRate = 96000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造 44.1k 升频为 96k 的频谱 (21.8kHz 处阶跃跌落，24kHz 以上全平底噪 -90dB)
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 21800) {
                spectrum[i] = -22f - (f / 21800f) * 15f
            } else {
                spectrum[i] = -90f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 2800,
                tier = AudioQualityTier.HiRes,
            )

        assertEquals(AudioQualityVerdict.UPSAMPLED_HIRES, result.verdict)
    }

    @Test
    fun testUpsampledHiResDetectionAt19500HzGap() {
        val sampleRate = 96000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造 19.5kHz 处截断的 96k 升频文件（消除 19.2k ~ 20k 黑洞）
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 19500) {
                spectrum[i] = -22f - (f / 19500f) * 15f
            } else {
                spectrum[i] = -90f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 2800,
                tier = AudioQualityTier.HiRes,
            )

        assertEquals(AudioQualityVerdict.UPSAMPLED_HIRES, result.verdict)
    }

    @Test
    fun testVotingMechanismSingleFakeSliceDoesNotMisclassifyFullTrack() {
        val sampleRate = 44100
        val fakeSlice =
            AudioAuditResult(
                verdict = AudioQualityVerdict.FAKE_LOSSLESS,
                cutoffFrequencyHz = 16000,
                confidenceScore = 0.96f,
                sampleRateHz = sampleRate,
            )
        val authenticSlice1 =
            AudioAuditResult(
                verdict = AudioQualityVerdict.AUTHENTIC,
                cutoffFrequencyHz = 22050,
                confidenceScore = 0.99f,
                sampleRateHz = sampleRate,
            )
        val authenticSlice2 =
            AudioAuditResult(
                verdict = AudioQualityVerdict.AUTHENTIC,
                cutoffFrequencyHz = 22050,
                confidenceScore = 0.99f,
                sampleRateHz = sampleRate,
            )

        // 1 票假无损（局部弱音切片），2 票真无损 -> 疑罪从无判定为真无损
        val voted = AudioQualityAuditor.voteAuditResults(listOf(fakeSlice, authenticSlice1, authenticSlice2))
        assertEquals(AudioQualityVerdict.AUTHENTIC, voted.verdict)
    }

    @Test
    fun testVotingMechanismMajorityFakeSlicesConfirmedAsFakeLossless() {
        val sampleRate = 44100
        val fakeSlice1 =
            AudioAuditResult(
                verdict = AudioQualityVerdict.FAKE_LOSSLESS,
                cutoffFrequencyHz = 16000,
                confidenceScore = 0.96f,
                sampleRateHz = sampleRate,
            )
        val fakeSlice2 =
            AudioAuditResult(
                verdict = AudioQualityVerdict.FAKE_LOSSLESS,
                cutoffFrequencyHz = 16200,
                confidenceScore = 0.96f,
                sampleRateHz = sampleRate,
            )
        val authenticSlice =
            AudioAuditResult(
                verdict = AudioQualityVerdict.AUTHENTIC,
                cutoffFrequencyHz = 22050,
                confidenceScore = 0.99f,
                sampleRateHz = sampleRate,
            )

        // 2 票假无损 -> 确认假无损
        val voted = AudioQualityAuditor.voteAuditResults(listOf(fakeSlice1, fakeSlice2, authenticSlice))
        assertEquals(AudioQualityVerdict.FAKE_LOSSLESS, voted.verdict)
    }

    @Test
    fun testAuthentic96kHiResWithHardwareFilterAt46kHzNotDegraded() {
        val sampleRate = 96000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 构造超宽频 Hi-Res：泛音平缓延伸至 44kHz (-35dB)，并在 46kHz 处遭遇声卡硬件抗混叠硬截断 (46k~48k 跌至 -85dB)
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 46000) {
                spectrum[i] = -20f - (f / 46000f) * 15f // 46k 处约 -35dB
            } else {
                spectrum[i] = -85f // 46k 以上硬件切除
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 3200,
                tier = AudioQualityTier.HiRes,
            )

        // 必须确认为真实 Hi-Res，绝不能因 46kHz 硬件断崖被误杀或降级
        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
        assertTrue(result.description.contains("高解析"))
    }

    @Test
    fun testSingleSliceFakeLosslessDowngradedToIndeterminate() {
        val sampleRate = 44100
        val singleFakeSlice =
            AudioAuditResult(
                verdict = AudioQualityVerdict.FAKE_LOSSLESS,
                cutoffFrequencyHz = 16000,
                confidenceScore = 0.96f,
                sampleRateHz = sampleRate,
            )

        // 单切片孤证不足以定罪 -> 疑罪从无降级为 INDETERMINATE
        val voted = AudioQualityAuditor.voteAuditResults(listOf(singleFakeSlice))
        assertEquals(AudioQualityVerdict.INDETERMINATE, voted.verdict)
    }

    // ==========================================
    // 黄金样本库（Golden Regression Test Suite）
    // ==========================================

    @Test
    fun testGoldenAuthenticCdPopVocal() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟流行人声母带：中频人声饱满（2k~6k 约 -18dB），高频齿音和镲片延伸至 20kHz 约 -35dB，22.05kHz 抗混叠滤波滚降
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 20000) {
                spectrum[i] = -18f - (f / 20000f) * 17f
            } else {
                spectrum[i] = -35f - ((f - 20000f) / 2050f) * 45f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 950,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
    }

    @Test
    fun testGoldenAuthenticAcousticGuitarSolo() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟纯木吉他/弱音器乐独奏：12kHz 以上能量自然平缓衰减到底噪 -70dB，无任何阶跃截断
        for (i in 0 until bins) {
            val f = i * binRes
            spectrum[i] = -22f - (f / 22050f) * 48f
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 750,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
    }

    @Test
    fun testGoldenAuthenticOrchestraSymphony() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟交响乐：宽动态全频延伸，铜管与打击乐泛音直达 21.5kHz
        for (i in 0 until bins) {
            val f = i * binRes
            spectrum[i] = -15f - (f / 22050f) * 20f
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 1020,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
    }

    @Test
    fun testGoldenAuthenticHiRes192kMaster() {
        val sampleRate = 192000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟 192kHz 原生母带：超声泛音一直自然延伸至 60kHz+，在 85kHz 处声卡硬件滤波
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 85000) {
                spectrum[i] = -20f - (f / 85000f) * 25f
            } else {
                spectrum[i] = -90f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 6200,
                tier = AudioQualityTier.Master,
            )

        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
        assertTrue(result.description.contains("高解析"))
    }

    @Test
    fun testGoldenFakeLossless192kMp3Transcode() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟 192k MP3 转 FLAC：在 18.2kHz 处发生 30dB 陡峭砖墙硬截断
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 18200) {
                spectrum[i] = -22f - (f / 18200f) * 12f
            } else {
                spectrum[i] = -82f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 900,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.FAKE_LOSSLESS, result.verdict)
        assertTrue(result.cutoffFrequencyHz in 17500..18800)
    }

    @Test
    fun testGoldenFakeLossless256kAacTranscode() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟 256k AAC 转 FLAC：在 18.5kHz 处发生 25dB 砖墙硬截断
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 18500) {
                spectrum[i] = -20f - (f / 18500f) * 10f
            } else {
                spectrum[i] = -80f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 920,
                tier = AudioQualityTier.SQ,
            )

        assertEquals(AudioQualityVerdict.FAKE_LOSSLESS, result.verdict)
        assertTrue(result.cutoffFrequencyHz in 18000..19200)
    }

    @Test
    fun testGoldenUpsampledFrom48kTo96kHiRes() {
        val sampleRate = 96000
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟 48kHz 母带升频至 96kHz：在 23.9kHz 处出现 48k 原生低通断崖
        for (i in 0 until bins) {
            val f = i * binRes
            if (f < 23900) {
                spectrum[i] = -22f - (f / 23900f) * 10f
            } else {
                spectrum[i] = -90f
            }
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 24,
                channels = 2,
                bitrateKbps = 2800,
                tier = AudioQualityTier.HiRes,
            )

        assertEquals(AudioQualityVerdict.UPSAMPLED_HIRES, result.verdict)
        assertTrue(result.cutoffFrequencyHz in 23500..24500)
    }

    @Test
    fun testGoldenWarmVintageAnalogMastering() {
        val sampleRate = 44100
        val bins = 2048
        val binRes = (sampleRate / 2f) / bins
        val spectrum = FloatArray(bins)

        // 模拟早期 70 年代暖色模拟磁带录音：高频从 10k 开始自然平滑滚降，在 20k 处降至 -65dB，无任何阶跃截断
        for (i in 0 until bins) {
            val f = i * binRes
            spectrum[i] = -18f - (f / 22050f) * 48f
        }

        val result =
            AudioQualityAuditor.evaluateSpectrum(
                spectrum = spectrum,
                sampleRate = sampleRate,
                bitDepth = 16,
                channels = 2,
                bitrateKbps = 820,
                tier = AudioQualityTier.SQ,
            )

        // 必须判定为真无损，并在详情中注明偏暖风格
        assertEquals(AudioQualityVerdict.AUTHENTIC, result.verdict)
        assertTrue(result.details.contains("滚降") || result.details.contains("偏暖"))
    }
}
