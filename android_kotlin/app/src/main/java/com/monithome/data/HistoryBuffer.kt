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
        if (!isFull && head == 0) return emptyList()
        
        return if (!isFull) {
            buffer.sliceArray(0 until head).toList()
        } else {
            val result = ArrayList<Float>(size)
            for (i in head until size) result.add(buffer[i])
            for (i in 0 until head) result.add(buffer[i])
            result
        }
    }
}
