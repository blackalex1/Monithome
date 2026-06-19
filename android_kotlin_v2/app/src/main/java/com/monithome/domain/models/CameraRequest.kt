package com.monithome.domain.models

sealed class CameraRequest {
    data class Start(
        val useUsb: Boolean,
        val useFront: Boolean,
        val quality: String
    ) : CameraRequest()
    object Stop : CameraRequest()
}
