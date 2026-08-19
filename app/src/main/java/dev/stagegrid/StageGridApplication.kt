package dev.stagegrid

import android.app.Application
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.AudioEngineController
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.data.StageGridDatabase
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.settings.AppSettingsRepository

class StageGridApplication : Application() {
    lateinit var database: StageGridDatabase
        private set
    lateinit var repository: LibraryRepository
        private set
    lateinit var importer: SongImporter
        private set
    lateinit var audioDevices: AudioDeviceManager
        private set
    lateinit var audio: AudioEngineController
        private set
    lateinit var settings: AppSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = StageGridDatabase.create(this)
        repository = LibraryRepository(database)
        importer = SongImporter(this, repository)
        audioDevices = AudioDeviceManager(this)
        audio = AudioEngineController(this, repository, NativeAudioEngine())
        settings = AppSettingsRepository(this)
    }
}
