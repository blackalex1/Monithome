package com.monithome.domain.usecase

import com.monithome.domain.models.LyricLine

class SyncLyricsUseCase {
    
    /**
     * Возвращает индекс текущей строки с помощью бинарного поиска.
     * Сложность: O(log N) вместо O(N), что полностью устраняет фризы.
     * 
     * @param lines отсортированный по времени список строк
     * @param currentTimeMs текущее время трека в миллисекундах
     * @return индекс активной строки или -1, если еще не началось
     */
    operator fun invoke(lines: List<LyricLine>, currentTimeMs: Long): Int {
        if (lines.isEmpty() || currentTimeMs < lines.first().timeMs) return -1
        
        var low = 0
        var high = lines.size - 1
        var bestMatch = -1

        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= currentTimeMs) {
                bestMatch = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        
        return bestMatch
    }
}
