package ir.daftarak.weatherwall

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Everything the renderer needs to know about the weather, all values 0..1 except wind (km/h). */
data class WeatherState(
    val cloud: Float = 0.2f,
    val rain: Float = 0f,
    val snow: Float = 0f,
    val fog: Float = 0f,
    val storm: Float = 0f,
    val snowGround: Float = 0f,
    val wind: Float = 8f,
    val code: Int = 1,
) {
    companion object {
        fun preset(mode: String): WeatherState? = when (mode) {
            "clear" -> WeatherState(cloud = 0.05f, code = 0)
            "cloudy" -> WeatherState(cloud = 0.8f, code = 3)
            "rain" -> WeatherState(cloud = 0.95f, rain = 0.7f, wind = 15f, code = 63)
            "snow" -> WeatherState(cloud = 0.95f, snow = 0.7f, snowGround = 1f, code = 73)
            "storm" -> WeatherState(cloud = 1f, rain = 1f, storm = 1f, wind = 30f, code = 95)
            "fog" -> WeatherState(cloud = 0.5f, fog = 0.9f, code = 45)
            else -> null
        }

        fun lerp(a: WeatherState, b: WeatherState, t: Float): WeatherState {
            fun l(x: Float, y: Float) = x + (y - x) * t
            return WeatherState(
                l(a.cloud, b.cloud), l(a.rain, b.rain), l(a.snow, b.snow), l(a.fog, b.fog),
                l(a.storm, b.storm), l(a.snowGround, b.snowGround), l(a.wind, b.wind),
                if (t < 0.5f) a.code else b.code,
            )
        }

        fun describe(code: Int): String = when (code) {
            0 -> "صاف"
            1, 2 -> "کمی ابری"
            3 -> "ابری"
            45, 48 -> "مه"
            in 51..57 -> "نم‌نم باران"
            in 61..67 -> "باران"
            in 71..77 -> "برف"
            in 80..82 -> "رگبار"
            85, 86 -> "بارش برف"
            in 95..99 -> "رعد و برق"
            else -> "نامشخص"
        }
    }
}

class Forecast(val times: LongArray, val states: Array<WeatherState>, val lat: Double, val lon: Double) {
    fun at(ms: Long): WeatherState? {
        if (times.isEmpty()) return null
        val s = ms / 1000
        if (s <= times[0]) return states[0]
        if (s >= times[times.size - 1]) {
            // Forecast ran out (offline for a week): don't keep showing stale rain.
            return if (s - times[times.size - 1] < 3 * 3600) states[states.size - 1] else null
        }
        var i = 0
        while (i < times.size - 1 && times[i + 1] <= s) i++
        val t = ((s - times[i]).toFloat() / (times[i + 1] - times[i]).toFloat()).coerceIn(0f, 1f)
        return WeatherState.lerp(states[i], states[i + 1], t)
    }

    companion object {
        fun parse(json: String): Forecast {
            val root = JSONObject(json)
            val h = root.getJSONObject("hourly")
            val time = h.getJSONArray("time")
            val n = time.length()
            fun arr(name: String): DoubleArray {
                val a = h.optJSONArray(name)
                return DoubleArray(n) { i -> a?.optDouble(i, 0.0)?.takeUnless { it.isNaN() } ?: 0.0 }
            }
            val code = arr("weather_code")
            val cloud = arr("cloud_cover")
            val rain = arr("rain")
            val showers = arr("showers")
            val snowfall = arr("snowfall")
            val vis = h.optJSONArray("visibility")
            val depth = arr("snow_depth")
            val wind = arr("wind_speed_10m")

            val times = LongArray(n) { time.getLong(it) }
            val states = Array(n) { i ->
                val c = code[i].toInt()
                val isSnow = c in 71..77 || c == 85 || c == 86
                val isRain = c in 51..67 || c in 80..82 || c >= 95
                val mm = rain[i] + showers[i]
                val rainBase = when (c) {
                    in 51..57 -> 0.25; 61 -> 0.35; 63 -> 0.6; 65 -> 0.9; 66, 67 -> 0.5
                    80 -> 0.4; 81 -> 0.7; 82 -> 1.0; in 95..99 -> 0.85; else -> 0.0
                }
                val rainI = if (isSnow) 0.0 else if (isRain) max(rainBase, min(1.0, mm / 3.0)) else min(0.4, mm / 3.0)
                val snowBase = when (c) {
                    71 -> 0.35; 73 -> 0.6; 75 -> 0.95; 77 -> 0.3; 85 -> 0.5; 86 -> 0.9; else -> 0.0
                }
                val snowI = if (isSnow) max(snowBase, min(1.0, snowfall[i] / 1.2)) else min(0.4, snowfall[i] / 1.2)
                var fog = if (c == 45 || c == 48) 0.85 else 0.0
                val v = vis?.optDouble(i, Double.NaN) ?: Double.NaN
                if (!v.isNaN() && v < 2000) fog = max(fog, (1.0 - v / 2000.0) * 0.9)
                var cl = cloud[i] / 100.0
                if (rainI > 0.05 || snowI > 0.05) cl = max(cl, 0.85)
                var recentSnow = 0.0
                for (k in max(0, i - 24)..i) recentSnow += snowfall[k]
                val ground = max(min(1.0, depth[i] / 0.03), if (recentSnow >= 1.0) 1.0 else 0.0)
                WeatherState(
                    cloud = cl.toFloat(), rain = rainI.toFloat(), snow = snowI.toFloat(),
                    fog = fog.toFloat(), storm = if (c >= 95) 1f else 0f,
                    snowGround = ground.toFloat(), wind = wind[i].toFloat(), code = c,
                )
            }
            return Forecast(times, states, root.optDouble("latitude", 0.0), root.optDouble("longitude", 0.0))
        }
    }
}

object WeatherRepo {
    private const val FILE = "forecast.json"
    private const val OK_INTERVAL = 60 * 60 * 1000L
    private const val FAIL_INTERVAL = 15 * 60 * 1000L

    @Volatile private var cached: Forecast? = null
    @Volatile private var cachedStamp = 0L
    @Volatile private var fetching = false
    @Volatile private var lastAttempt = 0L

    fun forecast(ctx: Context): Forecast? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        if (cached == null || stamp != cachedStamp) {
            cached = try { Forecast.parse(f.readText()) } catch (e: Exception) { null }
            cachedStamp = stamp
        }
        return cached
    }

    fun refreshIfNeeded(ctx: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val p = Config.prefs(app)
        val cfg = Config.load(app)
        val lastOk = p.getLong("wx_ok_time", 0L)
        val sameCity = p.getString("wx_city", "") == "${cfg.lat},${cfg.lon}"
        val due = force || !sameCity ||
            (now - lastOk > OK_INTERVAL && now - lastAttempt > FAIL_INTERVAL)
        if (fetching || !due) { done?.invoke(false); return }
        fetching = true
        lastAttempt = now
        Thread {
            var ok = false
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}" +
                        "&hourly=weather_code,cloud_cover,rain,showers,snowfall,visibility,snow_depth,wind_speed_10m" +
                        "&past_days=1&forecast_days=7&timeformat=unixtime&timezone=auto"
                )
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 20000
                val code = con.responseCode
                if (code != 200) throw Exception("HTTP $code")
                val body = con.inputStream.bufferedReader().use { it.readText() }
                Forecast.parse(body) // validate before saving
                val tmp = File(app.filesDir, "$FILE.tmp")
                tmp.writeText(body)
                tmp.renameTo(File(app.filesDir, FILE))
                p.edit()
                    .putLong("wx_ok_time", System.currentTimeMillis())
                    .putString("wx_city", "${cfg.lat},${cfg.lon}")
                    .putString("wx_status", "آخرین بروزرسانی موفق: " + fmt(System.currentTimeMillis()))
                    .apply()
                ok = true
            } catch (e: Exception) {
                p.edit().putString(
                    "wx_status",
                    "بروزرسانی ناموفق (" + fmt(System.currentTimeMillis()) + "): " + (e.message ?: e.javaClass.simpleName) +
                        if (lastOk > 0) "\nپیش‌بینی ذخیره‌شده‌ی قبلی استفاده می‌شود." else ""
                ).apply()
            } finally {
                fetching = false
                done?.invoke(ok)
            }
        }.start()
    }

    private fun fmt(ms: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))
}
