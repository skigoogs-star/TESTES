package com.deckrec

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.deckrec.audio.RecordingEngine
import com.deckrec.data.RecordingStore
import com.deckrec.data.SettingsStore
import com.deckrec.usb.UsbAudioScanner

/**
 * Holds the pieces that must outlive any one screen.
 *
 * The recording engine lives here rather than in the service so a set survives the service being
 * rebound, the activity being destroyed, or the user rotating the phone mid-mix. The service's job
 * is to keep the process alive and put controls in the shade, not to own the audio.
 */
class DeckRecApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var recordingStore: RecordingStore
        private set

    lateinit var usbAudioScanner: UsbAudioScanner
        private set

    lateinit var recordingEngine: RecordingEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        settingsStore = SettingsStore(this)
        recordingStore = RecordingStore(this).apply { refresh() }
        usbAudioScanner = UsbAudioScanner(this).apply { start() }
        recordingEngine = RecordingEngine(this, usbAudioScanner, recordingStore)

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            getString(R.string.channel_recording_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_recording_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val RECORDING_CHANNEL_ID = "recording"

        private lateinit var instance: DeckRecApp

        fun from(context: Context): DeckRecApp =
            context.applicationContext as? DeckRecApp ?: instance
    }
}
