package org.melodist.mobile.connect.dlna

data class DlnaDevice(
    val id: String,
    val name: String,
    val location: String,
    val controlUrl: String,
    val modelName: String = "",
    val manufacturer: String = "",
)
