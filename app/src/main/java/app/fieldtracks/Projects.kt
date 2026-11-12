package app.fieldtracks

import android.content.Context

data class Project(val key: String, val label: String, val url: String, val enabled: Boolean)

object Projects {
    // Add a project's Apps Script /exec URL and set enabled = true to switch it on.
    val all = listOf(
        Project("asha1", "Asha 1 (VCS 4866)", "https://script.google.com/macros/s/AKfycbyQ1AmDKLV2r0aJl54nhcvBIBhEGSAC9prUQdUtqd1tulmrM8LfLezhPraAcZ4LLbk0/exec", true),
        Project("asha2", "Asha 2", "", false),
        Project("arun", "Arun", "", false)
    )

    fun active(c: Context): Project {
        val k = Cfg.prefs(c).getString("project", "asha1")
        return all.firstOrNull { it.key == k && it.enabled } ?: all[0]
    }
}
