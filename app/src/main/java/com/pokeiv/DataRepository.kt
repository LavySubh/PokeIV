package com.pokeiv

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local copy of the data the GO Hub CP calculator uses (base stats, evolutions, CP multipliers).
 * Ships with a small seed; "Update data" downloads the full Pokédex (with regional forms) so everything works offline.
 */
object DataRepository {
    private const val API = "https://pogoapi.net/api/v1/"
    /** Forms that never change base stats (shadow/purified only change damage; costumes are cosmetic). */
    private val SAME_STATS = setOf("shadow", "purified")
    private val REGIONAL = Regex("alola|galar|hisui|paldea")
    @Volatile private var cached: GameData? = null

    fun get(ctx: Context): GameData = cached ?: synchronized(this) {
        cached ?: run {
            val f = File(ctx.filesDir, "game_data.json")
            val text = if (f.exists()) f.readText() else ctx.assets.open("seed_data.json").bufferedReader().readText()
            GameData.fromJson(text).also { cached = it }
        }
    }

    fun speciesCount(ctx: Context) = get(ctx).species.values.count { '~' !in it.key }
    fun formCount(ctx: Context) = get(ctx).species.values.count { '~' in it.key }

    /** Blocking — call off the main thread. Returns number of species loaded. */
    fun update(ctx: Context): Int {
        val stats = JSONArray(fetch(API + "pokemon_stats.json"))
        val evos = JSONArray(fetch(API + "pokemon_evolutions.json"))
        val cpmArr = JSONArray(fetch(API + "cp_multiplier.json"))

        // Base stats; a form is kept only if its stats differ from the normal form (drops costumes etc.)
        data class Raw(val name: String, val form: String, val a: Int, val d: Int, val s: Int)
        val raws = (0 until stats.length()).map { stats.getJSONObject(it) }
            .map { Raw(it.getString("pokemon_name"), it.optString("form", "Normal"), it.getInt("base_attack"), it.getInt("base_defense"), it.getInt("base_stamina")) }
            .filter { GameData.normalize(it.form) !in SAME_STATS }
        val normal = raws.filter { GameData.key(it.name, it.form) == GameData.normalize(it.name) }.associateBy { GameData.normalize(it.name) }
        val kept = raws.filter { r ->
            val n = normal[GameData.normalize(r.name)]
            GameData.key(r.name, r.form) == GameData.normalize(r.name) || n == null || n.a != r.a || n.d != r.d || n.s != r.s ||
                REGIONAL.containsMatchIn(r.form.lowercase()) // e.g. Alolan Vulpix: same stats, different evolution
        }
        val keys = kept.map { GameData.key(it.name, it.form) }.toSet()

        // Evolutions, form-aware ("Vulpix (Alola)" -> "Ninetales (Alola)"); unknown forms fall back to the normal form.
        val evoMap = HashMap<String, MutableList<String>>()
        fun resolve(name: String, form: String) = GameData.key(name, form).takeIf { it in keys } ?: GameData.normalize(name)
        for (i in 0 until evos.length()) {
            val e = evos.getJSONObject(i)
            val from = resolve(e.getString("pokemon_name"), e.optString("form", "Normal"))
            val list = e.optJSONArray("evolutions") ?: continue
            for (j in 0 until list.length()) {
                val t = list.getJSONObject(j)
                val to = resolve(t.getString("pokemon_name"), t.optString("form", "Normal"))
                val l = evoMap.getOrPut(from) { mutableListOf() }
                if (to !in l) l += to
            }
        }

        val species = HashMap<String, Species>()
        for (r in kept) {
            val key = GameData.key(r.name, r.form)
            if (key in species) continue
            val display = if ('~' in key) "${r.name} (${r.form.replace('_', ' ')})" else r.name
            species[key] = Species(key, display, r.a, r.d, r.s, evoMap[key] ?: emptyList())
        }
        val cpm = LinkedHashMap<Double, Double>()
        for (i in 0 until cpmArr.length()) { val c = cpmArr.getJSONObject(i); cpm[c.getDouble("level")] = c.getDouble("multiplier") }
        require(species.size > 100) { "Downloaded data looks incomplete" }
        val data = GameData(species, GameData.withAllLevels(cpm))
        File(ctx.filesDir, "game_data.json").writeText(data.toJson())
        cached = data
        return species.values.count { '~' !in it.key }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "PokeIV/0.2")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
