package dev.stagegrid.guide

import dev.stagegrid.debug.StageGridDebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges low-level fingerprint work into one high-level Native Guide progress session.
 *
 * Template preparation may already be running on a background worker when the user requests a
 * reanalysis. Tracking by absolute file path lets the active reanalysis observe that existing work
 * without launching another competing fingerprint pass.
 */
object NativeGuideProgressTracker {
    enum class Phase { TEMPLATES, REFERENCE }

    data class Snapshot(
        val phase: Phase,
        val fraction: Float,
        val currentFile: String,
        val completedTemplates: Int = 0,
        val totalTemplates: Int = 0,
    )

    private data class Session(
        val id: Long,
        val referencePath: String,
        val templatePaths: Set<String>,
        val callback: (Snapshot) -> Unit,
        val templateFractions: MutableMap<String, Float> = HashMap(),
        var lastTemplateLogBucket: Int = -1,
        var lastReferenceLogBucket: Int = -1,
    )

    private val serial = AtomicLong(0L)
    private val active = AtomicReference<Session?>(null)

    fun start(referenceFile: File, templateFiles: List<File>, callback: (Snapshot) -> Unit): Long {
        val id = serial.incrementAndGet()
        val session = Session(
            id = id,
            referencePath = referenceFile.absolutePath,
            templatePaths = templateFiles.map { it.absolutePath }.toSet(),
            callback = callback,
        )
        active.set(session)
        StageGridDebugLog.state(
            "NATIVE_GUIDE",
            "PROGRESS_SESSION_START id=$id templates=${session.templatePaths.size} reference=${referenceFile.name}",
        )
        return id
    }

    fun stop(id: Long) {
        val current = active.get() ?: return
        if (current.id != id) return
        if (active.compareAndSet(current, null)) {
            StageGridDebugLog.state("NATIVE_GUIDE", "PROGRESS_SESSION_STOP id=$id")
        }
    }

    fun onFingerprintOpen(file: File) {
        val session = active.get() ?: return
        val path = file.absolutePath
        when {
            path == session.referencePath -> session.callback(
                Snapshot(Phase.REFERENCE, 0f, file.name),
            )
            path in session.templatePaths -> {
                synchronized(session) {
                    session.templateFractions.putIfAbsent(path, 0f)
                    publishTemplateSnapshotLocked(session, file.name)
                }
            }
        }
    }

    fun onFingerprintProgress(file: File, fraction: Float) {
        val session = active.get() ?: return
        val path = file.absolutePath
        val safe = fraction.coerceIn(0f, 1f)
        when {
            path == session.referencePath -> {
                val bucket = (safe * 20f).toInt().coerceIn(0, 20)
                synchronized(session) {
                    if (bucket != session.lastReferenceLogBucket) {
                        session.lastReferenceLogBucket = bucket
                        StageGridDebugLog.state(
                            "NATIVE_GUIDE",
                            "REFERENCE_PROGRESS percent=${(safe * 100f).toInt().coerceIn(0, 100)} file=${file.name}",
                        )
                    }
                }
                session.callback(Snapshot(Phase.REFERENCE, safe, file.name))
            }
            path in session.templatePaths -> {
                synchronized(session) {
                    val previous = session.templateFractions[path] ?: 0f
                    if (safe > previous) session.templateFractions[path] = safe
                    publishTemplateSnapshotLocked(session, file.name)
                }
            }
        }
    }

    fun onFingerprintDone(file: File) {
        val session = active.get() ?: return
        val path = file.absolutePath
        when {
            path == session.referencePath -> session.callback(
                Snapshot(Phase.REFERENCE, 1f, file.name),
            )
            path in session.templatePaths -> {
                synchronized(session) {
                    session.templateFractions[path] = 1f
                    publishTemplateSnapshotLocked(session, file.name)
                }
            }
        }
    }

    private fun publishTemplateSnapshotLocked(session: Session, currentFile: String) {
        val total = session.templatePaths.size.coerceAtLeast(1)
        var sum = 0.0
        var completed = 0
        for (path in session.templatePaths) {
            val fraction = session.templateFractions[path] ?: 0f
            sum += fraction.toDouble()
            if (fraction >= 1f) completed++
        }
        val overall = (sum / total.toDouble()).toFloat().coerceIn(0f, 1f)
        val bucket = (overall * 20f).toInt().coerceIn(0, 20)
        if (bucket != session.lastTemplateLogBucket) {
            session.lastTemplateLogBucket = bucket
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "TEMPLATE_CACHE_PROGRESS percent=${(overall * 100f).toInt().coerceIn(0, 100)} completed=$completed/$total current=$currentFile",
            )
        }
        session.callback(
            Snapshot(
                phase = Phase.TEMPLATES,
                fraction = overall,
                currentFile = currentFile,
                completedTemplates = completed,
                totalTemplates = total,
            ),
        )
    }
}
