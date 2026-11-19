package org.melodist.playback.usb

import android.util.Log

class UsbAudioStream(
    fd: Int,
    interfaceId: Int,
    endpointOut: Int,
    endpointFeedback: Int,
    sampleRate: Int,
    channelCount: Int,
    bitDepth: Int,
    maxPacketSize: Int,
) {
    var nativeHandle: Long = 0L
        private set

    init {
        nativeHandle =
            nativeUsbAudioCreate(
                fd,
                interfaceId,
                endpointOut,
                endpointFeedback,
                sampleRate,
                channelCount,
                bitDepth,
                maxPacketSize,
            )
        if (nativeHandle == 0L) {
            Log.e(TAG, "nativeUsbAudioCreate returned 0 — check logcat for native errors")
        }
    }

    val isReady: Boolean
        get() = nativeHandle != 0L

    val isAlive: Boolean
        get() = nativeHandle != 0L && nativeIsRunning(nativeHandle)

    val framesWritten: Long
        get() = if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else 0L

    fun setAltSetting(altSetting: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetAltSetting(nativeHandle, altSetting)
    }

    fun setSampleRate(
        sampleRateHz: Int,
        clockSourceId: Int = 0,
    ): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioSetSampleRate(nativeHandle, sampleRateHz, clockSourceId)
    }

    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeUsbAudioStart(nativeHandle)
    }

    fun write(pcmBuffer: FloatArray) {
        if (nativeHandle == 0L) return
        nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    fun writeRaw(
        pcmBuffer: ByteArray,
        inputBitDepth: Int,
    ) {
        if (nativeHandle == 0L) return
        nativeUsbAudioWriteRaw(nativeHandle, pcmBuffer, inputBitDepth)
    }

    fun setVolume(volume: Float) {
        if (nativeHandle == 0L) return
        nativeSetVolume(nativeHandle, volume)
    }

    fun stop() {
        if (nativeHandle == 0L) return
        nativeUsbAudioStop(nativeHandle)
    }

    fun flush() {
        if (nativeHandle == 0L) return
        nativeFlush(nativeHandle)
    }

    fun drainUrbs(): Int {
        if (nativeHandle == 0L) return 0
        return nativeDrainUrbs(nativeHandle)
    }

    fun release() {
        if (nativeHandle == 0L) return
        nativeUsbAudioDestroy(nativeHandle)
        nativeHandle = 0L
    }

    private external fun nativeUsbAudioCreate(
        fd: Int,
        interfaceId: Int,
        endpointOut: Int,
        endpointFeedback: Int,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
        maxPacketSize: Int,
    ): Long

    private external fun nativeUsbAudioSetAltSetting(
        handle: Long,
        altSetting: Int,
    ): Boolean

    private external fun nativeUsbAudioSetSampleRate(
        handle: Long,
        sampleRateHz: Int,
        clockSourceId: Int,
    ): Boolean

    private external fun nativeUsbAudioStart(handle: Long): Boolean

    private external fun nativeUsbAudioWrite(
        handle: Long,
        pcmBuffer: FloatArray,
    )

    private external fun nativeUsbAudioWriteRaw(
        handle: Long,
        pcmBuffer: ByteArray,
        inputBitDepth: Int,
    )

    private external fun nativeUsbAudioStop(handle: Long)

    private external fun nativeFlush(handle: Long)

    private external fun nativeDrainUrbs(handle: Long): Int

    private external fun nativeUsbAudioDestroy(handle: Long)

    private external fun nativeIsRunning(handle: Long): Boolean

    private external fun nativeGetFramesWritten(handle: Long): Long

    private external fun nativeSetVolume(
        handle: Long,
        volume: Float,
    )

    companion object {
        private const val TAG = "UsbAudioStream"

        init {
            System.loadLibrary("melodist_usb_audio")
        }

        @JvmStatic
        external fun nativeUsbReset(fd: Int): Int
    }
}
