package ir.daftarak.weatherwall

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.SystemClock
import java.util.Calendar

/** Glue between settings, weather data, astronomy and the renderer. Used by the wallpaper and the in-app preview. */
class SceneController(ctx: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val app = ctx.applicationContext
    private val renderer = Renderer(app)
    @Volatile var config: Config = Config.load(app)
        private set

    private val startReal = SystemClock.elapsedRealtime()
    private var lastFrame = 0L
    private var astro: AstroState? = null
    private var astroAt = 0L
    private var astroKey = ""
    var lastWeather: WeatherState = WeatherState()
        private set

    init { Config.prefs(app).registerOnSharedPreferenceChangeListener(this) }

    fun release() { Config.prefs(app).unregisterOnSharedPreferenceChangeListener(this) }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        config = Config.load(app)
        astroAt = 0L
    }

    private fun sceneTime(): Long {
        val now = System.currentTimeMillis()
        if (!config.demo) return now
        // Demo: a whole day in two minutes.
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val elapsed = SystemClock.elapsedRealtime() - startReal
        return cal.timeInMillis + ((elapsed * 720L + 6 * 3600_000L) % 86400_000L)
    }

    /** Draws one frame. [launcherOffset] is the home screen scroll position or null if unknown. Returns the wanted fps. */
    fun frame(canvas: Canvas, w: Int, h: Int, launcherOffset: Float?): Int {
        val cfg = config
        val nowReal = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.016f else ((nowReal - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = nowReal

        val ms = sceneTime()
        val key = "${cfg.lat},${cfg.lon},${cfg.demo}"
        if (astro == null || key != astroKey || kotlin.math.abs(ms - astroAt) > (if (cfg.demo) 20_000L else 30_000L)) {
            astro = Astro.compute(ms, cfg.lat, cfg.lon)
            astroAt = ms
            astroKey = key
        }

        val wx = WeatherState.preset(cfg.mode)
            ?: WeatherRepo.forecast(app)?.at(ms)
            ?: WeatherState()
        lastWeather = wx

        val pos = if (cfg.parallax && launcherOffset != null) {
            // keep the tower in view: pan across 70% of the free width around the chosen position
            (cfg.position + (launcherOffset - 0.5f) * 0.7f).coerceIn(0f, 1f)
        } else cfg.position

        val t = (nowReal - startReal) / 1000.0
        renderer.draw(canvas, w, h, pos, t, dt, astro!!, wx)
        return if (renderer.needsFullRate(wx)) cfg.fps else maxOf(12, cfg.fps / 2)
    }
}
