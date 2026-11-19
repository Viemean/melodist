package org.melodist.playback.usb

/**
 * USB Bit-Perfect 直通运行状态。
 *
 * @property isConnected 是否已检测到兼容的 USB 音频输出硬件
 * @property deviceName 已连接的 USB 音频外设名称
 * @property sampleRate 实时直通输出采样率（Hz）
 * @property bitDepth 实时直通输出位深（bit）
 * @property isStreaming 是否正在向 USB 传输音频流
 */
data class UsbBitPerfectStatus(
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val isStreaming: Boolean = false,
)
