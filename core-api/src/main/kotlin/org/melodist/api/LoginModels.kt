package org.melodist.api

enum class QrStatus {
    Waiting,
    Confirming,
    Success,
    Expired,
    Canceled,
    Error,
}

data class QrCodeInfo(
    val imageBytes: ByteArray,
    val identifier: String,
    val mimeType: String = "image/png",
)

data class PollResult(
    val status: QrStatus,
    val message: String,
)
