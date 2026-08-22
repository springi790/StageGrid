package dev.stagegrid

import android.app.Application
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.AudioEngineController
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.audio.RehearsalMixExporter
import dev.stagegrid.backup.LibraryBackupManager
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.data.StageGridDatabase
import dev.stagegrid.guide.GuideCueAnalyzer
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideReanalyzer
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.metadata.ArtworkBackfillManager
import dev.stagegrid.metadata.MetadataAwareSongImporter
import dev.stagegrid.metadata.SongMetadataEnricher
import dev.stagegrid.midi.MidiDeviceManager
import dev.stagegrid.session.PerformanceSessionStore
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File

class StageGridApplication : Application() {
    lateinit var database: StageGridDatabase
        private set
    lateinit var repository: LibraryRepository
        private set
    lateinit var importer: MetadataAwareSongImporter
        private set
    lateinit var metadataEnricher: SongMetadataEnricher
        private set
    lateinit var artworkBackfill: ArtworkBackfillManager
        private set
    lateinit var backupManager: LibraryBackupManager
        private set
    lateinit var audioDevices: AudioDeviceManager
        private set
    lateinit var midiDevices: MidiDeviceManager
        private set
    lateinit var nativeAudio: NativeAudioEngine
        private set
    lateinit var audio: AudioEngineController
        private set
    lateinit var rehearsalMixExporter: RehearsalMixExporter
        private set
    lateinit var settings: AppSettingsRepository
        private set
    lateinit var guidePacks: GuidePackManager
        private set
    lateinit var nativeGuideReanalyzer: NativeGuideReanalyzer
        private set
    lateinit var sessionStore: PerformanceSessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = StageGridDatabase.create(this)
        repository = LibraryRepository(database)
        settings = AppSettingsRepository(this)
        guidePacks = GuidePackManager(this)
        metadataEnricher = SongMetadataEnricher()
        GuideCueAnalyzer.configurePersistentCache(File(filesDir, "guide-cache/fingerprints-v1.bin"))
        val stableImporter = SongImporter(this, repository, guidePacks, settings)
        importer = MetadataAwareSongImporter(this, repository, settings, stableImporter, metadataEnricher)
        nativeGuideReanalyzer = NativeGuideReanalyzer(filesDir, repository, guidePacks)
        backupManager = LibraryBackupManager(this, repository)
        audioDevices = AudioDeviceManager(this)
        midiDevices = MidiDeviceManager(this).also { it.start() }
        nativeAudio = NativeAudioEngine()
        audio = AudioEngineController(this, repository, nativeAudio, guidePacks)
        rehearsalMixExporter = RehearsalMixExporter(this, nativeAudio)
        sessionStore = PerformanceSessionStore(filesDir)

        // Best-effort, non-blocking migration for songs imported before automatic artwork support.
        // It can only write SongEntity.artworkPath; musical metadata is never modified.
        artworkBackfill = ArtworkBackfillManager(this, repository, settings).also { it.start() }
    }
}
