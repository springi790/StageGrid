package dev.stagegrid

import android.app.Application
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.AudioEngineController
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.backup.LibraryBackupManager
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.data.StageGridDatabase
import dev.stagegrid.guide.GuideCueAnalyzer
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideReanalyzer
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.session.PerformanceSessionStore
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File

class StageGridApplication : Application() {
    lateinit var database: StageGridDatabase
        private set
    lateinit var repository: LibraryRepository
        private set
    lateinit var importer: SongImporter
        private set
    lateinit var backupManager: LibraryBackupManager
        private set
    lateinit var audioDevices: AudioDeviceManager
        private set
    lateinit var nativeAudio: NativeAudioEngine
        private set
    lateinit var audio: AudioEngineController
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
        GuideCueAnalyzer.configurePersistentCache(File(filesDir, "guide-cache/fingerprints-v1.bin"))
        importer = SongImporter(this, repository, guidePacks, settings)
        nativeGuideReanalyzer = NativeGuideReanalyzer(filesDir, repository, guidePacks)
        backupManager = LibraryBackupManager(this, repository)
        audioDevices = AudioDeviceManager(this)
        nativeAudio = NativeAudioEngine()
        audio = AudioEngineController(this, repository, nativeAudio, guidePacks)
        sessionStore = PerformanceSessionStore(filesDir)
    }
}
