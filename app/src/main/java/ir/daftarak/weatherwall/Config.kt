package ir.daftarak.weatherwall

import android.content.Context
import android.content.SharedPreferences

data class City(val name: String, val lat: Double, val lon: Double)

val CITIES = listOf(
    City("تهران", 35.6892, 51.3890),
    City("کرج", 35.8400, 50.9391),
    City("اصفهان", 32.6546, 51.6680),
    City("مشهد", 36.2605, 59.6168),
    City("شیراز", 29.5918, 52.5837),
    City("تبریز", 38.0800, 46.2919),
    City("رشت", 37.2808, 49.5832),
    City("قم", 34.6399, 50.8759),
    City("اهواز", 31.3183, 48.6706),
    City("کرمانشاه", 34.3142, 47.0650),
    City("یزد", 31.8974, 54.3569),
    City("کرمان", 30.2839, 57.0834),
    City("ارومیه", 37.5527, 45.0761),
    City("ساری", 36.5633, 53.0601),
    City("همدان", 34.7989, 48.5146),
    City("بندرعباس", 27.1832, 56.2666),
)

val WEATHER_MODES = listOf(
    "auto" to "خودکار (آب‌وهوای واقعی)",
    "clear" to "صاف",
    "cloudy" to "ابری",
    "rain" to "بارانی",
    "snow" to "برفی",
    "storm" to "رعد و برق",
    "fog" to "مه",
)

val FPS_OPTIONS = listOf(
    20 to "کم‌مصرف (۲۰ فریم)",
    30 to "معمولی (۳۰ فریم)",
    60 to "روان (۶۰ فریم)",
)

data class Config(
    val cityName: String,
    val lat: Double,
    val lon: Double,
    val mode: String,
    val demo: Boolean,
    val position: Float,
    val parallax: Boolean,
    val fps: Int,
) {
    companion object {
        const val PREFS = "settings"

        fun prefs(ctx: Context): SharedPreferences =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): Config {
            val p = prefs(ctx)
            return Config(
                cityName = p.getString("city", "تهران") ?: "تهران",
                lat = p.getFloat("lat", 35.6892f).toDouble(),
                lon = p.getFloat("lon", 51.3890f).toDouble(),
                mode = p.getString("mode", "auto") ?: "auto",
                demo = p.getBoolean("demo", false),
                position = p.getFloat("position", 0.2f),
                parallax = p.getBoolean("parallax", true),
                fps = p.getInt("fps", 30),
            )
        }
    }
}
