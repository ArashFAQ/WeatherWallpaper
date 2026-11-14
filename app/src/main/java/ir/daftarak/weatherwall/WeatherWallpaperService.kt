package ir.daftarak.weatherwall

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class WeatherWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallEngine()

    inner class WallEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var controller: SceneController? = null
        private var visible = false
        private var width = 0
        private var height = 0
        private var offset: Float? = null
        private var lastWeatherCheck = 0L

        private val drawRunnable = object : Runnable {
            override fun run() {
                val fps = drawFrame()
                handler.removeCallbacks(this)
                if (visible) handler.postDelayed(this, (1000L / fps.coerceAtLeast(1)))
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            controller = SceneController(applicationContext)
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunnable)
            controller?.release()
            controller = null
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                checkWeather()
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w; height = h
            handler.post(drawRunnable)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            // Only trust offsets from launchers that actually report page scrolling.
            offset = if (xStep > 0f && xStep < 1f) xOffset else null
            if (visible) handler.post(drawRunnable)
        }

        private fun checkWeather() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWeatherCheck < 60_000L) return
            lastWeatherCheck = now
            WeatherRepo.refreshIfNeeded(applicationContext)
        }

        private fun drawFrame(): Int {
            val c = controller ?: return 1
            if (width == 0 || height == 0) return 1
            val holder = surfaceHolder
            var canvas: Canvas? = null
            var fps = 30
            try {
                canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
                if (canvas != null) fps = c.frame(canvas, width, height, offset)
            } catch (e: Exception) {
                // never crash the wallpaper process
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
            checkWeather()
            return fps
        }
    }
}
