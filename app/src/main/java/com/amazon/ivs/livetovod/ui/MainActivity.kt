package com.amazon.ivs.livetovod.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import com.amazon.ivs.livetovod.R
import com.amazon.ivs.livetovod.common.CONTROLS_COUNTDOWN_TIME
import com.amazon.ivs.livetovod.common.animateVisibility
import com.amazon.ivs.livetovod.common.clearAllAnchors
import com.amazon.ivs.livetovod.common.collect
import com.amazon.ivs.livetovod.common.countDownTimer
import com.amazon.ivs.livetovod.common.onDrawn
import com.amazon.ivs.livetovod.common.onProgress
import com.amazon.ivs.livetovod.common.onReady
import com.amazon.ivs.livetovod.common.scaleToFit
import com.amazon.ivs.livetovod.common.setXIfNotOutOfBounds
import com.amazon.ivs.livetovod.common.showSnackBar
import com.amazon.ivs.livetovod.common.toFormattedTime
import com.amazon.ivs.livetovod.databinding.ActivityMainBinding
import com.amazon.ivs.livetovod.models.SeekInterval
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import kotlin.properties.Delegates

const val SEEKBAR_MAX_PROGRESS = 1000L

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var allowThumbUpdate = true
    private var screenWidth = 0
    private var seekbarWidth = 0
    private var seekbarTickPixels: Float = 0f

    private val viewModel by viewModels<MainViewModel>()
    private val seekbarPadding by lazy { resources.getDimension(R.dimen.padding_small) }

    private var isLiveInitialized = false
    private var areTexturesInitialized by Delegates.observable(false) { _, _, newValue ->
        if (newValue) viewModel.getMetadata(binding.streamLive, binding.streamVod)
    }

    private val controlsTimer = countDownTimer(CONTROLS_COUNTDOWN_TIME, 1000,
        onFinish = {
            binding.playerControls.animateVisibility(false)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updatePlayerConstraints()
        controlsTimer.start()
        binding.isPlayButtonVisible = true
        binding.isLive = true
        binding.isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        binding.streamLive.onReady {
            Timber.d("Live surface ready")
            isLiveInitialized = true
            areTexturesInitialized = binding.isVodInitialized ?: false
        }
        binding.streamVod.onReady {
            Timber.d("Vod surface ready")
            binding.isVodInitialized = true
            areTexturesInitialized = isLiveInitialized
        }
        binding.streamSeek.onProgress(
            onProgressChanged = { newProgress ->
                adjustThumbnailCoordinates(newProgress)
            },
            onStarted = {
                binding.isLive = false
                binding.isDragging = true
                allowThumbUpdate = false
            },
            onReleased = { progress ->
                binding.isDragging = false
                allowThumbUpdate = true
                if (progress == SEEKBAR_MAX_PROGRESS) {
                    goLive()
                } else {
                    seek(progress)
                }
            }
        )
        binding.playPauseButton.setOnClickListener {
            viewModel.playPauseButtonClicked()
        }

        binding.forwardButton.setOnClickListener {
            viewModel.quickSeek(SeekInterval.FORWARD.time)
        }

        binding.backwardButton.setOnClickListener {
            viewModel.quickSeek(SeekInterval.BACKWARD.time)
        }

        binding.backToLive.setOnClickListener {
            binding.vodTime.text =
                getString(R.string.clock_template, 0L.toFormattedTime())
            goLive()
        }

        collect(viewModel.onLiveStreamLoading) { isLoading ->
            binding.streamBuffering.animateVisibility(isLoading)
        }

        collect(viewModel.onLiveStreamSizeChanged) { size ->
            binding.streamLive.scaleToFit(size)
        }

        collect(viewModel.onVodStreamSizeChanged) { size ->
            binding.streamVod.scaleToFit(size)
        }

        collect(viewModel.onError) { error ->
            binding.streamHolder.showSnackBar(error.message)
        }

        collect(viewModel.isStreamPaused) { isPaused ->
            binding.streamSeek.progressTintList =
                ColorStateList.valueOf(
                    if (isPaused) {
                        Color.TRANSPARENT
                    } else {
                        ResourcesCompat.getColor(resources, R.color.primary_red_color, null)
                    }
                )
            Timber.d("Progress is - ${binding.streamSeek.progress}")
            binding.streamSeek.secondaryProgress = binding.streamSeek.progress
            binding.isStreamPaused = isPaused
            binding.isPlayButtonVisible = isPaused
            binding.backToLive.animateVisibility(!isPaused && !viewModel.isLive)
        }

        collect(viewModel.onLiveStateChanged) { isLive ->
            binding.isLive = isLive
            if (isLive) {
                binding.streamSeek.secondaryProgress = SEEKBAR_MAX_PROGRESS.toInt()
            }
        }

        collect(viewModel.onProgressChanged) { progressUpdate ->
            if (allowThumbUpdate) {
                binding.vodProgress = progressUpdate.progress.toInt()
                progressUpdate.vodTime?.let { time ->
                    binding.vodTime.text = getString(R.string.clock_template, time.toFormattedTime())
                }
            }
        }

        collect(viewModel.onBufferedPositionChanged) { bufferedPosition ->
            binding.streamSeek.secondaryProgress = bufferedPosition
        }

        collect(viewModel.onVodPlayerReady) { isPlaying ->
            Timber.d("Vod playing: $isPlaying")
            binding.playPauseButton.isEnabled = isPlaying
            binding.streamSeek.isEnabled = isPlaying
            binding.backwardButton.isEnabled = isPlaying
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        super.dispatchTouchEvent(event)
        when (event?.action) {
            MotionEvent.ACTION_UP -> controlsTimer.start()
            MotionEvent.ACTION_DOWN -> {
                controlsTimer.cancel()
                binding.playerControls.animateVisibility(true)
            }
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        updatePlayerConstraints()
        binding.root.doOnLayout {
            binding.streamLive.scaleToFit(viewModel.onLiveStreamSizeChanged.value)
            binding.streamVod.scaleToFit(viewModel.onVodStreamSizeChanged.value)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.pause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.play()
    }

    private fun goLive() {
        if (!viewModel.isLive) {
            viewModel.switchAndPlay(switchToLive = true)
        }
    }

    private fun updatePlayerConstraints() = with(binding) {
        val seekAndPauseWrapperParams = seekAndPauseWrapper.layoutParams as ConstraintLayout.LayoutParams
        val playPauseButtonParams = playPauseButton.layoutParams as LinearLayout.LayoutParams

        val streamSeekParams = streamSeek.layoutParams as ConstraintLayout.LayoutParams
        seekAndPauseWrapperParams.clearAllAnchors()
        streamSeekParams.clearAllAnchors()
        binding.root.onDrawn {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                seekAndPauseWrapperParams.startToStart = playerControls.id
                seekAndPauseWrapperParams.topToBottom = backToLive.id
                seekAndPauseWrapperParams.endToStart = streamSeek.id
                seekAndPauseWrapperParams.bottomToBottom = playerControls.id
                seekAndPauseWrapperParams.marginStart = resources.getDimension(R.dimen.margin_normal).toInt()
                seekAndPauseWrapperParams.bottomMargin = resources.getDimension(R.dimen.margin_normal).toInt()

                streamSeekParams.startToEnd = seekAndPauseWrapper.id
                streamSeekParams.topToTop = seekAndPauseWrapper.id
                streamSeekParams.endToEnd = playerControls.id
                streamSeekParams.bottomToBottom = seekAndPauseWrapper.id

                playPauseButtonParams.marginStart = 0
                playPauseButtonParams.marginEnd = 0
            } else {
                seekAndPauseWrapperParams.startToStart = playerControls.id
                seekAndPauseWrapperParams.endToEnd = playerControls.id
                seekAndPauseWrapperParams.topToBottom = streamSeek.id
                seekAndPauseWrapperParams.bottomToBottom = playerControls.id
                seekAndPauseWrapperParams.marginStart = 0
                seekAndPauseWrapperParams.bottomMargin = resources.getDimension(R.dimen.margin_huge).toInt()

                streamSeekParams.endToEnd = playerControls.id
                streamSeekParams.startToStart = playerControls.id
                streamSeekParams.topToBottom = vodTimeWrapper.id
                streamSeekParams.bottomToTop = seekAndPauseWrapper.id

                playPauseButtonParams.marginStart = resources.getDimension(R.dimen.margin_huge).toInt()
                playPauseButtonParams.marginEnd = resources.getDimension(R.dimen.margin_huge).toInt()
            }

            seekAndPauseWrapper.layoutParams = seekAndPauseWrapperParams
            streamSeek.layoutParams = streamSeekParams
            playPauseButton.layoutParams = playPauseButtonParams
            playPauseButton.invalidate()
            streamSeek.invalidate()
            streamSeek.doOnLayout {
                seekbarWidth = binding.streamSeek.measuredWidth
                seekbarTickPixels = (screenWidth - (screenWidth - (seekbarWidth - seekbarPadding * 2))) / 1000
                screenWidth = Resources.getSystem().displayMetrics.widthPixels
                binding.vodProgress?.let { progress ->
                    adjustThumbnailCoordinates(progress)
                }
            }
        }
    }

    private fun seek(progress: Long) {
        viewModel.seek(progress)
    }

    private fun adjustThumbnailCoordinates(progress: Int) = with(binding) {
        val thumbXCenter = streamSeek.x + seekbarPadding + progress * seekbarTickPixels
        binding.vodTime.animateVisibility(progress.toLong() != SEEKBAR_MAX_PROGRESS)
        binding.backToLive.animateVisibility(
            viewModel.canShowBackToLive && progress.toLong() != SEEKBAR_MAX_PROGRESS
        )
        if (!allowThumbUpdate) {
            binding.vodTime.text =
                getString(R.string.clock_template, viewModel.getTimeDiff(progress.toLong()).toFormattedTime())
        }
        binding.vodTime.invalidate()
        binding.vodTime.doOnLayout {
            binding.vodTime.setXIfNotOutOfBounds(thumbXCenter, screenWidth, progress)
        }
    }
}
