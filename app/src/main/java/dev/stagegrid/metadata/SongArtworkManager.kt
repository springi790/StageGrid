package dev.stagegrid.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dev.stagegrid.data.LibraryRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

class SongArtworkManager(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun replace(songId: String, uri: Uri): Result<Unit> = runCatching {
        val song = repository.getSong(songId) ?: error("Song not found")
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        require(mime.startsWith("image/")) { "The selected file is not an image." }
        val extension = when {
            "png" in mime -> "png"
            "webp" in mime -> "webp"
            else -> "jpg"
        }
        val root = File(context.filesDir, "library/$songId").apply { mkdirs() }
        val staged = File(root, ".artwork-staging.$extension")
        staged.delete()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected image.")
            var total = 0L
            input.use { raw ->
                BufferedInputStream(raw).use { buffered ->
                    BufferedOutputStream(FileOutputStream(staged)).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = buffered.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_ARTWORK_BYTES) { "Artwork exceeds the 8 MiB limit." }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            require(staged.length() > 0L) { "The selected image is empty." }
            require(BitmapFactory.decodeFile(staged.absolutePath) != null) { "The selected image could not be decoded." }

            val destination = File(root, "artwork.$extension")
            root.listFiles()?.filter {
                it.isFile && it.name.startsWith("artwork.") && it != staged
            }?.forEach { it.delete() }
            if (!staged.renameTo(destination)) {
                staged.copyTo(destination, overwrite = true)
                staged.delete()
            }
            repository.updateSong(song.copy(artworkPath = destination.absolutePath))
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 8L * 1024L * 1024L
    }
}
