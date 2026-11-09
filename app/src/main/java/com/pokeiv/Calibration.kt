package com.pokeiv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Learns how this phone's bar measurements map to true IVs.
 * Each confirmed reading (manual fix, or a unique CP/HP solve) is a sample: measured fill fraction -> true IV.
 * A least-squares line fraction = a*(iv/15) + b is fitted and inverted for future reads.
 */
class Calibration(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    private val samples = mutableListOf<Pair<Double, Int>>()
    var a = 1.0; private set
    var b = 0.0; private set
    var reads = 0; private set
    var corrections = 0; private set

    init { load() }

    fun estimate(fraction: Double): Int = (((fraction - b) / a) * 15).roundToInt().coerceIn(0, 15)

    fun learn(fractions: DoubleArray, truth: Ivs, wasCorrection: Boolean) {
        val t = intArrayOf(truth.atk, truth.def, truth.sta)
        for (i in 0..2) if (fractions[i] >= 0) samples += fractions[i] to t[i]
        while (samples.size > 300) samples.removeAt(0)
        reads++; if (wasCorrection) corrections++
        fit(); save()
    }

    fun reset() { samples.clear(); a = 1.0; b = 0.0; reads = 0; corrections = 0; save() }

    fun sampleCount() = samples.size

    private fun fit() {
        if (samples.size < 9) { a = 1.0; b = 0.0; return }
        val xs = samples.map { it.second / 15.0 }; val ys = samples.map { it.first }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx < 1e-6) return
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val na = sxy / sxx
        if (na in 0.7..1.3) { a = na; b = my - na * mx }
    }

    private fun save() {
        val arr = JSONArray(); samples.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString("data", JSONObject().put("s", arr).put("reads", reads).put("corr", corrections).toString()).apply()
    }

    private fun load() {
        val t = prefs.getString("data", null) ?: return
        runCatching {
            val o = JSONObject(t); val arr = o.getJSONArray("s")
            for (i in 0 until arr.length()) { val p = arr.getJSONArray(i); samples += p.getDouble(0) to p.getInt(1) }
            reads = o.optInt("reads"); corrections = o.optInt("corr"); fit()
        }
    }
}
