package com.cortexn.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import timber.log.Timber

class AudioFocus(private val context: Context) {
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    
    fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleFocusChange(focusChange)
                }
                .build()
            
            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange -> handleFocusChange(focusChange) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
    
    fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { }
        }
    }
    
    private fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.d("Audio focus gained")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.d("Audio focus lost")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.d("Audio focus lost (transient)")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Timber.d("Audio focus lost (can duck)")
            }
        }
    }
}
