package org.melodist.playback.usb

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class UsbStreamingThread(
    private val usbStream: UsbAudioStream,
    initiallyPaused: Boolean = false,
) {
    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L
    }

    private sealed class AudioBuffer {
        class FloatBuffer(
            val data: FloatArray,
        ) : AudioBuffer()

        class RawBuffer(
            val data: ByteArray,
            val bitDepth: Int,
        ) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    @Volatile
    private var paused = initiallyPaused

    @Volatile
    private var isWriting = false

    private var thread: Thread? = null
    private var dropCount = 0

    fun start() {
        running = true
        thread =
            Thread({
                while (running) {
                    if (paused) {
                        Thread.sleep(50)
                        continue
                    }
                    when (val buf = audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        is AudioBuffer.FloatBuffer -> {
                            isWriting = true
                            try {
                                usbStream.write(buf.data)
                            } finally {
                                isWriting = false
                            }
                        }

                        is AudioBuffer.RawBuffer -> {
                            isWriting = true
                            try {
                                usbStream.writeRaw(buf.data, buf.bitDepth)
                            } finally {
                                isWriting = false
                            }
                        }

                        null -> {}
                    }
                }
            }, "UsbStreamingThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
    }

    fun enqueue(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped buffer #$dropCount")
            }
        }
    }

    fun enqueueRaw(
        rawBytes: ByteArray,
        bitDepth: Int,
    ) {
        val buf = AudioBuffer.RawBuffer(rawBytes, bitDepth)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped raw buffer #$dropCount")
            }
        }
    }

    fun pauseStreaming() {
        paused = true
    }

    fun resumeStreaming() {
        paused = false
    }

    fun hasPendingData(): Boolean = !audioQueue.isEmpty() || isWriting

    fun queueSize(): Int = audioQueue.size

    fun flush() {
        audioQueue.clear()
    }

    fun stop() {
        running = false
        audioQueue.clear()
        thread?.join(2000)
        thread = null
    }
}
