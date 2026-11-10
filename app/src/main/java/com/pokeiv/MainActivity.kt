package com.pokeiv

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_CODE, r.resultCode).putExtra(OverlayService.EXTRA_DATA, r.data))
            moveTaskToBack(true)
        } else refresh("Screen capture permission is needed to read the appraisal.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { col.addView(it) }
        col.addView(TextView(this).apply { text = "PokéIV"; textSize = 28f })
        col.addView(status)
        btn("Start PokéIV overlay") { startOverlay() }
        btn("Stop overlay") { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)); refresh() }
        btn("Correct last reading") { startActivity(Intent(this, EditActivity::class.java)) }
        btn("Update Pokémon data") { updateData() }
        btn("Relearn (reset bar calibration)") { Calibration(this).reset(); refresh("Calibration reset – it will relearn from your next scans.") }
        btn("Open GO Hub CP calculator") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://db.pokemongohub.net/tools/cp-calculator"))) }
        setContentView(ScrollView(this).apply { addView(col) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (DataRepository.speciesCount(this) < 100) updateData()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            refresh("Allow “Display over other apps”, then tap Start again."); return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateData() {
        refresh("Downloading Pokémon data…")
        thread {
            val msg = runCatching { "Updated: ${DataRepository.update(this)} Pokémon." }.getOrElse { "Update failed (${it.message}). Using built-in data." }
            runOnUiThread { refresh(msg) }
        }
    }

    private fun refresh(extra: String? = null) {
        val cal = Calibration(this)
        status.text = buildString {
            append(if (OverlayService.running) "● Overlay running\n" else "○ Overlay stopped\n")
            append("Pokémon in database: ${DataRepository.speciesCount(this@MainActivity)} (+${DataRepository.formCount(this@MainActivity)} regional forms)\n")
            append("Learned samples: ${cal.sampleCount()} · confirmed scans: ${cal.reads} · manual fixes: ${cal.corrections}\n")
            append("\nHow to use: start the overlay, open a Pokémon in GO, run Appraise until the bars show, then tap the orange IV button.")
            extra?.let { append("\n\n$it") }
        }
    }
}
