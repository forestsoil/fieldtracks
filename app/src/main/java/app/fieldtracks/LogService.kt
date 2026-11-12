package app.fieldtracks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.Manifest
import java.time.Instant
import java.time.ZoneId

/** Short-lived foreground service: take one fix, append it to today's file, stop. */
class LogService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    private var busy = false
    private var done = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("trk", "Location logging", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "trk")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("FieldTracks").setContentText("Logging location point")
                .setContentIntent(open).build()
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Throwable) {
            Status.err(this, "fgs: " + e.javaClass.simpleName + " " + e.message)
            stopSelf(); return START_NOT_STICKY
        }
        if (busy) return START_NOT_STICKY
        busy = true; done = false
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fieldtracks:fix").apply { acquire(90_000) }
        try { fix() } catch (e: Throwable) {
            Status.err(this, "fix: " + e.javaClass.simpleName + " " + e.message); finish(null, "")
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun fix() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Status.err(this, "no location permission"); finish(null, ""); return
        }
        val lm = getSystemService(LocationManager::class.java)
        val order = listOf(LocationManager.GPS_PROVIDER to 40_000L, LocationManager.NETWORK_PROVIDER to 10_000L)
            .filter { try { lm.isProviderEnabled(it.first) } catch (e: Exception) { false } }
        attempt(lm, order, 0)
    }

    private fun attempt(lm: LocationManager, order: List<Pair<String, Long>>, idx: Int) {
        if (idx >= order.size) {
            val l = try {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            } catch (e: SecurityException) { null }
            finish(l?.takeIf { System.currentTimeMillis() - it.time <= 10 * 60_000L }, "last"); return
        }
        val (prov, timeout) = order[idx]
        val sig = CancellationSignal()
        val t = Runnable { sig.cancel() }
        h.postDelayed(t, timeout)
        try {
            lm.getCurrentLocation(prov, sig, mainExecutor) { loc ->
                h.removeCallbacks(t)
                if (loc != null) finish(loc, if (prov == LocationManager.GPS_PROVIDER) "gps" else "net")
                else attempt(lm, order, idx + 1)
            }
        } catch (e: SecurityException) {
            h.removeCallbacks(t); attempt(lm, order, idx + 1)
        }
    }

    private fun finish(loc: Location?, src: String) {
        if (done) return
        done = true
        try {
            if (loc == null) Status.miss(this) else {
                val (file, n) = TrackStore.append(this, loc, src)
                Status.ok(this, loc.time, n)
                try {
                    TrackStore.publish(this, file, Cfg.load(this).treeUri)
                    Status.clearErr(this)
                } catch (e: Throwable) {
                    Status.err(this, "save: " + e.javaClass.simpleName + " " + e.message)
                }
            }
        } catch (e: Throwable) {
            Status.err(this, "log: " + e.javaClass.simpleName + " " + e.message)
        }
        try { if (Uploader.pending(this).isNotEmpty()) Uploader.kick(this) } catch (e: Throwable) {}
        try { wl?.release() } catch (e: Exception) {}
        busy = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
