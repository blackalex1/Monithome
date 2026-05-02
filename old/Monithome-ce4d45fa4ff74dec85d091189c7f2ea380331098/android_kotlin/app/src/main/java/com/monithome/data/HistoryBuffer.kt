package com.monithome.data

/**
 * Высокопроизводительный кольцевой буфер для хранения истории датчиков.
 * Использует FloatArray для минимального потребления памяти.
 */
class HistoryBuffer(val size: Int = 30) {
    private val buffer = FloatArray(size)
    private var head = 0
    private var isFull = false

    fun push(value: Float) {
        buffer[head] = value
        head = (head + 1) % size
        if (head == 0) isFull = true
    }

    /**
     * Возвращает список значений в хронологическом порядке [самые старые -> самые новые]
     */
    fun getValues(): List<Float> {
        val result = mutableListOf<Float>()
        if (!isFull) {
            for (i in 0 until head) {
                result.add(buffer[i])
            }
        } else {
            // Сначала старая часть (от head до конца)
            for (i in head until size) {
                result.add(buffer[i])
            }
            // Потом новая часть (от начала до head)
            for (i in 0 until head) {
                result.add(buffer[i])
            }
        }
        return result
    }
}
