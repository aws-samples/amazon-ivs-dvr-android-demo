package com.amazon.ivs.livetovod.ui

import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.ViewModel
import com.amazon.ivs.livetovod.BuildConfig
import com.amazon.ivs.livetovod.common.ConsumableSharedFlow
import com.amazon.ivs.livetovod.common.countDownTimer
import com.amazon.ivs.livetovod.common.launchIO
import com.amazon.ivs.livetovod.common.setListener
import com.amazon.ivs.livetovod.models.ErrorModel
import com.amazon.ivs.livetovod.models.ProgressUpdate
import com.amazon.ivs.livetovod.models.SizeModel
import com.amazon.ivs.livetovod.repository.Repository
import com.amazon.ivs.livetovod.repository.networking.NetworkClient
import com.amazon.ivs.livetovod.repository.networking.models.MetadataResponse
import com.amazon.ivs.livetovod.repository.networking.models.RequestStatus
import com.amazonaws.ivs.player.MediaPlayer
import com.amazonaws.ivs.player.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.*

private const val BUFFER_TICK_DELAY_SIZE = 700L
private const val PAUSE_PLAY_INTERVAL = 30 * 1000L
private const val PROGRESS_UPDATE_DELAY = 1000L

class MainViewModel : ViewModel() {

    private var rawIsStreamPaused = false
    private var shouldSeekToDelay = false
    private var pausedTime = 0L
    private var livePlayer: MediaPlayer? = null
    private var livePlayerListener: Player.Listener? = null
    private var vodPlayer: MediaPlayer? = null
    private var vodPlayerListener: Player.Listener? = null
    private var _isLive = true

    private val _onLiveStreamSizeChanged = ConsumableSharedFlow<SizeModel>(canReplay = true)
    private val _onVodStreamSizeChanged = ConsumableSharedFlow<SizeModel>(canReplay = true)
    private val _onStreamLoading = ConsumableSharedFlow<Boolean>()
    private val _onError = ConsumableSharedFlow<ErrorModel>()
    private val _onLiveStateChanged = ConsumableSharedFlow<Boolean>()
    private val _onProgressChanged = ConsumableSharedFlow<ProgressUpdate>()
    private val _onBufferedPositionChanged = ConsumableSharedFlow<Int>()
    private val _isStreamPaused = ConsumableSharedFlow<Boolean>()
    private val _onVodPlayerReady = MutableStateFlow(false)
    private val repository = Repository(NetworkClient())
    private val playPauseTimer = countDownTimer(
        PAUSE_PLAY_INTERVAL, 1000,
        onFinish = {
            Timber.d("Pause interval finished")
            _isLive = false
        }
    )

    val onError = _onError.asSharedFlow()
    val onLiveStreamSizeChanged = _onLiveStreamSizeChanged.asSharedFlow()
    val onLiveStreamLoading = _onStreamLoading.asSharedFlow()
    val onVodStreamSizeChanged = _onVodStreamSizeChanged.asSharedFlow()
    val onLiveStateChanged = _onLiveStateChanged.asSharedFlow()
    val onProgressChanged = _onProgressChanged.asSharedFlow()
    val onBufferedPositionChanged = _onBufferedPositionChanged.asSharedFlow()
    val isStreamPaused = _isStreamPaused.asSharedFlow()
    val onVodPlayerReady = _onVodPlayerReady.asSharedFlow()
    val isLive get() = _isLive
    val canShowBackToLive get() = !rawIsStreamPaused

    fun getMetadata(liveTexture: TextureView, vodTexture: TextureView) = launchIO {
        repository.getMetadata().collect { response ->
            when (response.status) {
                RequestStatus.ERROR -> {
                    response.error?.run {
                        _onError.tryEmit(ErrorModel(code, errorDescription))
                    }
                }
                RequestStatus.SUCCESS -> {
                    val metaData = response.data as MetadataResponse
                    initLivePlayer(liveTexture, metaData.livePlaybackUrl)
                    initVodPlayer(vodTexture, metaData.masterKey)
                }
            }
        }
    }

    fun switchAndPlay(switchToLive: Boolean) {
        Timber.d("Switch: isLive[$isLive]")
        _isLive = switchToLive
        if (!_isLive) {
            livePlayer?.pause()
            _onLiveStateChanged.tryEmit(false)
        } else {
            vodPlayer?.pause()
        }
        if (!rawIsStreamPaused) play()
    }

    fun quickSeek(time: Int) {
        vodPlayer?.run {
            val base = if (_isLive) duration else position
            when {
                time > 0 && isLive -> return
                time < 0 -> if (_isLive) switchAndPlay(false)
            }
            shouldSeekToDelay = false
            seekTo(base + time)
            _onProgressChanged.tryEmit(ProgressUpdate(((base + time).toDouble() / duration.toDouble() * SEEKBAR_MAX_PROGRESS).toLong()))
            if (base + time > duration) switchAndPlay(true)
        }
    }

    fun seek(progress: Long) {
        vodPlayer?.seekTo(getTime(progress))
        shouldSeekToDelay = false
        if (isLive) switchAndPlay(false)
    }

    fun getTimeDiff(progress: Long): Long {
        vodPlayer?.duration?.let { duration ->
            return duration - getTime(progress)
        }
        return 0
    }

    fun play() {
        playPauseTimer.cancel()
        if (_isLive) {
            Timber.d("Starting live playback")
            livePlayer?.play()
            _onProgressChanged.tryEmit(ProgressUpdate(SEEKBAR_MAX_PROGRESS))
        } else {
            if (shouldSeekToDelay) {
                vodPlayer?.seekTo(vodPlayer?.duration!! - (Date().time - pausedTime))
                shouldSeekToDelay = false
            }

            Timber.d("Starting vod playback")
            vodPlayer?.play()
        }
        rawIsStreamPaused = false
        _isStreamPaused.tryEmit(rawIsStreamPaused)
    }

    fun pause() {
        Timber.d("Pausing playback")
        if (_isLive) {
            pausedTime = Date().time
            playPauseTimer.start()
            shouldSeekToDelay = true
        }
        rawIsStreamPaused = true
        _isStreamPaused.tryEmit(rawIsStreamPaused)
        livePlayer?.pause()
        vodPlayer?.pause()
    }

    fun playPauseButtonClicked() {
        if (rawIsStreamPaused) play() else pause()
    }

    private fun initLivePlayer(textureView: TextureView, liveUrl: String) {
        if (livePlayer != null) {
            return
        }
        _onStreamLoading.tryEmit(true)
        livePlayer = MediaPlayer(textureView.context)
        livePlayerListener = initPlayer(
            livePlayer!!,
            textureView,
            liveUrl,
            onSizeChanged = { size ->
                _onLiveStreamSizeChanged.tryEmit(size)
            },
            onPlaying = {
                _onLiveStateChanged.tryEmit(_isLive)
            }
        )
        livePlayer?.play()
        Timber.d("Live stream started")
    }

    private fun initVodPlayer(textureView: TextureView, masterKey: String) {
        if (vodPlayer != null) {
            vodPlayer?.setSurface(Surface(textureView.surfaceTexture))
            return
        }
        _onStreamLoading.tryEmit(true)

        Timber.d("Initializing VOD player")
        vodPlayer = MediaPlayer(textureView.context)
        vodPlayerListener = initPlayer(
            vodPlayer!!,
            textureView,
            "${BuildConfig.STREAM_VOD_URL}/${masterKey}",
            onSizeChanged = { size ->
                _onVodStreamSizeChanged.tryEmit(size)
            },
            onReady = {
                _onVodPlayerReady.tryEmit(true)
            }
        )

        launchIO {
            while (vodPlayer != null) {
                delay(BUFFER_TICK_DELAY_SIZE)
                vodPlayer?.run {
                    val bufferedPosition =
                        bufferedPosition.toDouble() / duration.toDouble() * SEEKBAR_MAX_PROGRESS
                    _onBufferedPositionChanged.tryEmit(bufferedPosition.toInt())
                }
            }
        }

        launchIO {
            while (true) {
                delay(PROGRESS_UPDATE_DELAY)
                if (!isLive && !rawIsStreamPaused) {
                    vodPlayer?.run {
                        val progress = (position.toFloat() / duration.toFloat() * SEEKBAR_MAX_PROGRESS)
                        _onProgressChanged.tryEmit(
                            ProgressUpdate(
                                progress.toLong(),
                                duration - position
                            )
                        )
                    }
                }
            }
        }
    }

    private fun initPlayer(
        player: MediaPlayer,
        textureView: TextureView,
        uri: String,
        onSizeChanged: (SizeModel) -> Unit,
        onReady: () -> Unit = {},
        onPlaying: () -> Unit = {}
    ): Player.Listener {
        val listener = player.setListener(
            onVideoSizeChanged = { width, height ->
                Timber.d("Video size changed: $width $height")
                onSizeChanged(SizeModel(width, height))
            },
            onStateChanged = { state ->
                Timber.d("State changed: $state")
                when (state) {
                    Player.State.READY -> onReady()
                    Player.State.PLAYING -> onPlaying()
                    else -> { /* Ignored */ }
                }
                _onStreamLoading.tryEmit(state == Player.State.BUFFERING && livePlayer?.state != Player.State.PLAYING)
            },
            onError = { exception ->
                Timber.d("Error happened: $exception")
                _onError.tryEmit(ErrorModel(exception.code, exception.errorMessage))
            }
        )
        player.setSurface(Surface(textureView.surfaceTexture))
        player.load(Uri.parse(uri))
        return listener
    }

    private fun getTime(progress: Long): Long {
        // Additional time variable for 0 progress, because player at 0 time jumps almost to live.
        val additionalTime = if (progress == 0L) 1 else 0
        vodPlayer?.duration?.let { duration ->
            return duration * progress / SEEKBAR_MAX_PROGRESS + additionalTime
        }
        return 0
    }
}
