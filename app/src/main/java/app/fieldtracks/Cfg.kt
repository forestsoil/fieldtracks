package app.fieldtracks

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class Cfg(
    val startMin: Int = 7 * 60,      // window start, minutes after midnight
    val endMin: Int = 19 * 60,       // window end (inclusive)
    val intervalMin: Int = 5,        // 1-99
    val treeUri: String? = null,     // null = Documents/FieldTracks via MediaStore
    val enabled: Boolean = true
) {
    fun save(c: Context) {
        val e = prefs(c).edit()
            .putInt("start", startMin).putInt("end", endMin)
            .putInt("interval", intervalMin).putBoolean("on", enabled)
        if (treeUri == null) e.remove("tree") else e.putString("tree", treeUri)
        e.apply()
    }

    companion object {
        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("s", Context.MODE_PRIVATE)
        fun load(c: Context): Cfg {
            val p = prefs(c)
            return Cfg(
                p.getInt("start", 7 * 60), p.getInt("end", 19 * 60),
                p.getInt("interval", 5).coerceIn(1, 99),
                p.getString("tree", null), p.getBoolean("on", true)
            )
        }
        fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    }
}

/** Last-run diagnostics shown in the UI. */
object Status {
    private val tf = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")
    private fun stamp() = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    fun ok(c: Context, ts: Long, n: Int) =
        Cfg.prefs(c).edit().putLong("lastTs", ts).putInt("lastN", n).apply()
    fun miss(c: Context) {
        val p = Cfg.prefs(c)
        p.edit().putInt("misses", p.getInt("misses", 0) + 1).putLong("missTs", System.currentTimeMillis()).apply()
    }
    fun err(c: Context, m: String) =
        Cfg.prefs(c).edit().putString("err", stamp() + " " + m.take(300)).apply()
    fun clearErr(c: Context) = Cfg.prefs(c).edit().remove("err").apply()
}
