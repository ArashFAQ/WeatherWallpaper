package ir.daftarak.weatherwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Shared, decoded-once scene images (ground layers with the sky cut out). */
object SceneImages {
    var day: Bitmap? = null
    var night: Bitmap? = null
    var snow: Bitmap? = null
    var lights: Bitmap? = null

    @Synchronized
    fun ensure(ctx: Context) {
        if (day != null) return
        fun load(name: String): Bitmap = ctx.assets.open(name).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })!!
        }
        day = load("day.webp")
        night = load("night.webp")
        snow = load("snow.webp")
        lights = load("lights.webp")
    }
}

class Renderer(ctx: Context) {
    companion object {
        const val IMG_W = 1024f
        const val IMG_H = 1536f
        const val HORIZON_IMG_Y = 395f
        const val TOWER_LIGHT_X = 247.5f
        const val TOWER_LIGHT_Y = 271f
        const val MAX_RAIN = 420
        const val MAX_SNOW = 320
        const val MAX_CLOUDS = 16
    }

    private val density = ctx.resources.displayMetrics.density
    private val rnd = Random(7)

    init { SceneImages.ensure(ctx) }

    // ---------- paints ----------
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val moonPath = Path()
    private val oval = RectF()
    private val oval2 = RectF()
    private val cm = ColorMatrix()
    private val cmTmp = ColorMatrix()

    // ---------- sprites ----------
    private val cloudSprites: List<Bitmap> = List(5) { makeCloudSprite(it) }
    private val fogSprite: Bitmap = makeFogSprite()

    private fun makeCloudSprite(seed: Int): Bitmap {
        val w = 420; val h = 190
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(seed * 31 + 5)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val puffs = 26 + r.nextInt(14)
        repeat(puffs) {
            val t = r.nextFloat()
            val cx = w * (0.15f + 0.7f * t) + r.nextFloat() * 30 - 15
            val bump = sin(t * PI).toFloat()
            val rad = 28f + 46f * bump * (0.6f + 0.4f * r.nextFloat())
            val cy = h * 0.66f - bump * 40f * r.nextFloat() - r.nextFloat() * 10
            p.shader = RadialGradient(
                cx, cy, rad,
                intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, rad, p)
        }
        return b
    }

    private fun makeFogSprite(): Bitmap {
        val w = 512; val h = 128
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(99)
        repeat(18) {
            val cx = 40f + r.nextFloat() * (w - 80)
            val cy = h / 2f + (r.nextFloat() - 0.5f) * 30f
            val rad = 40f + r.nextFloat() * 50f
            p.shader = RadialGradient(cx, cy, rad, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rad, p)
        }
        return b
    }

    // ---------- dynamic objects ----------
    private class Cloud(var sprite: Int, var x: Float, var y: Float, var scale: Float, var speed: Float, var alpha: Float, var stretch: Float)

    private val clouds = Array(MAX_CLOUDS) { i ->
        Cloud(
            sprite = i % 5,
            x = rnd.nextFloat() * (IMG_W + 600) - 300,
            y = 20f + rnd.nextFloat() * (HORIZON_IMG_Y - 90),
            scale = 0.55f + rnd.nextFloat() * 0.9f,
            speed = 3f + rnd.nextFloat() * 6f,
            alpha = 0.65f + rnd.nextFloat() * 0.35f,
            stretch = if (i >= 10) 2.6f else 1f,
        )
    }

    private val stars = FloatArray(260 * 4).also {
        for (i in 0 until 260) {
            it[i * 4] = rnd.nextFloat()
            it[i * 4 + 1] = rnd.nextFloat().pow(1.4f)
            it[i * 4 + 2] = 0.5f + rnd.nextFloat() * 1.3f
            it[i * 4 + 3] = rnd.nextFloat() * 6.28f
        }
    }

    // rain: x, y (0..1 of screen), speed, depth
    private val rain = FloatArray(MAX_RAIN * 4).also {
        for (i in 0 until MAX_RAIN) respawnRain(it, i, true)
    }
    private val rainLinesFar = FloatArray(MAX_RAIN * 4)
    private val rainLinesNear = FloatArray(MAX_RAIN * 4)

    // snow: x, y, layer, phase
    private val snow = FloatArray(MAX_SNOW * 4).also {
        for (i in 0 until MAX_SNOW) respawnSnow(it, i, true)
    }
    private val snowPts = Array(3) { FloatArray(MAX_SNOW * 2) }

    private fun respawnRain(a: FloatArray, i: Int, anyY: Boolean) {
        a[i * 4] = rnd.nextFloat() * 1.4f - 0.2f
        a[i * 4 + 1] = if (anyY) rnd.nextFloat() else -rnd.nextFloat() * 0.1f
        val near = rnd.nextFloat() < 0.35f
        a[i * 4 + 3] = if (near) 1f else 0f
        a[i * 4 + 2] = if (near) 1.5f + rnd.nextFloat() * 0.4f else 1.0f + rnd.nextFloat() * 0.3f
    }

    private fun respawnSnow(a: FloatArray, i: Int, anyY: Boolean) {
        a[i * 4] = rnd.nextFloat() * 1.2f - 0.1f
        a[i * 4 + 1] = if (anyY) rnd.nextFloat() else -rnd.nextFloat() * 0.05f
        val r = rnd.nextFloat()
        a[i * 4 + 2] = if (r < 0.5f) 0f else if (r < 0.85f) 1f else 2f
        a[i * 4 + 3] = rnd.nextFloat() * 6.28f
    }

    private var flash = 0f
    private var nextFlashIn = 4f
    private var flashSecond = -1f

    // ---------- helpers ----------
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * k).toInt(),
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * k).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * k).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * k).toInt(),
        )
    }

    private val skyKeys = floatArrayOf(-18f, -12f, -6f, -2f, 2f, 8f, 25f)
    private val skyTop = intArrayOf(0xFF04070F.toInt(), 0xFF0A1330.toInt(), 0xFF17234E.toInt(), 0xFF283A70.toInt(), 0xFF3D63A8.toInt(), 0xFF4A86D0.toInt(), 0xFF2F77D6.toInt())
    private val skyBot = intArrayOf(0xFF0B1529.toInt(), 0xFF1B294D.toInt(), 0xFF5E4A72.toInt(), 0xFFD98058.toInt(), 0xFFF0B07C.toInt(), 0xFFBCD6EE.toInt(), 0xFFA6CEF2.toInt())

    private fun keyColor(arr: IntArray, alt: Float): Int {
        if (alt <= skyKeys[0]) return arr[0]
        for (i in 0 until skyKeys.size - 1) {
            if (alt <= skyKeys[i + 1]) return mix(arr[i], arr[i + 1], (alt - skyKeys[i]) / (skyKeys[i + 1] - skyKeys[i]))
        }
        return arr[arr.size - 1]
    }

    private fun matrixFor(bright: Float, warm: Float, sat: Float, blueNight: Float): ColorMatrixColorFilter {
        cm.reset()
        cm.setSaturation(sat)
        cmTmp.reset()
        val r = bright * (1f + 0.22f * warm) * (1f - 0.25f * blueNight)
        val g = bright * (1f + 0.02f * warm) * (1f - 0.12f * blueNight)
        val b = bright * (1f - 0.22f * warm) * (1f + 0.15f * blueNight)
        cmTmp.setScale(r, g, b, 1f)
        cm.postConcat(cmTmp)
        return ColorMatrixColorFilter(cm)
    }

    /**
     * @param pos horizontal pan 0..1 of the scene.
     * @param dt seconds since last frame.
     */
    fun draw(canvas: Canvas, w: Int, h: Int, pos: Float, t: Double, dt: Float, a: AstroState, wx: WeatherState) {
        val day = SceneImages.day ?: return
        val night = SceneImages.night!!
        val snowImg = SceneImages.snow!!
        val lights = SceneImages.lights!!

        val wf = w.toFloat(); val hf = h.toFloat()
        val scale = max(hf / IMG_H, wf / IMG_W)
        val dw = IMG_W * scale
        val dh = IMG_H * scale
        val left = -(dw - wf) * pos.coerceIn(0f, 1f)
        val top = hf - dh
        val horizonY = top + HORIZON_IMG_Y * scale

        val sunDeg = (a.sunAlt * 180 / PI).toFloat()
        val dayK = smooth(-8f, 4f, sunDeg)
        val nightK = 1f - smooth(-12f, -4f, sunDeg)
        val cloud = wx.cloud.coerceIn(0f, 1f)
        val overcast = (cloud.pow(1.6f) * 0.85f + wx.rain * 0.15f + wx.snow * 0.1f).coerceIn(0f, 1f)

        // ---------- sky ----------
        var top0 = keyColor(skyTop, sunDeg)
        var bot0 = keyColor(skyBot, sunDeg)
        val greyTop = mix(0xFF141821.toInt(), 0xFF8E98A3.toInt(), dayK)
        val greyBot = mix(0xFF232833.toInt(), 0xFFC2C7CD.toInt(), dayK)
        top0 = mix(top0, greyTop, overcast)
        bot0 = mix(bot0, greyBot, overcast)
        if (wx.fog > 0f) {
            bot0 = mix(bot0, mix(0xFF2C3038.toInt(), 0xFFD5D8DC.toInt(), dayK), wx.fog * 0.7f)
            top0 = mix(top0, mix(0xFF1E2128.toInt(), 0xFFB9BEC4.toInt(), dayK), wx.fog * 0.5f)
        }
        fill.shader = LinearGradient(0f, 0f, 0f, horizonY + 30 * scale, top0, bot0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, wf, hf, fill)
        fill.shader = null

        // ---------- stars ----------
        val starA = nightK * (1f - cloud).pow(2) * (1f - wx.fog)
        if (starA > 0.02f) {
            for (i in 0 until 260) {
                val sx = stars[i * 4] * wf
                val sy = stars[i * 4 + 1] * (horizonY - 20 * scale)
                val tw = 0.55f + 0.45f * sin(t * 1.7 + stars[i * 4 + 3]).toFloat()
                fill.color = Color.argb((starA * tw * 230).toInt(), 255, 255, 245)
                canvas.drawCircle(sx, sy, stars[i * 4 + 2] * density * 0.6f, fill)
            }
        }

        // sky position helpers (azimuth: 0 = south, west positive; east appears on the right)
        fun skyX(az: Double) = left + (IMG_W * (0.5f - (az / PI).toFloat() * 0.6f)) * scale
        fun skyY(alt: Double) = horizonY + 6 * scale - (alt / (55 * PI / 180)).toFloat() * (horizonY - top0Offset(top, scale))

        // ---------- sun ----------
        if (sunDeg > -3f) {
            val sx = skyX(a.sunAz); val sy = skyY(a.sunAlt)
            val r = 20f * scale
            val low = 1f - smooth(0f, 12f, sunDeg)
            val col = mix(0xFFFFF4D6.toInt(), 0xFFFFA860.toInt(), low)
            val vis = (1f - 0.88f * cloud) * (1f - 0.7f * wx.fog)
            if (vis > 0.03f) {
                fill.shader = RadialGradient(sx, sy, r * 7, mix(col and 0x00FFFFFF, col, 0.45f * vis), col and 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawCircle(sx, sy, r * 7, fill)
                fill.shader = null
                fill.color = col
                fill.alpha = (255 * vis).toInt()
                canvas.drawCircle(sx, sy, r, fill)
            }
        }

        // ---------- moon ----------
        val moonDeg = (a.moonAlt * 180 / PI).toFloat()
        if (moonDeg > -2f) {
            val vis = (1f - 0.7f * dayK) * (1f - 0.9f * cloud) * (1f - 0.7f * wx.fog)
            if (vis > 0.03f) drawMoon(canvas, skyX(a.moonAz), skyY(a.moonAlt), 15f * scale, a, vis)
        }

        // ---------- clouds ----------
        val visibleClouds = when {
            cloud < 0.08f -> 0
            else -> (2 + cloud * 8).toInt().coerceAtMost(10)
        }
        val heavyClouds = if (cloud > 0.6f) ((cloud - 0.6f) / 0.4f * 6).toInt().coerceIn(0, 6) else 0
        val warmSky = max(0f, 1f - abs(sunDeg - 1f) / 8f) * (1f - overcast)
        var cloudCol = mix(0xFF3A4152.toInt(), 0xFFFFFFFF.toInt(), dayK)
        cloudCol = mix(cloudCol, 0xFFFFC4A8.toInt(), warmSky * 0.8f)
        cloudCol = mix(cloudCol, mix(0xFF20242C.toInt(), 0xFF8A919B.toInt(), dayK), (wx.rain + wx.storm * 0.5f + wx.snow * 0.3f).coerceAtMost(1f) * 0.8f)
        cloudPaint.colorFilter = PorterDuffColorFilter(cloudCol, PorterDuff.Mode.MULTIPLY)
        val windSpeed = 0.6f + wx.wind / 12f
        for (i in 0 until MAX_CLOUDS) {
            val c = clouds[i]
            c.x += c.speed * windSpeed * dt
            val sw = 420f * c.scale * c.stretch
            if (c.x > IMG_W + 50) { c.x = -sw - rnd.nextFloat() * 200; c.y = 20f + rnd.nextFloat() * (HORIZON_IMG_Y - 90) }
            val active = if (i < 10) i < visibleClouds else (i - 10) < heavyClouds
            if (!active) continue
            val sh = 190f * c.scale * (if (c.stretch > 1f) 1.3f else 1f)
            dstRect.set(left + c.x * scale, top + c.y * scale, left + (c.x + sw) * scale, top + (c.y + sh) * scale)
            cloudPaint.alpha = (255 * c.alpha * (0.55f + 0.45f * cloud)).toInt()
            canvas.drawBitmap(cloudSprites[c.sprite], null, dstRect, cloudPaint)
        }

        // ---------- ground ----------
        srcRect.set(0, 0, day.width, day.height)
        dstRect.set(left, top, left + dw, top + dh)
        val warm = max(0f, 1f - abs(sunDeg - 1f) / 7f) * (1f - cloud * 0.7f)
        val sat = (1f - 0.35f * overcast - 0.45f * wx.fog).coerceIn(0.2f, 1f)
        val bright = (1f - 0.2f * overcast - 0.08f * wx.rain) * (0.75f + 0.25f * dayK)
        val dayFilter = matrixFor(bright, warm, sat, 0f)
        val snowMode = wx.snowGround > 0.5f
        if (!snowMode) {
            bmpPaint.colorFilter = if (wx.fog > 0.05f) matrixFor(1f, 0f, sat, 0f) else null
            bmpPaint.alpha = 255
            canvas.drawBitmap(night, srcRect, dstRect, bmpPaint)
            if (dayK > 0.003f) {
                bmpPaint.colorFilter = dayFilter
                bmpPaint.alpha = (255 * dayK).toInt()
                canvas.drawBitmap(day, srcRect, dstRect, bmpPaint)
            }
        } else {
            bmpPaint.colorFilter = matrixFor(0.24f, 0f, 0.7f, 1f)
            bmpPaint.alpha = 255
            canvas.drawBitmap(snowImg, srcRect, dstRect, bmpPaint)
            if (dayK < 0.997f) {
                addPaint.alpha = (255 * (1f - dayK)).toInt()
                canvas.drawBitmap(lights, srcRect, dstRect, addPaint)
            }
            if (dayK > 0.003f) {
                bmpPaint.colorFilter = dayFilter
                bmpPaint.alpha = (255 * dayK).toInt()
                canvas.drawBitmap(snowImg, srcRect, dstRect, bmpPaint)
            }
        }
        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255

        // blinking aviation light on the tower
        if (dayK < 0.6f) {
            val on = ((t % 1.6) < 0.8)
            if (on) {
                val lx = left + TOWER_LIGHT_X * scale; val ly = top + TOWER_LIGHT_Y * scale
                val rr = 7f * scale
                fill.shader = RadialGradient(lx, ly, rr, Color.argb((200 * (1f - dayK)).toInt(), 255, 40, 30), Color.argb(0, 255, 0, 0), Shader.TileMode.CLAMP)
                canvas.drawCircle(lx, ly, rr, fill)
                fill.shader = null
            }
        }

        // ---------- fog ----------
        if (wx.fog > 0.03f) {
            val fogCol = mix(0xFF3A3F48.toInt(), 0xFFE4E6E9.toInt(), dayK)
            fill.shader = LinearGradient(
                0f, horizonY - 120 * scale, 0f, hf,
                intArrayOf(fogCol and 0x00FFFFFF, mix(fogCol and 0x00FFFFFF, fogCol, wx.fog * 0.75f), mix(fogCol and 0x00FFFFFF, fogCol, wx.fog * 0.45f)),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, wf, hf, fill)
            fill.shader = null
            cloudPaint.colorFilter = PorterDuffColorFilter(fogCol, PorterDuff.Mode.MULTIPLY)
            for (k in 0 until 6) {
                val bandY = horizonY + (k * 0.11f) * (hf - horizonY) - 40 * scale
                val bw = wf * 1.6f
                val x = (((t * (8 + k * 3) * density) % (bw + wf)) - bw).toFloat()
                dstRect.set(x, bandY, x + bw, bandY + 110 * scale)
                cloudPaint.alpha = (255 * wx.fog * 0.8f).toInt()
                canvas.drawBitmap(fogSprite, null, dstRect, cloudPaint)
            }
        }

        // ---------- rain ----------
        val rainN = (wx.rain * MAX_RAIN).toInt()
        if (rainN > 0) {
            val slant = (wx.wind / 60f).coerceIn(0.05f, 0.35f)
            var nf = 0; var nn = 0
            for (i in 0 until rainN) {
                val o = i * 4
                rain[o + 1] += rain[o + 2] * dt
                rain[o] -= slant * rain[o + 2] * dt * hf / wf
                if (rain[o + 1] > 1.05f || rain[o] < -0.3f) respawnRain(rain, i, false)
                val x = rain[o] * wf; val y = rain[o + 1] * hf
                val near = rain[o + 3] > 0.5f
                val len = (if (near) 0.05f else 0.028f) * hf
                val arr = if (near) rainLinesNear else rainLinesFar
                val idx = if (near) nn else nf
                arr[idx] = x; arr[idx + 1] = y
                arr[idx + 2] = x + slant * len; arr[idx + 3] = y - len
                if (near) nn += 4 else nf += 4
            }
            val nightRain = 1f - dayK
            linePaint.color = mix(Color.argb(85, 230, 236, 245), Color.argb(70, 170, 185, 215), nightRain)
            linePaint.strokeWidth = 1.0f * density
            canvas.drawLines(rainLinesFar, 0, nf, linePaint)
            linePaint.color = mix(Color.argb(130, 235, 240, 250), Color.argb(100, 180, 195, 225), nightRain)
            linePaint.strokeWidth = 1.6f * density
            canvas.drawLines(rainLinesNear, 0, nn, linePaint)
        }

        // ---------- snow ----------
        val snowN = (wx.snow * MAX_SNOW).toInt()
        if (snowN > 0) {
            val counts = IntArray(3)
            val sway = 0.3f + wx.wind / 40f
            for (i in 0 until snowN) {
                val o = i * 4
                val layer = snow[o + 2].toInt()
                val speed = when (layer) { 0 -> 0.045f; 1 -> 0.075f; else -> 0.12f }
                snow[o + 1] += speed * dt
                snow[o] += (sin(t * 0.8 + snow[o + 3]).toFloat() * 0.012f * sway + wx.wind * 0.0004f) * dt
                if (snow[o + 1] > 1.03f) respawnSnow(snow, i, false)
                val pts = snowPts[layer]
                val c = counts[layer]
                pts[c] = snow[o] * wf; pts[c + 1] = snow[o + 1] * hf
                counts[layer] = c + 2
            }
            val base = mix(Color.argb(255, 200, 210, 230), Color.WHITE, dayK)
            for (layer in 0 until 3) {
                pointPaint.strokeWidth = density * when (layer) { 0 -> 1.6f; 1 -> 2.6f; else -> 4.2f }
                pointPaint.color = base
                pointPaint.alpha = when (layer) { 0 -> 150; 1 -> 200; else -> 230 }
                canvas.drawPoints(snowPts[layer], 0, counts[layer], pointPaint)
            }
        }

        // ---------- lightning ----------
        if (wx.storm > 0.5f) {
            nextFlashIn -= dt
            if (nextFlashIn <= 0f) {
                flash = 1f
                flashSecond = 0.18f
                nextFlashIn = 5f + rnd.nextFloat() * 10f
            }
            if (flashSecond > 0f) {
                flashSecond -= dt
                if (flashSecond <= 0f) flash = 0.8f
            }
        }
        if (flash > 0.01f) {
            fill.color = Color.argb((flash * 140).toInt(), 230, 235, 255)
            canvas.drawRect(0f, 0f, wf, hf, fill)
            flash = max(0f, flash - dt * 5f)
        }
    }

    private fun top0Offset(top: Float, scale: Float) = top + 40f * scale

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, a: AstroState, vis: Float) {
        // glow
        fill.shader = RadialGradient(cx, cy, r * 5, Color.argb((60 * vis).toInt(), 220, 225, 255), Color.argb(0, 220, 225, 255), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 5, fill)
        fill.shader = null
        // dark disc (earthshine)
        fill.color = Color.argb((50 * vis).toInt(), 160, 170, 200)
        canvas.drawCircle(cx, cy, r, fill)

        val k = a.moonFraction.toFloat().coerceIn(0f, 1f)
        if (k < 0.02f) return
        val waxing = a.moonPhase < 0.5
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val rx = r * abs(1f - 2f * k)
        oval2.set(cx - rx, cy - r, cx + rx, cy + r)
        moonPath.reset()
        if (waxing) {
            moonPath.arcTo(oval, -90f, 180f, true)
            moonPath.arcTo(oval2, 90f, if (k < 0.5f) -180f else 180f, false)
        } else {
            moonPath.arcTo(oval, -90f, -180f, true)
            moonPath.arcTo(oval2, 90f, if (k < 0.5f) 180f else -180f, false)
        }
        moonPath.close()
        fill.color = Color.argb((255 * vis).toInt(), 242, 240, 228)
        canvas.drawPath(moonPath, fill)
    }

    /** How busy the scene is, so the caller can lower the frame rate when only clouds move. */
    fun needsFullRate(wx: WeatherState) = wx.rain > 0.02f || wx.snow > 0.02f || wx.storm > 0.5f || flash > 0f
}
