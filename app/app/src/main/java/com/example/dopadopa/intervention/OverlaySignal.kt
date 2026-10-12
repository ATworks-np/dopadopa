package com.example.dopadopa.intervention

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Unordered drifting light gathers into a softly floating shield during an actual analysis. */
class OverlaySignal(context: Context) : View(context) {
    var judging = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value) "DopaGuard 分析中" else "DopaGuard 待機"
            if (!ValueAnimator.areAnimatorsEnabled()) energy = if (value) 1f else 0f
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloMatrix = Matrix()
    private val idleHalo = RadialGradient(0f, 0f, 1f,
        intArrayOf(0x709995D9, 0x207E88DD, 0x007E88DD),
        floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    private val activeHalo = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xB09BFFF0.toInt(), 0x607D79FF, 0x00EC80FF),
        floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    private val count = 276
    private val idle = FloatArray(count * 2)
    private val shield = FloatArray(count * 2)
    private val seeds = FloatArray(count * 4)
    private var previousFrame = 0L
    private var elapsed = 0f
    private var energy = 0f
    private val motion = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = SystemClock.uptimeMillis()
            val dt = if (previousFrame == 0L) 0f else ((now - previousFrame) / 1000f).coerceAtMost(0.1f)
            previousFrame = now
            energy += ((if (judging) 1f else 0f) - energy) * (1f - exp(-dt * 2.5f))
            elapsed += dt
            invalidate()
        }
    }

    init {
        contentDescription = "DopaGuard 待機"
        setBackgroundColor(Color.TRANSPARENT)
        val random = Random(41)
        val outline = Path().apply {
            moveTo(0f, -0.88f)
            cubicTo(0.24f, -0.81f, 0.43f, -0.71f, 0.62f, -0.65f)
            lineTo(0.58f, 0.08f)
            cubicTo(0.52f, 0.43f, 0.26f, 0.66f, 0f, 0.86f)
            cubicTo(-0.26f, 0.66f, -0.52f, 0.43f, -0.58f, 0.08f)
            lineTo(-0.62f, -0.65f)
            cubicTo(-0.43f, -0.71f, -0.24f, -0.81f, 0f, -0.88f)
            close()
        }
        val perimeter = PathMeasure(outline, true)
        val point = FloatArray(2)
        for (i in 0 until count) {
            val angle = random.nextFloat() * 6.283185f
            val reach = sqrt(random.nextFloat()) * 0.77f
            idle[i * 2] = cos(angle) * reach
            idle[i * 2 + 1] = sin(angle) * reach
            for (j in 0..3) seeds[i * 4 + j] = random.nextFloat()
            if (i < 192) {
                val inner = i >= 120
                val index = if (inner) i - 120 else i
                val total = if (inner) 72 else 120
                perimeter.getPosTan(perimeter.length * index / total, point, null)
                shield[i * 2] = point[0] * if (inner) 0.82f else 1f
                shield[i * 2 + 1] = point[1] * if (inner) 0.82f else 1f
            } else {
                val y = random.nextFloat() * 1.36f - 0.6f
                val halfWidth = if (y < 0.08f) 0.50f else 0.50f * ((0.86f - y) / 0.78f)
                shield[i * 2] = (random.nextFloat() * 2f - 1f) * halfWidth
                shield[i * 2 + 1] = y
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateMotion() }
    override fun onDetachedFromWindow() { motion.cancel(); previousFrame = 0L; super.onDetachedFromWindow() }

    private fun updateMotion() {
        if (isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            if (!motion.isStarted) { previousFrame = 0L; motion.start() }
        } else { motion.cancel(); previousFrame = 0L; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width * 0.5f + sin(elapsed * 0.43f) * side * 0.018f * energy
        val cy = height * 0.5f + sin(elapsed * 0.7f) * side * 0.032f * energy
        val reach = side * (0.42f + sin(elapsed * 0.8f) * (0.008f + energy * 0.01f))
        aura(canvas, cx, cy, side * 0.46f)
        val tilt = sin(elapsed * 0.35f) * 0.08f * energy
        val ct = cos(tilt)
        val st = sin(tilt)
        for (i in 0 until count) {
            val seed = i * 4
            val phase = seeds[seed] * 6.283185f
            val speed = 0.22f + seeds[seed + 1] * 0.4f
            // Independent slow waves, no common orbit or spinning sphere.
            val dx = sin(elapsed * speed + phase) * 0.14f + sin(elapsed * speed * 0.47f + phase * 2f) * 0.045f
            val dy = cos(elapsed * speed * 0.73f + phase * 1.3f) * 0.14f
            val idleX = idle[i * 2] + dx
            val idleY = idle[i * 2 + 1] + dy
            val targetX = shield[i * 2] + sin(elapsed * speed + phase) * (0.04f + seeds[seed + 2] * 0.025f) +
                sin(elapsed * 0.65f + shield[i * 2 + 1] * 4f) * 0.018f
            val targetY = shield[i * 2 + 1] + cos(elapsed * speed * 0.8f + phase) * (0.035f + seeds[seed + 3] * 0.02f)
            val x = idleX * (1f - energy) + targetX * energy
            val y = idleY * (1f - energy) + targetY * energy
            val px = cx + (x * ct - y * st) * reach
            val py = cy + (x * st + y * ct) * reach
            val shimmer = 0.68f + sin(elapsed * 0.9f + phase * 2f) * 0.32f
            val outlineLight = if (i < 120) 1f else if (i < 192) 0.75f else 0.3f
            val alpha = ((65f + seeds[seed + 2] * 110f) * shimmer * (1f - energy) +
                (150f + shimmer * 105f) * outlineLight * energy).toInt().coerceIn(0, 255)
            val size = side * (0.0026f + seeds[seed + 3] * 0.005f) * (1f + energy * 0.12f)
            val color = if (i % 3 == 0) Color.rgb(156, 213, 244) else Color.rgb(189, 173, 245)
            val active = if (i % 5 == 0) Color.rgb(255, 156, 223) else Color.rgb(119, 255, 231)
            paint.color = Color.rgb(
                (Color.red(color) + (Color.red(active) - Color.red(color)) * energy).toInt(),
                (Color.green(color) + (Color.green(active) - Color.green(color)) * energy).toInt(),
                (Color.blue(color) + (Color.blue(active) - Color.blue(color)) * energy).toInt())
            if (i % 4 == 0) {
                paint.alpha = (alpha * (0.1f + energy * 0.13f)).toInt()
                canvas.drawCircle(px, py, size * (3.5f + energy), paint)
                paint.alpha = (alpha * 0.3f).toInt()
                canvas.drawCircle(px, py, size * 1.8f, paint)
            }
            paint.alpha = alpha
            canvas.drawCircle(px, py, size, paint)
        }
    }

    private fun aura(canvas: Canvas, x: Float, y: Float, radius: Float) {
        haloMatrix.setScale(radius, radius)
        haloMatrix.postTranslate(x, y)
        idleHalo.setLocalMatrix(haloMatrix)
        activeHalo.setLocalMatrix(haloMatrix)
        paint.shader = idleHalo
        paint.alpha = (55f * (1f - energy)).toInt()
        canvas.drawCircle(x, y, radius, paint)
        if (energy > 0.001f) {
            paint.shader = activeHalo
            paint.alpha = (120f * energy).toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.shader = null
    }
}
