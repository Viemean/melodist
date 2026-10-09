package org.melodist.playback

import androidx.media3.common.ParserException
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException

class ResilientFlacExtractorTest {
    @Test
    fun `aligns to next sync code after seek`() {
        // 前 4 字节为随机垃圾数据，第 4、5 字节为 FLAC 同步字 0xFF, 0xF8
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xFF.toByte(), 0xF8.toByte(), 0x12, 0x34)
        val fakeInput = TestExtractorInput(data)

        var delegateReadCalled = false
        val fakeDelegate =
            object : Extractor {
                override fun sniff(input: ExtractorInput): Boolean = true

                override fun init(output: ExtractorOutput) {}

                override fun seek(
                    position: Long,
                    timeUs: Long,
                ) {}

                override fun release() {}

                override fun read(
                    input: ExtractorInput,
                    seekPosition: PositionHolder,
                ): Int {
                    delegateReadCalled = true
                    assertEquals(4L, input.position)
                    return Extractor.RESULT_CONTINUE
                }
            }

        val extractor = ResilientFlacExtractor(fakeDelegate)
        extractor.seek(100L, 1000_000L) // 触发 pendingSeekResync

        val result = extractor.read(fakeInput, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertTrue(delegateReadCalled)
    }

    @Test
    fun `tolerates trailing malformed frame when near end of input`() {
        val totalLength = 100_000
        val data = ByteArray(totalLength)
        val fakeInput = TestExtractorInput(data)
        // 模拟已读取到流尾部（距离末尾不到 64KB）
        fakeInput.skipFully(totalLength - 100)

        val fakeDelegate =
            object : Extractor {
                override fun sniff(input: ExtractorInput): Boolean = true

                override fun init(output: ExtractorOutput) {}

                override fun seek(
                    position: Long,
                    timeUs: Long,
                ) {}

                override fun release() {}

                override fun read(
                    input: ExtractorInput,
                    seekPosition: PositionHolder,
                ): Int = throw ParserException.createForMalformedContainer("Trailing garbage frame", null)
            }

        val extractor = ResilientFlacExtractor(fakeDelegate)
        val result = extractor.read(fakeInput, PositionHolder())
        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
    }

    @Test
    fun `resyncs to next frame when malformed frame occurs mid-stream`() {
        val totalLength = 200_000
        val data = ByteArray(totalLength)
        // 在偏移 10000 处埋设损坏数据，并在 10010 处布置同步头 0xFF, 0xF8
        data[10010] = 0xFF.toByte()
        data[10011] = 0xF8.toByte()

        val fakeInput = TestExtractorInput(data)
        fakeInput.skipFully(10000)

        var callCount = 0
        val fakeDelegate =
            object : Extractor {
                override fun sniff(input: ExtractorInput): Boolean = true

                override fun init(output: ExtractorOutput) {}

                override fun seek(
                    position: Long,
                    timeUs: Long,
                ) {}

                override fun release() {}

                override fun read(
                    input: ExtractorInput,
                    seekPosition: PositionHolder,
                ): Int {
                    callCount++
                    if (callCount == 1) {
                        throw ParserException.createForMalformedContainer("Corrupted middle frame", null)
                    }
                    assertEquals(10010L, input.position)
                    return Extractor.RESULT_CONTINUE
                }
            }

        val extractor = ResilientFlacExtractor(fakeDelegate)
        val result1 = extractor.read(fakeInput, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result1)
        assertEquals(10010L, fakeInput.position)

        val result2 = extractor.read(fakeInput, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result2)
        assertEquals(2, callCount)
    }

    private class TestExtractorInput(
        private val data: ByteArray,
    ) : ExtractorInput {
        private var position = 0
        private var peekPosition = 0

        override fun getPosition(): Long = position.toLong()

        override fun getLength(): Long = data.size.toLong()

        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun resetPeekPosition() {
            peekPosition = position
        }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= data.size) return -1
            val bytesToRead = minOf(length, data.size - position)
            System.arraycopy(data, position, target, offset, bytesToRead)
            position += bytesToRead
            peekPosition = position
            return bytesToRead
        }

        override fun readFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean,
        ): Boolean {
            if (position + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            System.arraycopy(data, position, target, offset, length)
            position += length
            peekPosition = position
            return true
        }

        override fun readFully(
            target: ByteArray,
            offset: Int,
            length: Int,
        ) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val bytesToSkip = minOf(length, data.size - position)
            position += bytesToSkip
            peekPosition = position
            return bytesToSkip
        }

        override fun skipFully(
            length: Int,
            allowEndOfInput: Boolean,
        ): Boolean {
            if (position + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            position += length
            peekPosition = position
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (peekPosition >= data.size) return -1
            val bytesToRead = minOf(length, data.size - peekPosition)
            System.arraycopy(data, peekPosition, target, offset, bytesToRead)
            peekPosition += bytesToRead
            return bytesToRead
        }

        override fun peekFully(
            target: ByteArray,
            offset: Int,
            length: Int,
            allowEndOfInput: Boolean,
        ): Boolean {
            if (peekPosition + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            System.arraycopy(data, peekPosition, target, offset, length)
            peekPosition += length
            return true
        }

        override fun peekFully(
            target: ByteArray,
            offset: Int,
            length: Int,
        ) {
            peekFully(target, offset, length, false)
        }

        override fun advancePeekPosition(
            length: Int,
            allowEndOfInput: Boolean,
        ): Boolean {
            if (peekPosition + length > data.size) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun <E : Throwable?> setRetryPosition(
            position: Long,
            e: E,
        ) {
            this.position = position.toInt()
            this.peekPosition = this.position
        }
    }
}
