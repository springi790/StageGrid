package dev.stagegrid.backup

enum class BackupStage {
    PREPARING,
    HASHING,
    WRITING,
    COPYING_ARCHIVE,
    VALIDATING,
    RESTORING_FILES,
    RESTORING_LIBRARY,
    COMPLETE,
}

data class BackupProgress(
    val percent: Int,
    val stage: BackupStage,
    val detail: String? = null,
) {
    val fraction: Float get() = percent.coerceIn(0, 100) / 100f
}

data class BackupResult(
    val fileName: String,
    val songs: Int,
    val setlists: Int,
    val bytes: Long,
)

data class RestoreResult(
    val songs: Int,
    val setlists: Int,
    val files: Int,
)
