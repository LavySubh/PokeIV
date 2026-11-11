package com.pokeiv

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Manual correction: fix name / CP / HP / IVs. Saved corrections train the bar reader. */
class EditActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Correct reading"
        val data = DataRepository.get(this)
        val last = LastScan.result
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val name = AutoCompleteTextView(this).apply {
            hint = "Pokémon"; setText(LastScan.species?.name ?: LastScan.read?.name ?: ""); threshold = 2
            setAdapter(ArrayAdapter(this@EditActivity, android.R.layout.simple_dropdown_item_1line, data.species.values.map { it.name }.sorted()))
        }
        fun num(h: String, v: Int?) = EditText(this).apply { hint = h; inputType = InputType.TYPE_CLASS_NUMBER; setText(v?.toString() ?: "") }
        val cp = num("CP", last?.cp ?: LastScan.read?.cp); val hp = num("HP (max)", last?.hp ?: LastScan.read?.hp)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val iv = last?.best?.ivs ?: Ivs(8, 8, 8)
        val pickers = listOf("Attack" to iv.atk, "Defense" to iv.def, "HP" to iv.sta).map { (label, v) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(TextView(this).apply { text = label })
            val p = NumberPicker(this).apply { minValue = 0; maxValue = 15; value = v; wrapSelectorWheel = false }
            col.addView(p); row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); p
        }
        val buddy = CheckBox(this).apply { text = "Active Best Buddy (CP shown one level higher)"; isChecked = last?.buddyBoost ?: false }
        val shadow = CheckBox(this).apply { text = "Shadow Pokémon (enables “If Purified”)"; isChecked = last?.shadow ?: false }
        val status = TextView(this)
        val save = Button(this).apply { text = "Save & recalculate" }
        listOf(name, cp, hp, row, buddy, shadow, status, save).forEach { root.addView(it) }
        setContentView(root)

        save.setOnClickListener {
            val sp = data.fuzzyFind(name.text.toString()) ?: run { status.text = "Unknown Pokémon – try “Update Pokémon data” in the app"; return@setOnClickListener }
            val cpV = cp.text.toString().toIntOrNull() ?: run { status.text = "Enter the CP"; return@setOnClickListener }
            val hpV = hp.text.toString().toIntOrNull()
            val ivs = Ivs(pickers[0].value, pickers[1].value, pickers[2].value)
            val sol = IvSolver.solve(data, sp, cpV, hpV, ivs, window = 0, buddy = buddy.isChecked).firstOrNull { it.ivs == ivs }
            if (sol == null) { status.text = "${sp.name} $ivs can't be CP $cpV${hpV?.let { " / HP $it" } ?: ""} at any level${if (buddy.isChecked) " as active buddy" else ""} – check the values"; return@setOnClickListener }
            val wasWrong = last?.best?.ivs != ivs
            LastScan.read?.let { Calibration(this).learn(it.fractions, ivs, wasWrong) }
            val res = ScanResult(sp, cpV, hpV, sol, 1, true, null, shadow = shadow.isChecked, purified = last?.purified ?: false, activeBuddy = buddy.isChecked)
            LastScan.result = res
            val (l, r) = Analyzer.format(this, res)
            if (OverlayService.running) showOverlayResult() else status.text = "$l\n$r"
            if (OverlayService.running) finish()
        }
    }
}
