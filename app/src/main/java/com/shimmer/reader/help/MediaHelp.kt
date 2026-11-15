package com.shimmer.reader.help

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.shimmer.reader.R
import splitties.systemservices.audioManager

object MediaHelp {

    const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            or PlaybackStateCompat.ACTION_REWIND
            or PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_STOP
            or PlaybackStateCompat.ACTION_FAST_FORWARD
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SEEK_TO
            or PlaybackStateCompat.ACTION_SET_RATING
            or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            or PlaybackStateCompat.ACTION_PLAY_FROM_URI
            or PlaybackStateCompat.ACTION_PREPARE
            or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            or PlaybackStateCompat.ACTION_PREPARE_FROM_URI
            or PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            or PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED)

    fun buildAudioFocusRequestCompat(
        audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener
    ): AudioFocusRequestCompat {
        val mPlaybackAttributes = AudioAttributesCompat.Builder()
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mPlaybackAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }


    /**
     * @return 音频焦点
     */
    fun requestFocus(focusRequest: AudioFocusRequestCompat): Boolean {
        val request = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)
        return request == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * 常驻的那段静音音频。
     * 焦点被别的应用抢来抢去时 play() 会被反复调用，
     * 每次都 new 一个 MediaPlayer，前一个还开着就被丢掉，
     * 音频通道不停地开关，反而是额外的开销。
     */
    private var silentPlayer: MediaPlayer? = null

    /**
     * 播放静音音频,用来获取音频焦点
     */
    @Synchronized
    fun playSilentSound(mContext: Context) {
        kotlin.runCatching {
            // Stupid Android 8 "Oreo" hack to make media buttons work
            val player = silentPlayer ?: MediaPlayer.create(mContext, R.raw.silent_sound)
                ?.also { mp ->
                    mp.setOnErrorListener { p, _, _ ->
                        p.runCatching { release() }
                        if (silentPlayer === p) silentPlayer = null
                        true
                    }
                    silentPlayer = mp
                } ?: return
            if (!player.isPlaying) {
                player.seekTo(0)
                player.start()
            }
        }
    }

    /** 朗读服务退出时放掉，不留着占音频通道 */
    @Synchronized
    fun releaseSilentSound() {
        kotlin.runCatching { silentPlayer?.release() }
        silentPlayer = null
    }
}