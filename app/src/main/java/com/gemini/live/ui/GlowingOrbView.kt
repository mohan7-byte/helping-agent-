package com.gemini.live.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.gemini.live.R

/**
 * Ultra lightweight native Canvas Glowing Orb.
 * Zero WebView/CSS overhead: uses pure Android Canvas RadialGradient + hardware acceleration.
 */
class GlowingOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE, LISTENING, WORKING, SPEAKING
    }

    private var currentState = State.IDLE
    private var glowScale = 1.0f
    private var coreScale = 1.0f
    private var glowAlpha = 0.5f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pulseAnimator: ValueAnimator? = null

    init {
        startPulseAnimation()
    }

    fun setState(state: State) {
        if (currentState == state) return
        currentState = state
        when (state) {
            State.IDLE -> {
                glowScale = 1.0f
                glowAlpha = 0.4f
            }
            State.LISTENING -> {
                glowScale = 1.3f
                glowAlpha = 0.85f
            }
            State.WORKING -> {
                glowScale = 1.4f
                glowAlpha = 0.95f
            }
            State.SPEAKING -> {
                glowScale = 1.5f
                glowAlpha = 0.95f
            }
        }
        invalidate()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.94f, 1.06f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                coreScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = Math.min(cx, cy) * 0.45f

        val (innerColor, outerColor) = when (currentState) {
            State.IDLE -> Pair(
                ContextCompat.getColor(context, R.color.orb_idle_inner),
                ContextCompat.getColor(context, R.color.orb_idle_outer)
            )
            State.LISTENING -> Pair(
                ContextCompat.getColor(context, R.color.orb_listening_inner),
                ContextCompat.getColor(context, R.color.orb_listening_outer)
            )
            State.WORKING -> Pair(
                ContextCompat.getColor(context, R.color.orb_working_inner),
                ContextCompat.getColor(context, R.color.orb_working_outer)
            )
            State.SPEAKING -> Pair(
                ContextCompat.getColor(context, R.color.orb_speaking_inner),
                ContextCompat.getColor(context, R.color.orb_speaking_outer)
            )
        }

        // Draw Soft Glowing Aura
        val glowRadius = baseRadius * 1.8f * glowScale
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            outerColor, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (glowAlpha * 255).toInt()
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // Draw High-Contrast Core Orb
        val currentCoreRadius = baseRadius * coreScale
        corePaint.shader = RadialGradient(
            cx - currentCoreRadius * 0.3f,
            cy - currentCoreRadius * 0.3f,
            currentCoreRadius * 1.3f,
            outerColor, innerColor,
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = 255
        canvas.drawCircle(cx, cy, currentCoreRadius, corePaint)
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
