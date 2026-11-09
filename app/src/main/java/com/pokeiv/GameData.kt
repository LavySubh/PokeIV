package com.pokeiv

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class Species(val key: String, val name: String, val atk: Int, val def: Int, val sta: Int, val evolutions: List<String>)

/** Base stats, evolution chains and CP multipliers. Kept locally; refreshed from the web by DataUpdater. */
class GameData(val species: Map<String, Species>, val cpm: Map<Double, Double>) {

    fun find(name: String): Species? = species[normalize(name)]

    /** Best fuzzy match for an OCR'd name (handles small misreads). */
    fun fuzzyFind(raw: String): Species? {
        val n = normalize(raw)
        if (n.isEmpty()) return null
        species[n]?.let { return it }
        var best: Species? = null; var bestD = Int.MAX_VALUE
        for ((k, s) in species) {
            val d = levenshtein(n, k)
            if (d < bestD) { bestD = d; best = s }
        }
        return if (bestD <= maxOf(1, n.length / 4)) best else null
    }

    /** All forms reachable by evolving, in order (excluding the species itself). */
    fun evolutionsOf(s: Species): List<Species> {
        val out = mutableListOf<Species>(); val seen = mutableSetOf(s.key)
        fun walk(x: Species) { for (e in x.evolutions) { val t = species[e] ?: continue; if (seen.add(t.key)) { out += t; walk(t) } } }
        walk(s); return out
    }

    fun toJson(): String {
        val sp = JSONObject()
        for (s in species.values) sp.put(s.key, JSONObject().put("name", s.name).put("atk", s.atk).put("def", s.def).put("sta", s.sta).put("evo", JSONArray(s.evolutions)))
        val c = JSONObject(); for ((l, m) in cpm) c.put(l.toString(), m)
        return JSONObject().put("pokemon", sp).put("cpm", c).toString()
    }

    companion object {
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        fun fromJson(text: String): GameData {
            val o = JSONObject(text); val sp = o.getJSONObject("pokemon"); val map = HashMap<String, Species>()
            for (k in sp.keys()) {
                val p = sp.getJSONObject(k); val ev = p.optJSONArray("evo") ?: JSONArray()
                val key = normalize(k)
                map[key] = Species(key, p.getString("name"), p.getInt("atk"), p.getInt("def"), p.getInt("sta"), List(ev.length()) { normalize(ev.getString(it)) })
            }
            val cpm = o.optJSONObject("cpm")?.let { c -> c.keys().asSequence().associate { it.toDouble() to c.getDouble(it) } }
            return GameData(map, if (cpm.isNullOrEmpty()) defaultCpm() else cpm)
        }

        private val WHOLE = doubleArrayOf(0.094, 0.16639787, 0.21573247, 0.25572005, 0.29024988, 0.3210876, 0.34921268, 0.3752356, 0.39956728, 0.4225,
            0.44310755, 0.4627984, 0.48168495, 0.49985844, 0.51739395, 0.5343543, 0.5507927, 0.5667545, 0.5822789, 0.5974,
            0.6121573, 0.6265671, 0.64065295, 0.65443563, 0.667934, 0.6811649, 0.69414365, 0.7068842, 0.7193991, 0.7317,
            0.7377695, 0.74378943, 0.74976104, 0.7556855, 0.76156384, 0.76739717, 0.7731865, 0.77893275, 0.784637, 0.7903,
            0.7953, 0.8003, 0.8053, 0.8103, 0.8153, 0.8203, 0.8253, 0.8303, 0.8353, 0.8403, 0.8453)

        /** Levels 1..51 in half steps. Half levels = sqrt of the mean of the squares of neighbours (game rule). */
        fun defaultCpm(): Map<Double, Double> {
            val m = LinkedHashMap<Double, Double>()
            for (i in WHOLE.indices) {
                m[(i + 1).toDouble()] = WHOLE[i]
                if (i + 1 < WHOLE.size) m[i + 1.5] = sqrt((WHOLE[i] * WHOLE[i] + WHOLE[i + 1] * WHOLE[i + 1]) / 2)
            }
            return m
        }

        fun levenshtein(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val t = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = t
                }
            }
            return d[b.length]
        }
    }
}
