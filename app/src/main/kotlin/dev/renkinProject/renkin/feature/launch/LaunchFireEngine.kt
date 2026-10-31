package dev.renkinProject.renkin.feature.launch

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

private const val STEP_SECONDS = 1f / 60f
private const val MAX_STEPS_PER_FRAME = 6
/** Lets the last flames and the sheen finish before the overlay fades out. */
private const val TAIL_MS = 150f
private const val COOLING_SPAN = 0.32f
private const val PREHEAT_SPAN = 0.06f
/** Beyond this distance the sheen's Gaussian is below 1e-4: invisible. */
private const val SHEEN_REACH = 0.2f

internal const val FLAME = 0
internal const val EMBER = 1

/**
 * Deterministic fire simulation in mask-pixel space. Particles live in pre-allocated parallel
 * arrays and the symbol is painted into a reused ARGB buffer, so a frame allocates nothing.
 * Stalled frames run at most [MAX_STEPS_PER_FRAME] fixed steps: on a slow device the animation
 * stretches instead of skipping the burn.
 */
internal class LaunchFireEngine(private val mask: LaunchFireMask, val capacity: Int, seed: Int = 7) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    private val velocityX = FloatArray(capacity)
    private val velocityY = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val lifetime = FloatArray(capacity)
    val radius = FloatArray(capacity)
    private val phase = FloatArray(capacity)
    val kind = IntArray(capacity)
    var count = 0
        private set

    /** Colour of the symbol's metal as opaque ARGB, [LaunchFireMask.width] wide. */
    val pixels = IntArray(mask.width * mask.height)

    // An opaque colour field over the whole crop: the vector symbol gives the shape and its sharp
    // edges, so these pixels only need smooth colour, never coverage.
    private val restingGold = argb(1f, LaunchGold.red * 255f, LaunchGold.green * 255f, LaunchGold.blue * 255f)
    private val finishArgb = IntArray(pixels.size) {
        argb(1f, mask.finishRed[it], mask.finishGreen[it], mask.finishBlue[it])
    }

    var elapsedMs = 0f
        private set
    val progress: Float get() = min(1f, elapsedMs / LAUNCH_FIRE_DURATION_MS)
    val finished: Boolean get() = elapsedMs >= LAUNCH_FIRE_DURATION_MS + TAIL_MS

    private var randomState = seed
    private var pendingSeconds = 0f
    private var flameCarry = 0f
    private var emberCarry = 0f
    private var paintedAtRest = false
    private val unit = mask.width / 200f

    fun ageFraction(index: Int): Float = age[index] / lifetime[index]

    fun advance(frameSeconds: Float) {
        pendingSeconds += frameSeconds
        var steps = 0
        while (pendingSeconds >= STEP_SECONDS && steps < MAX_STEPS_PER_FRAME) {
            step()
            pendingSeconds -= STEP_SECONDS
            steps++
        }
        if (steps == MAX_STEPS_PER_FRAME) pendingSeconds = 0f
    }

    internal fun step() {
        val p = elapsedMs / LAUNCH_FIRE_DURATION_MS
        if (p <= 1f) spawn(p)
        var i = 0
        while (i < count) {
            age[i] += STEP_SECONDS
            if (age[i] >= lifetime[i]) {
                removeAt(i)
                continue
            }
            if (kind[i] == FLAME) {
                velocityY[i] *= 1f - 0.6f * STEP_SECONDS
                velocityX[i] *= 1f - 1.5f * STEP_SECONDS
                radius[i] *= 1f - 0.9f * STEP_SECONDS
            } else {
                x[i] += sin(age[i] * 7f + phase[i]) * 12f * unit * STEP_SECONDS
            }
            x[i] += velocityX[i] * STEP_SECONDS
            y[i] += velocityY[i] * STEP_SECONDS
            i++
        }
        elapsedMs += STEP_SECONDS * 1000f
    }

    /** Fire lives only on the burn front: nothing spawns under or around the symbol. */
    private fun spawn(p: Float) {
        val front = LaunchFireTimeline.front(p)
        if (front < -0.02f || front > 1.05f) return
        val reached = frontIndex(front)
        if (reached == 0) return
        val spread = maxOf(1, minOf((mask.frontOrder.size * 0.08f).toInt(), reached))
        val intensity = LaunchFireTimeline.flame(p)

        flameCarry += 320f * intensity * STEP_SECONDS
        while (flameCarry >= 1f) {
            flameCarry -= 1f
            val pixel = mask.frontOrder[reached - 1 - (random() * spread).toInt()]
            add(FLAME, pixel,
                velocityX = (random() - 0.5f) * 34f * unit,
                velocityY = -(55f + random() * 95f) * unit,
                lifetime = 0.32f + random() * 0.45f,
                radius = (9f + random() * 14f) * unit)
        }
        emberCarry += 34f * intensity * STEP_SECONDS
        while (emberCarry >= 1f) {
            emberCarry -= 1f
            val pixel = mask.frontOrder[reached - 1 - (random() * spread).toInt()]
            add(EMBER, pixel,
                velocityX = (random() - 0.5f) * 20f * unit,
                velocityY = -(35f + random() * 70f) * unit,
                lifetime = 0.9f + random() * 0.9f,
                radius = (2f + random() * 2.5f) * unit)
        }
    }

    /** Number of front-order pixels whose burn time is below [front]. */
    private fun frontIndex(front: Float): Int {
        var low = 0
        var high = mask.frontOrder.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (mask.burnTime[mask.frontOrder[mid]] < front) low = mid + 1 else high = mid
        }
        return low
    }

    private fun add(type: Int, pixel: Int, velocityX: Float, velocityY: Float, lifetime: Float, radius: Float) {
        if (count == capacity) return
        val i = count++
        kind[i] = type
        x[i] = (pixel % mask.width).toFloat()
        y[i] = (pixel / mask.width).toFloat()
        this.velocityX[i] = velocityX
        this.velocityY[i] = velocityY
        age[i] = 0f
        this.lifetime[i] = lifetime
        this.radius[i] = radius
        phase[i] = random() * 6.28f
    }

    private fun removeAt(index: Int) {
        val last = --count
        x[index] = x[last]; y[index] = y[last]
        velocityX[index] = velocityX[last]; velocityY[index] = velocityY[last]
        age[index] = age[last]; lifetime[index] = lifetime[last]
        radius[index] = radius[last]; phase[index] = phase[last]
        kind[index] = kind[last]
    }

    /** Repaints the symbol for the current progress; returns false when nothing changed. */
    fun paintSymbol(): Boolean {
        val p = progress
        if (p >= 1f) {
            if (paintedAtRest) return false
            paintedAtRest = true
        }
        val front = LaunchFireTimeline.front(p)
        val sheen = LaunchFireTimeline.sheen(p)
        val sheenActive = sheen > -0.2f && sheen < 1.2f
        val width = mask.width
        val height = mask.height
        // Unreached and fully cooled pixels take precomputed colours, so the per-pixel maths runs
        // just for the hot band and the sheen.
        for (i in pixels.indices) {
            val reached = front - mask.burnTime[i]
            if (reached <= -PREHEAT_SPAN) {
                pixels[i] = restingGold
                continue
            }
            if (reached < 0f) {
                // Gold just ahead of the front warms toward orange, so the fire reads as heating
                // the metal rather than replacing it.
                val warmth = (1f + reached / PREHEAT_SPAN).let { it * it } * 0.6f
                pixels[i] = argb(
                    1f,
                    mix(LaunchGold.red * 255f, HotMetal.red(0.45f), warmth),
                    mix(LaunchGold.green * 255f, HotMetal.green(0.45f), warmth),
                    mix(LaunchGold.blue * 255f, HotMetal.blue(0.45f), warmth)
                )
                continue
            }
            val cooling = reached / COOLING_SPAN
            val shine = if (sheenActive) {
                val offset = ((i % width).toFloat() / width + (i / width).toFloat() / height) * 0.5f - sheen
                if (offset > SHEEN_REACH || offset < -SHEEN_REACH) 0f else exp(-(offset * offset) / 0.004f)
            } else 0f
            if (cooling >= 1f && shine == 0f) {
                pixels[i] = finishArgb[i]
                continue
            }
            val hot = clamp01(cooling)
            val settle = smoothStep(0.35f, 1f, hot)
            var red = mix(HotMetal.red(hot), mask.finishRed[i], settle)
            var green = mix(HotMetal.green(hot), mask.finishGreen[i], settle)
            var blue = mix(HotMetal.blue(hot), mask.finishBlue[i], settle)
            red += (255f - red) * shine * 0.65f
            green += (255f - green) * shine * 0.65f
            blue += (235f - blue) * shine * 0.5f
            pixels[i] = argb(1f, red, green, blue)
        }
        return true
    }

    /** The mockup's mulberry32 generator, reduced to 24 bits so the result is always below 1. */
    private fun random(): Float {
        randomState += 0x6D2B79F5
        var t = randomState
        t = (t xor (t ushr 15)) * (1 or t)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)) ushr 8) / 16777216f
    }
}

private fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int =
    (channel(alpha * 255f) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)

private fun channel(value: Float): Int = value.toInt().coerceIn(0, 255)
