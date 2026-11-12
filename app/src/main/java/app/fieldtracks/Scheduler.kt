package app.fieldtracks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object Plan {
    private const val MIN = 60_000L

    /** Next tick strictly after [after]. Ticks are start, start+N, start+2N ... <= end, each day. */
    fun next(s: Cfg, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val step = s.intervalMin * MIN
        val d0 = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (i in 0..2) {
            val day = d0.plusDays(i.toLong()).atStartOfDay(zone)
            val start = day.plusMinutes(s.startMin.toLong()).toInstant().toEpochMilli()
            val end = day.plusMinutes(s.endMin.toLong()).toInstant().toEpochMilli()
            if (after < start) return start
            if (after < end) {
                val t = start + ((after - start) / step + 1) * step
                if (t <= end) return t
            }
        }
        return after + 24 * 60 * MIN
    }

    /** Next upload moment: one minute after window end, each day. */
    fun nextUpload(s: Cfg, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val d0 = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (i in 0..2) {
            val t = d0.plusDays(i.toLong()).atStartOfDay(zone).plusMinutes(s.endMin + 1L).toInstant().toEpochMilli()
            if (t > after) return t
        }
        return after + 24 * MIN * 60
    }

    fun inWindow(s: Cfg, t: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        val m = z.hour * 60 + z.minute
        return m in s.startMin..s.endMin
    }
}

object Scheduler {
    private fun pi(c: Context, at: Long?): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        if (at != null) i.putExtra("at", at)
        return PendingIntent.getBroadcast(
            c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun upPi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 2, Intent(c, UploadAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun armUpload(c: Context, s: Cfg, after: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(upPi(c))
        if (!Uploader.configured(c)) return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Plan.nextUpload(s, after), upPi(c))
    }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Arms one alarm for the next tick after [after]. Each firing re-arms the next. */
    fun arm(c: Context, after: Long = System.currentTimeMillis()) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, null))
        val s = Cfg.load(c)
        armUpload(c, s, after)
        if (!s.enabled) { Cfg.prefs(c).edit().remove("nextAt").apply(); return }
        val at = Plan.next(s, after)
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, at))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, at))
        } catch (x: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, at))
        }
        Cfg.prefs(c).edit().putLong("nextAt", at).apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val now = System.currentTimeMillis()
        val at = i.getLongExtra("at", now)
        Scheduler.arm(c, maxOf(at, now))          // re-arm first so the chain never breaks
        val s = Cfg.load(c)
        if (!s.enabled || !Plan.inWindow(s, at)) return
        try {
            c.startForegroundService(Intent(c, LogService::class.java))
        } catch (e: Throwable) {
            Status.err(c, "start: " + e.javaClass.simpleName + " " + e.message)
        }
    }
}

class UploadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.armUpload(c, Cfg.load(c), System.currentTimeMillis() + 1000)
        Uploader.kick(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Cfg.prefs(c).edit().putLong("lastBoot", System.currentTimeMillis()).apply()
        Scheduler.arm(c)
        Uploader.kick(c)
    }
}
