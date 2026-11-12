package app.fieldtracks

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Uploader {
    fun configured(c: Context) = Projects.active(c).url.isNotEmpty() && BuildConfig.SECRET.isNotEmpty()

    private fun upKey(c: Context, f: File) = "up_" + Projects.active(c).key + "_" + f.name

    private fun dateOf(f: File): LocalDate? = try {
        LocalDate.parse(f.name.substring(11, 19), DateTimeFormatter.BASIC_ISO_DATE)
    } catch (e: Exception) { null }

    private fun count(f: File): Int = try {
        JSONObject(f.readText()).getJSONArray("points").length()
    } catch (e: Exception) { -2 }

    /** Days ready to send: before today, or today once the window has ended (or forced), and changed since last send. */
    fun pending(c: Context): List<File> {
        val p = Cfg.prefs(c)
        val s = Cfg.load(c)
        val now = ZonedDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        val today = now.toLocalDate()
        val force = p.getBoolean("force", false)
        return TrackStore.allFiles(c).filter { f ->
            val d = dateOf(f)
            if (d == null) false else {
                val ready = d.isBefore(today) || (d == today && (nowMin > s.endMin || force))
                ready && p.getInt(upKey(c, f), -1) != count(f)
            }
        }
    }

    fun kick(c: Context) {
        if (!configured(c)) return
        val js = c.getSystemService(JobScheduler::class.java)
        js.schedule(
            JobInfo.Builder(1, ComponentName(c, UploadJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()
        )
    }

    /** null = ok or nothing to do; otherwise an error string. */
    fun sendAll(c: Context): String? {
        if (!configured(c)) return null
        val email = Cfg.load(c).email
        if (email.isBlank()) return null
        val p = Cfg.prefs(c)
        for (f in pending(c)) {
            try {
                post(c, email, f)
                p.edit().putInt(upKey(c, f), count(f)).putLong("upTs", System.currentTimeMillis()).apply()
            } catch (e: Throwable) {
                return e.javaClass.simpleName + " " + e.message
            }
        }
        p.edit().remove("force").apply()
        return null
    }

    private fun status(c: Context): JSONObject {
        val s = Cfg.load(c)
        val p = Cfg.prefs(c)
        return JSONObject()
            .put("ver", BuildConfig.VERSION_NAME)
            .put("enabled", s.enabled).put("interval", s.intervalMin)
            .put("start", Cfg.hm(s.startMin)).put("end", Cfg.hm(s.endMin))
            .put("bgLocation", c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
            .put("batteryUnrestricted", c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName))
            .put("lastBoot", p.getLong("lastBoot", 0L)).put("lastPoint", p.getLong("lastTs", 0L))
            .put("misses", p.getInt("misses", 0))
    }

    private fun post(c: Context, email: String, f: File) {
        val body = JSONObject()
            .put("action", "ftTrack").put("secret", BuildConfig.SECRET).put("project", Projects.active(c).key)
            .put("email", email).put("date", dateOf(f).toString())
            .put("track", JSONObject(f.readText())).put("status", status(c))
            .toString()
        val cx = URL(Projects.active(c).url).openConnection() as HttpURLConnection
        try {
            cx.requestMethod = "POST"; cx.doOutput = true
            cx.connectTimeout = 20_000; cx.readTimeout = 30_000
            cx.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            cx.outputStream.use { it.write(body.toByteArray()) }
            val code = cx.responseCode   // GAS answers 302; Java follows it with GET and returns the result
            val txt = (if (code in 200..299) cx.inputStream else cx.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299 || !txt.contains("\"ok\":true")) throw IOException("HTTP $code " + txt.take(120))
        } finally { cx.disconnect() }
    }
}

class UploadJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            val err = try { Uploader.sendAll(this) } catch (e: Throwable) { e.javaClass.simpleName + " " + e.message }
            if (err == null) Status.upClear(this) else Status.upErr(this, err)
            jobFinished(p, err != null)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters): Boolean = true
}
