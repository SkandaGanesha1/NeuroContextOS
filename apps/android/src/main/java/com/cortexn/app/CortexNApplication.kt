package com.cortexn.app

import android.app.Application
import timber.log.Timber

/**
 * Minimal Application class to satisfy manifest and centralize app-wide init.
 */
class CortexNApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("CortexNApplication initialized")
    }
}

