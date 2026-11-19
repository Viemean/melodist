package org.melodist.playback.usb

import android.hardware.usb.UsbDeviceConnection

data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val controlInterfaceId: Int,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int,
)
