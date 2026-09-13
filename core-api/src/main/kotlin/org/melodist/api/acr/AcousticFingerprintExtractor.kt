package org.melodist.api.acr

import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 声学特征实体
 */
data class AcousticFeature(
    val data: ByteArray,
    val duration: Float,
    val featureType: Int = 0,
    val confidence: Float = 0.0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AcousticFeature
        return data.contentEquals(other.data) &&
            duration == other.duration &&
            featureType == other.featureType &&
            confidence == other.confidence
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + featureType
        result = 31 * result + confidence.hashCode()
        return result
    }
}

/**
 * 高性能原生声学指纹特征提取器
 * 纯 Kotlin / JVM 原生实现，全预计算查表与高密度位流组装，零外部依赖。
 */
object AcousticFingerprintExtractor {
    const val SAMPLE_RATE = 8000
    const val WINDOW_SIZE = 1024
    const val HOP_SIZE = 128
    const val FFT_SIZE = 1024
    const val CHANNEL_COUNT = 4
    const val TIME_WINDOW = 5
    const val FREQ_WINDOW = 20
    const val MAX_PEAKS_PER_FRAME = 5
    const val MAG_THRESHOLD = 20000.0f

    private val hammingWindow =
        FloatArray(WINDOW_SIZE) { n ->
            (0.54 - 0.46 * cos(2.0 * Math.PI * n / (WINDOW_SIZE - 1))).toFloat()
        }

    private val bitRevSwaps: IntArray =
        run {
            val swaps = mutableListOf<Int>()
            var j = 0
            for (i in 0 until FFT_SIZE - 1) {
                if (i < j) {
                    swaps.add(i)
                    swaps.add(j)
                }
                var k = FFT_SIZE shr 1
                while (k <= j) {
                    j -= k
                    k = k shr 1
                }
                j += k
            }
            swaps.toIntArray()
        }

    private val twiddlesRe: Array<FloatArray>
    private val twiddlesIm: Array<FloatArray>

    init {
        var stages = 0
        var lTmp = 1
        while (lTmp < FFT_SIZE) {
            stages++
            lTmp = lTmp shl 1
        }
        twiddlesRe = Array(stages) { FloatArray(0) }
        twiddlesIm = Array(stages) { FloatArray(0) }

        var stage = 0
        var l = 1
        while (l < FFT_SIZE) {
            val re = FloatArray(l)
            val im = FloatArray(l)
            val angle = -Math.PI / l
            val wRe = cos(angle)
            val wIm = kotlin.math.sin(angle)
            var curRe = 1.0
            var curIm = 0.0
            for (k in 0 until l) {
                re[k] = curRe.toFloat()
                im[k] = curIm.toFloat()
                val nextRe = curRe * wRe - curIm * wIm
                val nextIm = curRe * wIm + curIm * wRe
                curRe = nextRe
                curIm = nextIm
            }
            twiddlesRe[stage] = re
            twiddlesIm[stage] = im
            stage++
            l = l shl 1
        }
    }

    private val modeBitWidths = intArrayOf(1, 2, 3, 4, 5, 6, 7, 9)
    private val modeItemCounts = intArrayOf(36, 18, 12, 9, 7, 6, 5, 4)
    private val modePaddingBits = intArrayOf(0, 0, 0, 0, 1, 0, 1, 0)
    private val maxDiffs = intArrayOf(1, 3, 4, 15, 31, 63, 127, 511)

    data class Landmark(
        val time: Int,
        val freq: Int,
    )

    /**
     * 将 16000Hz PCM 降采样至 8000Hz (单声道 2 点低通滑动均值)
     */
    fun downsample16kTo8k(pcm16k: ShortArray): ShortArray {
        val targetLen = pcm16k.size / 2
        val pcm8k = ShortArray(targetLen)
        for (i in 0 until targetLen) {
            val sum = pcm16k[i * 2].toInt() + pcm16k[i * 2 + 1].toInt()
            pcm8k[i] = (sum / 2).toShort()
        }
        return pcm8k
    }

    /**
     * 从 8000Hz 16-bit 单声道 PCM 样本直接提取声学特征实体
     */
    fun extract(samples: ShortArray): AcousticFeature? {
        if (samples.size < WINDOW_SIZE + HOP_SIZE * (TIME_WINDOW * CHANNEL_COUNT)) {
            return null
        }

        val landmarks = extractLandmarks(samples)
        val featBytes = packLandmarks(landmarks)
        if (featBytes.isEmpty()) {
            return null
        }

        val duration = samples.size.toFloat() / SAMPLE_RATE
        return AcousticFeature(featBytes, duration, 0, 0.0f)
    }

    /**
     * 从 8000Hz 16-bit 单声道 PCM 提取四通道特征点坐标
     */
    fun extractLandmarks(samples: ShortArray): Array<List<Landmark>> {
        val totalSamples = samples.size
        val numFrames = (totalSamples - WINDOW_SIZE) / HOP_SIZE + 1
        if (numFrames <= TIME_WINDOW * CHANNEL_COUNT) {
            return Array(CHANNEL_COUNT) { emptyList() }
        }

        val halfFft = FFT_SIZE / 2
        val spectrogram = Array(numFrames) { FloatArray(halfFft + 1) }
        val fftRe = FloatArray(FFT_SIZE)
        val fftIm = FloatArray(FFT_SIZE)

        for (i in 0 until numFrames) {
            val stStart = i * HOP_SIZE
            for (n in 0 until WINDOW_SIZE) {
                fftRe[n] = samples[stStart + n] * hammingWindow[n]
                fftIm[n] = 0.0f
            }
            for (n in WINDOW_SIZE until FFT_SIZE) {
                fftRe[n] = 0.0f
                fftIm[n] = 0.0f
            }

            computeFft(fftRe, fftIm)

            val mag = spectrogram[i]
            for (f in 0..halfFft) {
                val re = fftRe[f]
                val im = fftIm[f]
                mag[f] = sqrt(re * re + im * im)
            }
        }

        // 4 相时域交织分流
        val channelSpecs = Array(CHANNEL_COUNT) { ArrayList<FloatArray>(numFrames / CHANNEL_COUNT + 1) }
        for (i in 0 until numFrames) {
            channelSpecs[i % CHANNEL_COUNT].add(spectrogram[i])
        }

        val result = Array<List<Landmark>>(CHANNEL_COUNT) { emptyList() }
        val cands = ArrayList<CandidatePeak>(64)

        for (ch in 0 until CHANNEL_COUNT) {
            val spec = channelSpecs[ch]
            val numT = spec.size
            val list = ArrayList<Landmark>()

            val targetEnd = max(0, numT - TIME_WINDOW)
            for (t in 0 until targetEnd) {
                cands.clear()
                val rowT = spec[t]
                for (f in 3..510) {
                    val v = rowT[f]
                    if (v < MAG_THRESHOLD) continue

                    val tStart = max(0, t - TIME_WINDOW)
                    val tEnd = t + TIME_WINDOW
                    val fStart = max(3, f - FREQ_WINDOW)
                    val fEnd = min(510, f + FREQ_WINDOW)

                    var isLocalMax = true
                    for (tPrime in tStart..tEnd) {
                        val rowPrime = spec[tPrime]
                        for (fPrime in fStart..fEnd) {
                            if (rowPrime[fPrime] > v) {
                                isLocalMax = false
                                break
                            }
                        }
                        if (!isLocalMax) break
                    }

                    if (isLocalMax) {
                        cands.add(CandidatePeak(f, v))
                    }
                }

                if (cands.size > 1) {
                    cands.sortByDescending { it.value }
                }
                val take = min(MAX_PEAKS_PER_FRAME, cands.size)
                for (k in 0 until take) {
                    list.add(Landmark(t, cands[k].freq))
                }
            }
            result[ch] = list
        }

        return result
    }

    private data class CandidatePeak(
        val freq: Int,
        val value: Float,
    )

    /**
     * 时间帧一阶差分分块熵编码容器 (Part 1)
     */
    fun packPart1(times: List<Int>): ByteArray {
        val n = times.size
        if (n == 0) return byteArrayOf(0, 0, 0, 0)

        val blocks = ArrayList<Block>()
        var currIdx = 0
        while (currIdx < n) {
            val t0 = times[currIdx]
            var chosenMode = 7
            for (m in 0 until 8) {
                val cnt = modeItemCounts[m]
                val maxVal = maxDiffs[m]
                var valid = true
                for (k in 0 until cnt) {
                    val idx2 = currIdx + 1 + k
                    val diff = if (idx2 < n) times[idx2] - times[idx2 - 1] else 0
                    if (diff > maxVal) {
                        valid = false
                        break
                    }
                }
                if (valid) {
                    chosenMode = m
                    break
                }
            }

            val blockCnt = modeItemCounts[chosenMode]
            val blockDiffs = IntArray(blockCnt)
            for (k in 0 until blockCnt) {
                val idx2 = currIdx + 1 + k
                blockDiffs[k] = if (idx2 < n) times[idx2] - times[idx2 - 1] else 0
            }

            blocks.add(Block(t0, chosenMode, blockDiffs))
            currIdx += 1 + blockCnt
        }

        val bw = BitWriter()
        bw.writeBits(n, 16)
        bw.writeBits(blocks.size, 16)

        for (block in blocks) {
            bw.writeBits(block.t0 and 0x1FF, 9)
            bw.writeBits(block.mode and 7, 3)
            val w = modeBitWidths[block.mode]
            for (d in block.diffs) {
                bw.writeBits(d, w)
            }
            if (modePaddingBits[block.mode] > 0) {
                bw.writeBits(0, 1)
            }
        }

        return bw.toBytes()
    }

    private class Block(
        val t0: Int,
        val mode: Int,
        val diffs: IntArray,
    )

    /**
     * 频点 9-bit 定长紧凑流序列化 (Part 2)
     */
    fun packPart2(freqs: List<Int>): ByteArray {
        val bw = BitWriter()
        bw.writeBits(freqs.size, 16)
        for (f in freqs) {
            bw.writeBits(f and 0x1FF, 9)
        }
        return bw.toBytes()
    }

    /**
     * 将提取的四通道极大值坐标组装为通用动态变长二进制指纹
     */
    fun packLandmarks(channelPeaks: Array<List<Landmark>>): ByteArray {
        val out = ByteArrayOutputStream()

        for (b in 0 until CHANNEL_COUNT) {
            val peaks = if (b < channelPeaks.size) channelPeaks[b] else emptyList()
            val times = ArrayList<Int>(peaks.size)
            val freqs = ArrayList<Int>(peaks.size)
            for (p in peaks) {
                times.add(p.time)
                freqs.add(p.freq)
            }

            val p1 = packPart1(times)
            val p2 = packPart2(freqs)

            out.write(p1)
            out.write(p2)
        }

        return out.toByteArray()
    }

    private fun computeFft(
        re: FloatArray,
        im: FloatArray,
    ) {
        var idx = 0
        while (idx < bitRevSwaps.size) {
            val i = bitRevSwaps[idx]
            val j = bitRevSwaps[idx + 1]
            val tRe = re[i]
            re[i] = re[j]
            re[j] = tRe
            val tIm = im[i]
            im[i] = im[j]
            im[j] = tIm
            idx += 2
        }

        var stage = 0
        var l = 1
        while (l < FFT_SIZE) {
            val stageTwiddlesRe = twiddlesRe[stage]
            val stageTwiddlesIm = twiddlesIm[stage]
            stage++
            val step = l shl 1
            var m = 0
            while (m < FFT_SIZE) {
                for (k in 0 until l) {
                    val p = m + k
                    val q = p + l

                    val curRe = stageTwiddlesRe[k]
                    val curIm = stageTwiddlesIm[k]
                    val tRe = curRe * re[q] - curIm * im[q]
                    val tIm = curRe * im[q] + curIm * re[q]

                    re[q] = re[p] - tRe
                    im[q] = im[p] - tIm
                    re[p] += tRe
                    im[p] += tIm
                }
                m += step
            }
            l = l shl 1
        }
    }

    private class BitWriter {
        private var buffer = ByteArray(256)
        private var bitCount = 0

        fun writeBits(
            value: Int,
            numBits: Int,
        ) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value shr i) and 1
                val byteIdx = bitCount shr 3
                val bitPos = 7 - (bitCount and 7)

                if (byteIdx >= buffer.size) {
                    buffer = buffer.copyOf(buffer.size * 2)
                }

                if (bit != 0) {
                    buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitPos)).toByte()
                }

                bitCount++
            }
        }

        fun toBytes(): ByteArray {
            val totalBytes = (bitCount + 7) shr 3
            return buffer.copyOf(totalBytes)
        }
    }
}
