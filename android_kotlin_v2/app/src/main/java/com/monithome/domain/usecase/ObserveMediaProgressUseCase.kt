package com.monithome.domain.usecase

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ObserveMediaProgressUseCase {
    operator fun invoke(baseProgress: Double, duration: Double, lastUpdateUnixTime: Double, isPlaying: Boolean): Flow<Float> = flow {
        if (duration <= 0) {
            emit(0f)
            return@flow
        }

        if (!isPlaying) {
            emit((baseProgress / duration).toFloat().coerceIn(0f, 1f))
            return@flow
        }

        while (true) {
            val now = System.currentTimeMillis() / 1000.0
            val currentProgress = baseProgress + (now - lastUpdateUnixTime)
            val ratio = (currentProgress / duration).toFloat().coerceIn(0f, 1f)
            emit(ratio)
            delay(100) // 10Hz
        }
    }
}
