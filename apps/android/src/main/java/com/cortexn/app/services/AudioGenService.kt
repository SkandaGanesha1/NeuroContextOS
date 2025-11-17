package com.cortexn.app.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * Placeholder foreground service for audio generation jobs.
 */
class AudioGenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("AudioGenService started (stub)")
        stopSelf()
        return START_NOT_STICKY
    }
}
