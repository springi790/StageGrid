package dev.stagegrid.ui

internal object SetlistLiveNavigation {
    fun initialIndex(songIds: List<String>, loadedSongId: String?): Int {
        if (songIds.isEmpty()) return -1
        val existing = loadedSongId?.let(songIds::indexOf) ?: -1
        return existing.takeIf { it >= 0 } ?: 0
    }

    fun previousIndex(currentIndex: Int, size: Int): Int? =
        (currentIndex - 1).takeIf { it in 0 until size }

    fun nextIndex(currentIndex: Int, size: Int): Int? =
        (currentIndex + 1).takeIf { it in 0 until size }
}
