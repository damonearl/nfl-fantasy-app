package com.damonearl.fantasymultiview.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.damonearl.fantasymultiview.streams.StreamSource
import okhttp3.OkHttpClient

sealed interface TileStatus {
    data object Loading : TileStatus
    data object Playing : TileStatus
    data class Error(val message: String) : TileStatus
}

/**
 * One ExoPlayer per on-screen game. Only the focused tile has sound, and unfocused tiles are capped
 * at lower resolutions so several decoders can run at once on modest Google TV hardware.
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class PlayerPool(private val context: Context, private val http: OkHttpClient) {

    private class Entry(val player: ExoPlayer, val source: StreamSource, val listener: Player.Listener)

    private val entries = mutableMapOf<String, Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var audioGameId: String? = null
    private var expandedGameId: String? = null
    private var paused = false

    /** Observable per-game playback status for the tile overlays. */
    val status = mutableStateMapOf<String, TileStatus>()

    fun player(gameId: String): ExoPlayer? = entries[gameId]?.player

    /** Makes the pool match [wanted]: starts new streams, swaps changed ones, releases the rest. */
    fun sync(wanted: Map<String, StreamSource>) {
        (entries.keys - wanted.keys).forEach(::release)
        wanted.forEach { (gameId, source) ->
            val existing = entries[gameId]
            if (existing?.source != source) {
                existing?.let { release(gameId) }
                entries[gameId] = create(gameId, source)
            }
        }
        applyAudio()
        applyQuality()
    }

    fun setAudioFocus(gameId: String?) {
        audioGameId = gameId
        applyAudio()
    }

    fun setExpanded(gameId: String?) {
        expandedGameId = gameId
        applyQuality()
    }

    fun pauseAll() {
        paused = true
        entries.values.forEach { it.player.pause() }
    }

    fun resumeAll() {
        paused = false
        entries.values.forEach {
            // Jump back to the live edge rather than resuming minutes behind.
            if (it.player.isCurrentMediaItemLive) it.player.seekToDefaultPosition()
            it.player.play()
        }
    }

    fun releaseAll() = entries.keys.toList().forEach(::release)

    private fun create(gameId: String, source: StreamSource): Entry {
        val dataSource = OkHttpDataSource.Factory(http).setDefaultRequestProperties(source.headers)
        source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { dataSource.setUserAgent(it.value) }

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status[gameId] = TileStatus.Playing
                if (state == Player.STATE_BUFFERING && status[gameId] !is TileStatus.Error) status[gameId] = TileStatus.Loading
            }

            override fun onPlayerError(error: PlaybackException) {
                status[gameId] = TileStatus.Error(describe(error))
                handler.postDelayed({
                    if (entries[gameId]?.player !== player) return@postDelayed
                    player.seekToDefaultPosition()
                    player.prepare()
                }, RETRY_MS)
            }
        }
        player.addListener(listener)
        player.volume = 0f
        player.setMediaItem(MediaItem.fromUri(source.url))
        player.prepare()
        player.playWhenReady = !paused
        status[gameId] = TileStatus.Loading
        return Entry(player, source, listener)
    }

    private fun release(gameId: String) {
        entries.remove(gameId)?.let {
            it.player.removeListener(it.listener)
            it.player.release()
        }
        status.remove(gameId)
    }

    private fun applyAudio() {
        val target = audioGameId?.takeIf { it in entries } ?: entries.keys.firstOrNull()
        entries.forEach { (id, e) -> e.player.volume = if (id == target) 1f else 0f }
    }

    private fun applyQuality() {
        val count = entries.size
        entries.forEach { (id, e) ->
            val builder = e.player.trackSelectionParameters.buildUpon()
            when {
                count <= 1 || id == expandedGameId -> builder.clearVideoSizeConstraints()
                expandedGameId != null -> builder.setMaxVideoSize(640, 360)
                count == 2 -> builder.setMaxVideoSize(1920, 1080)
                else -> builder.setMaxVideoSize(1280, 720)
            }
            e.player.trackSelectionParameters = builder.build()
        }
    }

    private fun describe(error: PlaybackException) = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Stream unavailable (tuner busy or link expired)"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach stream"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This device can't decode the stream (try a transcode profile)"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
