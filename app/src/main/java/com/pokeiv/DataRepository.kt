package com.pokeiv

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local copy of the data the GO Hub CP calculator uses (base stats, evolutions, CP multipliers).
 * Ships with a small seed; "Update data" downloads the full Pokédex so every species works offline.
 */
object DataRepository {
    private const val API = "https://pogoapi.net/api/v1/"
    @Volatile private var cached: GameData? = null

    fun get(ctx: Context): GameData = cached ?: synchronized(this) {
        cached ?: run {
            val f = File(ctx.filesDir, "game_data.json")
            val text = if (f.exists()) f.readText() else ctx.assets.open("seed_data.json").bufferedReader().readText()
            GameData.fromJson(text).also { cached = it }
        }
    }

    fun speciesCount(ctx: Context) = get(ctx).species.size

    /** Blocking — call off the main thread. Returns number of species loaded. */
    fun update(ctx: Context): Int {
        val stats = JSONArray(fetch(API + "pokemon_stats.json"))
        val evos = JSONArray(fetch(API + "pokemon_evolutions.json"))
        val cpmArr = JSONArray(fetch(API + "cp_multiplier.json"))
        val evoMap = HashMap<String, MutableList<String>>()
        for (i in 0 until evos.length()) {
            val e = evos.getJSONObject(i)
            if (e.optString("form", "Normal") != "Normal") continue
            val from = GameData.normalize(e.getString("pokemon_name"))
            val list = e.optJSONArray("evolutions") ?: continue
            for (j in 0 until list.length()) {
                val t = list.getJSONObject(j)
                if (t.optString("form", "Normal") == "Normal") evoMap.getOrPut(from) { mutableListOf() } += GameData.normalize(t.getString("pokemon_name"))
            }
        }
        val species = HashMap<String, Species>()
        for (i in 0 until stats.length()) {
            val p = stats.getJSONObject(i)
            if (p.optString("form", "Normal") != "Normal") continue
            val name = p.getString("pokemon_name"); val key = GameData.normalize(name)
            species[key] = Species(key, name, p.getInt("base_attack"), p.getInt("base_defense"), p.getInt("base_stamina"), evoMap[key] ?: emptyList())
        }
        val cpm = LinkedHashMap<Double, Double>()
        for (i in 0 until cpmArr.length()) { val c = cpmArr.getJSONObject(i); cpm[c.getDouble("level")] = c.getDouble("multiplier") }
        require(species.size > 100) { "Downloaded data looks incomplete" }
        val data = GameData(species, if (cpm.size > 50) cpm else GameData.defaultCpm())
        File(ctx.filesDir, "game_data.json").writeText(data.toJson())
        cached = data
        return species.size
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "PokeIV/0.1")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
