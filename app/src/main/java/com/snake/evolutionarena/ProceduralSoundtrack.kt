package com.snake.evolutionarena

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Lightweight original loop generated on-device; no licensed audio assets are bundled. */
class ProceduralSoundtrack(modeId: String) {
    private val sampleRate = 22_050
    private val track: AudioTrack?

    init {
        val bpm = when (modeId) {
            "blitz" -> 142f
            "expedition" -> 92f
            else -> 112f
        }
        val seconds = 16f * 60f / bpm
        val sampleCount = (sampleRate * seconds).toInt().coerceAtLeast(sampleRate * 4)
        val samples = ShortArray(sampleCount)
        val root = when (modeId) {
            "blitz" -> 55.0
            "expedition" -> 43.65
            else -> 49.0
        }
        val melody = when (modeId) {
            "blitz" -> doubleArrayOf(1.0, 1.5, 2.0, 1.78, 1.0, 1.33, 1.5, 2.0)
            "expedition" -> doubleArrayOf(1.0, 1.19, 1.5, 1.33, 1.0, 1.5, 1.78, 1.33)
            else -> doubleArrayOf(1.0, 1.33, 1.5, 1.19, 1.0, 1.5, 2.0, 1.78)
        }
        val beatSeconds = 60.0 / bpm
        for (index in samples.indices) {
            val time = index.toDouble() / sampleRate
            val beat = time / beatSeconds
            val beatPhase = beat - beat.toInt()
            val step = beat.toInt() % melody.size
            val bass = sin(2.0 * PI * root * time) * .20
            val pad = sin(2.0 * PI * root * .5 * time) * .08 +
                sin(2.0 * PI * root * .75 * time) * .055
            val pulseEnvelope = (1.0 - beatPhase).coerceIn(0.0, 1.0)
            val leadFrequency = root * 2.0 * melody[step]
            val lead = sin(2.0 * PI * leadFrequency * time) * pulseEnvelope * .095
            val kickPhase = beatPhase * beatSeconds
            val kick = sin(2.0 * PI * (62.0 - kickPhase * 32.0) * kickPhase) *
                (1.0 - kickPhase * 8.0).coerceIn(0.0, 1.0) * .24
            val fadeSamples = (sampleRate * .025).toInt()
            val edgeFade = when {
                index < fadeSamples -> index.toDouble() / fadeSamples
                index >= sampleCount - fadeSamples -> (sampleCount - index - 1).toDouble() / fadeSamples
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val mixed = ((bass + pad + lead + kick) * edgeFade).coerceIn(-.88, .88)
            samples[index] = (mixed * Short.MAX_VALUE).toInt().toShort()
        }

        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
                .also { audio ->
                    audio.write(samples, 0, samples.size)
                    audio.setLoopPoints(0, samples.size, -1)
                    audio.setVolume(.14f)
                }
        }.getOrNull()
    }

    fun play() {
        val audio = track ?: return
        if (audio.state == AudioTrack.STATE_INITIALIZED && audio.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { audio.play() }
        }
    }

    fun pause() {
        val audio = track ?: return
        if (audio.playState == AudioTrack.PLAYSTATE_PLAYING) runCatching { audio.pause() }
    }

    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }
}
