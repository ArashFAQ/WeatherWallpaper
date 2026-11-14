package ir.daftarak.weatherwall

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the newest APK from the GitHub release and installs it. */
object Updater {
    const val APK_URL = "https://github.com/ArashFAQ/WeatherWallpaper/releases/latest/download/app.apk"
    private const val ACTION = "ir.daftarak.weatherwall.INSTALL_STATUS"

    fun start(act: Activity, status: (String) -> Unit) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            status("اول اجازه‌ی نصب از این برنامه را بدهید، بعد دوباره دکمه را بزنید.")
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
            )
            return
        }
        status("در حال دانلود…")
        Thread {
            try {
                val file = File(act.cacheDir, "update.apk")
                var url = URL(APK_URL)
                var con = open(url)
                var hops = 0
                while (con.responseCode in 300..399 && hops < 6) {
                    url = URL(url, con.getHeaderField("Location"))
                    con.disconnect()
                    con = open(url)
                    hops++
                }
                if (con.responseCode != 200) throw Exception("HTTP ${con.responseCode}")
                val total = con.contentLengthLong
                con.inputStream.use { inp ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    act.runOnUiThread { status("در حال دانلود… $pct٪") }
                                }
                            }
                        }
                    }
                }
                act.runOnUiThread {
                    status("در حال نصب…")
                    install(act, file, status)
                }
            } catch (e: Exception) {
                act.runOnUiThread { status("دانلود ناموفق: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20000
        readTimeout = 60000
    }

    private fun install(act: Activity, file: File, status: (String) -> Unit) {
        try {
            val installer = act.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (confirm != null) {
                                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    act.startActivity(confirm)
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> status("نصب شد.")
                            else -> status("نصب انجام نشد: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
                        }
                    }
                }
                val filter = IntentFilter(ACTION)
                if (Build.VERSION.SDK_INT >= 33) {
                    act.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    act.applicationContext.registerReceiver(receiver, filter)
                }
                val intent = Intent(ACTION).setPackage(act.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(act, id, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            status("نصب ناموفق: " + (e.message ?: e.javaClass.simpleName))
        }
    }
}
