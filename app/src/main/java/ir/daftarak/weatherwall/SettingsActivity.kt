package ir.daftarak.weatherwall

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Live preview of the scene inside the settings screen. */
class PreviewView(ctx: Context) : View(ctx) {
    private val controller = SceneController(ctx)
    var running = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        controller.frame(canvas, width, height, null)
        if (running) postInvalidateDelayed(33)
    }

    fun weatherNow() = controller.lastWeather

    fun release() { running = false; controller.release() }
}

class SettingsActivity : Activity() {
    private lateinit var preview: PreviewView
    private lateinit var statusText: TextView
    private lateinit var nowText: TextView
    private lateinit var updateText: TextView
    private val prefs by lazy { Config.prefs(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))

        nowText = label("", 14f)
        root.addView(nowText)

        root.addView(button("تنظیم به‌عنوان والپیپر") { setWallpaper() })

        // ---- city ----
        root.addView(title("شهر"))
        val cityNames = CITIES.map { it.name } + "موقعیت فعلی من"
        val citySpinner = spinner(cityNames)
        val savedIdx = CITIES.indexOfFirst { it.name == cfg.cityName }
        citySpinner.setSelection(if (savedIdx >= 0) savedIdx else cityNames.size - 1)
        citySpinner.onItemSelectedListener = onSelect { i ->
            if (i < CITIES.size) {
                val c = CITIES[i]
                if (c.name != Config.load(this).cityName) {
                    prefs.edit().putString("city", c.name).putFloat("lat", c.lat.toFloat()).putFloat("lon", c.lon.toFloat()).apply()
                    refreshWeather(true)
                }
            } else if (Config.load(this).cityName != "موقعیت من") {
                useMyLocation()
            }
        }
        root.addView(citySpinner)

        // ---- weather ----
        root.addView(title("آب‌وهوا"))
        statusText = label(prefs.getString("wx_status", "هنوز بروزرسانی نشده") ?: "", 13f)
        root.addView(statusText)
        root.addView(button("بروزرسانی آب‌وهوا الان") { refreshWeather(true) })

        root.addView(title("نمایش آزمایشی آب‌وهوا"))
        val modeSpinner = spinner(WEATHER_MODES.map { it.second })
        modeSpinner.setSelection(WEATHER_MODES.indexOfFirst { it.first == cfg.mode }.coerceAtLeast(0))
        modeSpinner.onItemSelectedListener = onSelect { i -> prefs.edit().putString("mode", WEATHER_MODES[i].first).apply() }
        root.addView(modeSpinner)

        root.addView(CheckBox(this).apply {
            text = "نمایش سریع یک شبانه‌روز (هر ۲ دقیقه یک روز)"
            isChecked = cfg.demo
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("demo", b).apply() }
        })

        // ---- view ----
        root.addView(title("جای منظره"))
        root.addView(SeekBar(this).apply {
            max = 100
            progress = (cfg.position * 100).toInt()
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putFloat("position", p / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        root.addView(CheckBox(this).apply {
            text = "حرکت منظره با ورق زدن صفحه‌ها (اگر لانچر پشتیبانی کند)"
            isChecked = cfg.parallax
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean("parallax", b).apply() }
        })

        root.addView(title("روانی انیمیشن"))
        val fpsSpinner = spinner(FPS_OPTIONS.map { it.second })
        fpsSpinner.setSelection(FPS_OPTIONS.indexOfFirst { it.first == cfg.fps }.coerceAtLeast(0))
        fpsSpinner.onItemSelectedListener = onSelect { i -> prefs.edit().putInt("fps", FPS_OPTIONS[i].first).apply() }
        root.addView(fpsSpinner)

        // ---- update ----
        root.addView(title("نسخه‌ی برنامه"))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        root.addView(label("نسخه‌ی فعلی: $ver", 13f))
        updateText = label("", 13f)
        root.addView(button("دانلود و نصب آخرین نسخه از GitHub") {
            Updater.start(this) { s -> updateText.text = s }
        })
        root.addView(updateText)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshWeather(false)
        tickNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.release()
    }

    private fun tickNow() {
        val w = preview.weatherNow()
        nowText.text = "الان: " + WeatherState.describe(w.code) +
            (if (Config.load(this).mode != "auto") "  (حالت آزمایشی)" else "")
        if (!isFinishing) nowText.postDelayed({ tickNow() }, 2000)
    }

    private fun refreshWeather(force: Boolean) {
        if (force) statusText.text = "در حال گرفتن پیش‌بینی…"
        WeatherRepo.refreshIfNeeded(this, force) {
            runOnUiThread { statusText.text = prefs.getString("wx_status", "") }
        }
    }

    private fun setWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, WeatherWallpaperService::class.java)
            ))
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    // ---------- location ----------
    private fun useMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (_: SecurityException) {}
        if (best != null) { saveLocation(best!!); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) { toast("مکان‌یابی گوشی خاموش است"); return }
            toast("در حال پیدا کردن موقعیت…")
            try {
                lm.getCurrentLocation(provider, null, mainExecutor) { l ->
                    if (l != null) saveLocation(l) else toast("موقعیت پیدا نشد")
                }
            } catch (_: SecurityException) {}
        } else toast("موقعیت پیدا نشد")
    }

    private fun saveLocation(l: Location) {
        prefs.edit().putString("city", "موقعیت من")
            .putFloat("lat", l.latitude.toFloat()).putFloat("lon", l.longitude.toFloat()).apply()
        toast("موقعیت ذخیره شد")
        refreshWeather(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) useMyLocation()
        else toast("بدون اجازه‌ی مکان، یک شهر را از فهرست انتخاب کنید")
    }

    // ---------- tiny UI helpers ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
        gravity = Gravity.START
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun onSelect(f: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        private var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (first) { first = false; return }
            f(pos)
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
}
