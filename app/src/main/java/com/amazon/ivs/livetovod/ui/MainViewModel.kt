package com.amazon.ivs.livetovod.ui

import android.view.Surface
import android.view.TextureView
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import com.amazon.ivs.livetovod.BuildConfig
import com.amazon.ivs.livetovod.common.countDownTimer
import com.amazon.ivs.livetovod.common.launch
import com.amazon.ivs.livetovod.common.setListener
import com.amazon.ivs.livetovod.models.ErrorModel
import com.amazon.ivs.livetovod.models.ProgressUpdate
import com.amazon.ivs.livetovod.models.SizeModel
import com.amazon.ivs.livetovod.repository.Repository
import com.amazon.ivs.livetovod.repository.networking.models.MetadataResponse
import com.amazon.ivs.livetovod.repository.networking.models.RequestStatus
import com.amazonaws.ivs.player.MediaPlayer
import com.amazonaws.ivs.player.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.Date
import javax.inject.Inject

private const val BUFFER_TICK_DELAY_SIZE = 700L
private const val PAUSE_PLAY_INTERVAL = 30 * 1000L
private const val PROGRESS_UPDATE_DELAY = 1000L

@HiltViewModel
class MainViewModel @Inject constructor(private val repository: Repository) : ViewModel() {

    private var rawIsStreamPaused = false
    private var shouldSeekToDelay = false
    private var pausedTime = 0L
    private var livePlayer: MediaPlayer? = null
    private var livePlayerListener: Player.Listener? = null
    private var vodPlayer: MediaPlayer? = null
    private var vodPlayerListener: Player.Listener? = null
    private var _isLive = true

    private val _onLiveStreamSizeChanged = MutableStateFlow(SizeModel(0, 0))
    private val _onVodStreamSizeChanged = MutableStateFlow(SizeModel(0, 0))
    private val _onVodPlayerReady = MutableStateFlow(false)
    private val _onProgressChanged = MutableSharedFlow<ProgressUpdate>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _onStreamLoading = Channel<Boolean>()
    private val _onError = Channel<ErrorModel>()
    private val _onLiveStateChanged = Channel<Boolean>()
    private val _onBufferedPositionChanged = Channel<Int>()
    private val _isStreamPaused = Channel<Boolean>()
    private val playPauseTimer = countDownTimer(
        PAUSE_PLAY_INTERVAL, 1000,
        onFinish = {
            Timber.d("Pause interval finished")
            _isLive = false
        }
    )

    val onLiveStreamSizeChanged = _onLiveStreamSizeChanged.asStateFlow()
    val onVodStreamSizeChanged = _onVodStreamSizeChanged.asStateFlow()
    val onVodPlayerReady = _onVodPlayerReady.asStateFlow()
    val onProgressChanged = _onProgressChanged.asSharedFlow()
    val onError = _onError.receiveAsFlow()
    val onLiveStreamLoading = _onStreamLoading.receiveAsFlow()
    val onLiveStateChanged = _onLiveStateChanged.receiveAsFlow()
    val onBufferedPositionChanged = _onBufferedPositionChanged.receiveAsFlow()
    val isStreamPaused = _isStreamPaused.receiveAsFlow()
    val isLive get() = _isLive
    val canShowBackToLive get() = !rawIsStreamPaused

    fun getMetadata(liveTexture: TextureView, vodTexture: TextureView) = launch {
        repository.getMetadata().collect { response ->
            when (response.status) {
                RequestStatus.ERROR -> {
                    response.error?.run {
                        _onError.send(ErrorModel(code, errorDescription))
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
        } else {
            vodPlayer?.pause()
        }
        _onLiveStateChanged.trySend(_isLive)
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
        _isStreamPaused.trySend(false)
    }

    fun pause() {
        Timber.d("Pausing playback")
        if (_isLive) {
            pausedTime = Date().time
            playPauseTimer.start()
            shouldSeekToDelay = true
        }
        rawIsStreamPaused = true
        _isStreamPaused.trySend(true)
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
        _onStreamLoading.trySend(true)
        livePlayer = MediaPlayer.Builder(textureView.context).build()
        livePlayerListener = initPlayer(
            livePlayer!!,
            textureView,
            liveUrl,
            onSizeChanged = { size ->
                _onLiveStreamSizeChanged.update { size }
            },
            onPlaying = {
                _onLiveStateChanged.trySend(_isLive)
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
        _onStreamLoading.trySend(true)

        Timber.d("Initializing VOD player")
        vodPlayer = MediaPlayer.Builder(textureView.context).build()
        vodPlayerListener = initPlayer(
            vodPlayer!!,
            textureView,
            "${BuildConfig.STREAM_VOD_URL}/${masterKey}",
            onSizeChanged = { size ->
                _onVodStreamSizeChanged.update { size }
            },
            onReady = {
                _onVodPlayerReady.update { true }
            }
        )

        launch {
            while (vodPlayer != null) {
                delay(BUFFER_TICK_DELAY_SIZE)
                if (!isLive) {
                    vodPlayer?.run {
                        val bufferedPosition = bufferedPosition.toDouble() / duration.toDouble() * SEEKBAR_MAX_PROGRESS
                        _onBufferedPositionChanged.trySend(bufferedPosition.toInt())
                    }
                }
            }
        }

        launch {
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
                _onStreamLoading.trySend(state == Player.State.BUFFERING && livePlayer?.state != Player.State.PLAYING)
            },
            onError = { exception ->
                Timber.d("Error happened: $exception")
                _onError.trySend(ErrorModel(exception.code, exception.errorMessage))
            }
        )
        player.setSurface(Surface(textureView.surfaceTexture))
        player.load(uri.toUri())
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
