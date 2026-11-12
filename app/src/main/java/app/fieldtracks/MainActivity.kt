package app.fieldtracks

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private var cfg = Cfg()
    private lateinit var statusText: TextView
    private lateinit var destText: TextView
    private lateinit var errText: TextView
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var intervalIn: EditText
    private lateinit var emailIn: EditText
    private lateinit var projSpin: Spinner
    private lateinit var onSwitch: Switch
    private val dt = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, sp: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    private fun btn(label: String, f: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { safe(f) }
    }
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Cfg.load(this)
        statusText = tv("", 15f); destText = tv("", 15f); errText = tv("", 13f)
        startBtn = btn("") { pick(cfg.startMin) { cfg = cfg.copy(startMin = it); labels() } }
        endBtn = btn("") { pick(cfg.endMin) { cfg = cfg.copy(endMin = it); labels() } }
        intervalIn = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(cfg.intervalMin.toString()); gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        emailIn = EditText(this).apply {
            hint = "Email (filled by ARR Suite link)"; setText(cfg.email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        projSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Projects.all.map { it.label + (if (it.enabled) "" else " (disabled)") })
            setSelection(Projects.all.indexOfFirst { it.key == cfg.project }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val pr = Projects.all[pos]
                    if (!pr.enabled) {
                        Toast.makeText(this@MainActivity, "Not enabled yet", Toast.LENGTH_SHORT).show()
                        setSelection(Projects.all.indexOfFirst { it.key == cfg.project }.coerceAtLeast(0))
                    } else if (pr.key != cfg.project) {
                        cfg = cfg.copy(project = pr.key); cfg.save(this@MainActivity); Uploader.kick(this@MainActivity)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        onSwitch = Switch(this).apply { text = "Logging on"; isChecked = cfg.enabled; textSize = 16f }

        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tv("Log every (1-99 min)").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(intervalIn)
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(endBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28))
            addView(tv("FieldTracks", 26f, true))
            addView(statusText)
            addView(btn("Grant permissions") { grantStep() })
            addView(btn("Battery: set unrestricted") { batteryOff() })
            addView(tv("Logging window", 16f, true)); addView(timeRow); addView(intervalRow); addView(tv("Project")); addView(projSpin); addView(emailIn); addView(onSwitch)
            addView(btn("Save settings") { save() })
            addView(tv("Save to", 16f, true)); addView(destText)
            addView(btn("Choose folder...") { chooseFolder() })
            addView(btn("Use default (Documents/FieldTracks)") { useDefault() })
            addView(btn("Upload now") {
                Cfg.prefs(this@MainActivity).edit().putBoolean("force", true).apply()
                Uploader.kick(this@MainActivity)
                Toast.makeText(this@MainActivity, "Upload queued", Toast.LENGTH_SHORT).show()
            })
            addView(btn("Log a point now") { startForegroundService(Intent(this@MainActivity, LogService::class.java)) })
            addView(errText)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        labels()
        safe { handleLink(intent) }
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        safe { handleLink(i) }
    }

    private fun handleLink(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "fieldtracks") return
        val e = u.getQueryParameter("email")?.trim()?.lowercase()
        val pj = u.getQueryParameter("project")?.trim()?.lowercase()
        if (pj != null && Projects.all.any { it.key == pj && it.enabled }) cfg = cfg.copy(project = pj)
        if (!e.isNullOrBlank()) {
            cfg = cfg.copy(email = e); cfg.save(this); emailIn.setText(e)
            Toast.makeText(this, "Linked: $e", Toast.LENGTH_LONG).show()
            Uploader.kick(this)
        }
    }

    override fun onResume() {
        super.onResume()
        safe { cfg = Cfg.load(this); labels(); Scheduler.arm(this); Uploader.kick(this); refresh() }
    }

    private fun safe(f: () -> Unit) {
        try { f() } catch (e: Throwable) {
            errText.text = "ERROR:\n" + android.util.Log.getStackTraceString(e).take(1500)
        }
    }

    private fun labels() {
        startBtn.text = "Start  " + Cfg.hm(cfg.startMin)
        endBtn.text = "End  " + Cfg.hm(cfg.endMin)
    }

    private fun pick(cur: Int, set: (Int) -> Unit) {
        TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { _, h, m -> set(h * 60 + m) }, cur / 60, cur % 60, true).show()
    }

    private fun save() {
        val iv = (intervalIn.text.toString().toIntOrNull() ?: 5).coerceIn(1, 99)
        intervalIn.setText(iv.toString())
        if (cfg.endMin <= cfg.startMin) { Toast.makeText(this, "End must be after start", Toast.LENGTH_LONG).show(); return }
        cfg = cfg.copy(intervalMin = iv, enabled = onSwitch.isChecked, email = emailIn.text.toString().trim().lowercase())
        cfg.save(this); Scheduler.arm(this); refresh()
    }

    // ---- permissions -------------------------------------------------------------------
    private fun grantStep() {
        val need = ArrayList<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            need += Manifest.permission.ACCESS_FINE_LOCATION; need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        if (!has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            AlertDialog.Builder(this).setTitle("Allow location all the time")
                .setMessage("On the next screen choose Location > \"Allow all the time\" (keep \"Use precise location\" on). Needed to log while the screen is off.")
                .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        safe {
            if (code == 1 && (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION))) grantStep()
            refresh()
        }
    }

    private fun batteryOff() {
        startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ---- save location -----------------------------------------------------------------
    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(i, 5)
    }

    private fun useDefault() { cfg = cfg.copy(treeUri = null); cfg.save(this); republish(); refresh() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, res: Int, data: Intent?) {
        super.onActivityResult(code, res, data)
        safe {
            val u = data?.data
            if (code == 5 && res == RESULT_OK && u != null) {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                cfg = cfg.copy(treeUri = u.toString()); cfg.save(this); republish(); refresh()
            }
        }
    }

    private fun republish() {
        val tree = cfg.treeUri
        Thread {
            val e = TrackStore.publishAll(this, tree)
            if (e != null) Status.err(this, "export: $e") else Status.clearErr(this)
            runOnUiThread { safe { refresh() } }
        }.start()
    }

    // ---- status ------------------------------------------------------------------------
    private fun refresh() {
        val z = ZoneId.systemDefault()
        fun t(ms: Long) = dt.format(Instant.ofEpochMilli(ms).atZone(z))
        val p = Cfg.prefs(this)
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bg = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val lines = ArrayList<String>()
        lines += "Location: " + (if (fine) "precise" else if (coarse) "approximate only" else "NOT GRANTED")
        lines += "All the time: " + (if (bg) "yes" else "NO (logging will fail with screen off)")
        lines += "Notifications: " + (if (notif) "yes" else "no (logging still works)")
        lines += "Battery unrestricted: " + (if (batt) "yes" else "no (set it, or points may be skipped)")
        lines += "Exact alarms: " + (if (Scheduler.canExact(this)) "yes" else "NO (points may be late)")
        val next = p.getLong("nextAt", 0L)
        lines += "Next point: " + (if (!cfg.enabled) "off" else if (next > 0) t(next) else "-")
        val last = p.getLong("lastTs", 0L)
        lines += "Last point: " + (if (last > 0) t(last) else "-") + "   today: " + TrackStore.todayCount(this)
        val upTs = p.getLong("upTs", 0L)
        lines += "Upload: " + (if (!Uploader.configured(this)) "not configured in this build"
            else if (cfg.email.isBlank()) "email not set"
            else "last ok " + (if (upTs > 0) t(upTs) else "-") + ", pending " + Uploader.pending(this).size)
        lines += "No-fix misses: " + p.getInt("misses", 0)
        statusText.text = lines.joinToString("\n")
        destText.text = (cfg.treeUri?.let { Uri.parse(it).lastPathSegment } ?: "Documents/FieldTracks/ (default)") +
            "\nfieldtracks_yyyymmdd.json"
        errText.text = listOfNotNull(p.getString("err", null), p.getString("upErr", null)).joinToString("\n") { "Last error: $it" }
    }
}
