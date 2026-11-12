package app.fieldtracks

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Canonical copy: filesDir/tracks/fieldtracks_yyyymmdd.json (private, never lost).
 * Published copy: rewritten in full after every point to
 *   - Documents/FieldTracks/ (MediaStore, no storage permission needed), or
 *   - a folder the user picked (SAF tree).
 */
object TrackStore {
    const val REL = "Documents/FieldTracks/"
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun name(d: LocalDate) = "fieldtracks_" + d.format(DateTimeFormatter.BASIC_ISO_DATE) + ".json"
    private fun dir(c: Context) = File(c.filesDir, "tracks").also { it.mkdirs() }
    fun fileFor(c: Context, d: LocalDate) = File(dir(c), name(d))
    fun allFiles(c: Context): List<File> =
        dir(c).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun r6(x: Double) = Math.round(x * 1e6) / 1e6
    private fun r1(x: Double) = Math.round(x * 10) / 10.0

    @Synchronized
    fun append(c: Context, loc: Location, src: String): Pair<File, Int> {
        val z = Instant.ofEpochMilli(loc.time).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS)
        val f = fileFor(c, z.toLocalDate())
        var root: JSONObject? = null
        if (f.exists()) try { root = JSONObject(f.readText()) } catch (e: Exception) { f.renameTo(File(f.path + ".bad")) }
        val r = root ?: JSONObject()
            .put("schema", "fieldtracks/1")
            .put("date", z.toLocalDate().toString())
            .put("tz", z.offset.id)
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("points", JSONArray())
        val p = JSONObject().put("t", iso.format(z)).put("lat", r6(loc.latitude)).put("lon", r6(loc.longitude))
        if (loc.hasAccuracy()) p.put("acc", r1(loc.accuracy.toDouble()))
        if (loc.hasAltitude()) p.put("alt", r1(loc.altitude))
        if (loc.hasSpeed()) p.put("spd", r1(loc.speed.toDouble()))
        p.put("src", src)
        val pts = r.getJSONArray("points")
        val n0 = pts.length()
        if (n0 > 0 && pts.getJSONObject(n0 - 1).optString("t") == p.getString("t")) return Pair(f, n0) // same fix twice
        pts.put(p)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(r.toString(1))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return Pair(f, pts.length())
    }

    fun todayCount(c: Context): Int = try {
        val f = fileFor(c, LocalDate.now())
        if (f.exists()) JSONObject(f.readText()).getJSONArray("points").length() else 0
    } catch (e: Exception) { 0 }

    fun publish(c: Context, f: File, tree: String?) {
        val bytes = f.readBytes()
        if (tree != null) publishTree(c, Uri.parse(tree), f.name, bytes) else publishDefault(c, f.name, bytes)
    }

    /** Re-export every stored day (after the user changes the save folder). */
    fun publishAll(c: Context, tree: String?): String? {
        var err: String? = null
        for (f in allFiles(c)) try { publish(c, f, tree) } catch (e: Throwable) { err = e.javaClass.simpleName + " " + e.message }
        return err
    }

    private fun publishDefault(c: Context, name: String, bytes: ByteArray) {
        val r = c.contentResolver
        val coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val key = "pub_$name"
        val prefs = Cfg.prefs(c)
        // 1. URI remembered from an earlier write
        prefs.getString(key, null)?.let { s ->
            try { r.openOutputStream(Uri.parse(s), "wt")!!.use { it.write(bytes) }; return } catch (e: Exception) {}
        }
        // 2. existing entry that we own
        var found: Uri? = null
        r.query(
            coll, arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(REL, name), null
        )?.use { if (it.moveToFirst()) found = ContentUris.withAppendedId(coll, it.getLong(0)) }
        val u = found
        if (u != null) try {
            r.openOutputStream(u, "wt")!!.use { it.write(bytes) }
            prefs.edit().putString(key, u.toString()).apply(); return
        } catch (e: Exception) {}
        // 3. create (MediaStore may add " (1)" if an un-owned file with this name exists)
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
        }
        val nu = r.insert(coll, cv) ?: throw IOException("MediaStore insert failed")
        r.openOutputStream(nu, "wt")!!.use { it.write(bytes) }
        prefs.edit().putString(key, nu.toString()).apply()
    }

    private fun publishTree(c: Context, tree: Uri, name: String, bytes: ByteArray) {
        val r = c.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        var found: Uri? = null
        r.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use {
            while (it.moveToNext()) if (it.getString(1) == name) {
                found = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)); break
            }
        }
        val target = found ?: DocumentsContract.createDocument(r, parent, "application/json", name)
            ?: throw IOException("could not create $name in chosen folder")
        r.openOutputStream(target, "wt")!!.use { it.write(bytes) }
    }
}
