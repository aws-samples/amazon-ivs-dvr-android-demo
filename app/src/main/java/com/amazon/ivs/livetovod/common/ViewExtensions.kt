package com.amazon.ivs.livetovod.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.SurfaceTexture
import android.os.CountDownTimer
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.amazon.ivs.livetovod.models.SizeModel
import com.amazon.ivs.livetovod.ui.SEEKBAR_MAX_PROGRESS
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlin.math.roundToInt

const val ALPHA_VISIBLE = 1F
const val ALPHA_GONE = 0F

fun View.animateVisibility(visible: Boolean) {
    if ((isVisible && visible) || (isGone && !visible) || (isInvisible && !visible)) return
    setVisible(true)
    alpha = if (visible) ALPHA_GONE else ALPHA_VISIBLE
    animate().setDuration(250L).alpha(if (visible) ALPHA_VISIBLE else ALPHA_GONE)
        .setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                setVisible(visible, View.INVISIBLE)
            }
        }).start()
}

fun SeekBar.onProgress(onProgressChanged: (Int) -> Unit, onStarted: () -> Unit, onReleased: (Long) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, p2: Boolean) {
            onProgressChanged(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
            onStarted()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            onReleased(seekBar?.progress?.toLong() ?: 0L)
        }
    })
}

fun TextureView.onReady(onReady: (surface: Surface) -> Unit) {
    if (surfaceTexture != null) {
        onReady(Surface(surfaceTexture))
        return
    }
    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            surfaceTextureListener = null
            onReady(Surface(surfaceTexture))
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            /* Ignored */
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            /* Ignored */
        }
    }
}

fun FrameLayout.showSnackBar(message: String) =
    Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()

fun View.onDrawn(onDrawn: () -> Unit) {
    invalidate()
    requestLayout()
    doOnLayout { onDrawn() }
}

fun ConstraintLayout.LayoutParams.clearAllAnchors() {
    startToStart = ConstraintLayout.LayoutParams.UNSET
    startToEnd = ConstraintLayout.LayoutParams.UNSET
    topToTop = ConstraintLayout.LayoutParams.UNSET
    topToBottom = ConstraintLayout.LayoutParams.UNSET
    endToEnd = ConstraintLayout.LayoutParams.UNSET
    endToStart = ConstraintLayout.LayoutParams.UNSET
    bottomToBottom = ConstraintLayout.LayoutParams.UNSET
    bottomToTop = ConstraintLayout.LayoutParams.UNSET
    matchConstraintPercentHeight = 1f
    matchConstraintPercentWidth = 1f
    matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
    matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
}

fun View.scaleToFit(videoSize: SizeModel, parentView: View? = null) {
    if (videoSize.width == 0 || videoSize.height == 0) return
    (parentView ?: parent as View).doOnLayout { useToScale ->
        calculateSurfaceSize(videoSize.width, videoSize.height)
        val size = useToScale.calculateSurfaceSize(videoSize.width, videoSize.height)
        val params = layoutParams
        params.width = size.width
        params.height = size.height
        layoutParams = params
    }
}

private fun View.calculateSurfaceSize(videoWidth: Int, videoHeight: Int): Size {
    val ratio = videoHeight / videoWidth.toFloat()
    val scaledWidth: Int
    val scaledHeight: Int
    if (measuredHeight > measuredWidth * ratio) {
        scaledWidth = measuredWidth
        scaledHeight = (measuredWidth * ratio).roundToInt()
    } else {
        scaledWidth = (measuredHeight / ratio).roundToInt()
        scaledHeight = measuredHeight
    }
    return Size(scaledWidth, scaledHeight)
}

fun Long.toFormattedTime(): String {
    val stringBuffer = StringBuffer()
    val tenMinutes = 1000 * 60 * 10
    val hour = tenMinutes * 6
    val tenHours = hour * 10
    when {
        this in 0 until tenMinutes -> {
            stringBuffer.append(this.getMinutesAndSeconds("%01d"))
        }
        this in tenMinutes until hour -> {
            stringBuffer.append(this.getMinutesAndSeconds())
        }
        this in hour until tenHours -> {
            val hours: Long = this / (1000 * 60 * 60)
            val leftover = this - hours * 1000 * 60 * 60
            stringBuffer.append(formatLocalized("%01d", hours)).append(":")
            stringBuffer.append(leftover.getMinutesAndSeconds())
        }
        this >= tenHours -> {
            val hours: Long = this / (1000 * 60 * 60)
            val leftover = this - hours * 1000 * 60 * 60
            stringBuffer.append(formatLocalized("%02d", hours)).append(":")
            stringBuffer.append(leftover.getMinutesAndSeconds())
        }
    }
    return stringBuffer.toString()
}

fun Long.getMinutesAndSeconds(minutesFormat: String = "%02d"): String {
    val stringBuffer = StringBuffer()
    val minutes = this / (1000 * 60)
    val seconds = this / 1000 - minutes * 60
    stringBuffer.append(formatLocalized(minutesFormat, minutes)).append(":")
    stringBuffer.append(formatLocalized("%02d", seconds))
    return stringBuffer.toString()
}

fun View.setVisible(isVisible: Boolean = true, hideOption: Int = View.GONE) {
    visibility = if (isVisible) View.VISIBLE else hideOption
}

fun countDownTimer(time: Long, tickSize: Long, onFinish: () -> Unit) = object : CountDownTimer(time, tickSize) {
    override fun onTick(p0: Long) {
        /* Ignored */
    }

    override fun onFinish() {
        onFinish()
    }
}

fun View.setXIfNotOutOfBounds(thumbXCenter: Float, screenWidth: Int, progress: Int) {
    val halfSize = width.toFloat() / 2
    when (progress) {
        SEEKBAR_MAX_PROGRESS.toInt() -> x = screenWidth - measuredWidth.toFloat()
        0 -> x = thumbXCenter - halfSize
        else -> {
            if (thumbXCenter >= halfSize && thumbXCenter <= screenWidth - halfSize) {
                x = thumbXCenter - halfSize
            }
        }
    }
}

private fun formatLocalized(format: String, vararg args: Any) = String.format(Locale.getDefault(), format, *args)
