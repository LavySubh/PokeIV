package com.pokeiv

import android.content.Context

/** Last scan kept so the manual-edit screen can correct it. */
object LastScan {
    var read: ScreenRead? = null
    var species: Species? = null
    var result: ScanResult? = null
}

data class ScanResult(
    val species: Species?, val cp: Int?, val hp: Int?,
    val best: Solution?, val alternatives: Int, val confident: Boolean, val message: String?,
)

object Analyzer {
    fun analyze(ctx: Context, read: ScreenRead): ScanResult {
        val data = DataRepository.get(ctx); val cal = Calibration(ctx)
        if (!read.appraisalFound) return ScanResult(null, read.cp, read.hp, null, 0, false, "Open the appraisal first")
        val sp = read.name?.let { data.fuzzyFind(it) }
        LastScan.read = read; LastScan.species = sp
        val est = Ivs(estimate(cal, read, 0), estimate(cal, read, 1), estimate(cal, read, 2))
        if (sp == null || read.cp == null) {
            val msg = if (sp == null) "Name not recognised (${read.name ?: "?"}) – tap ✎" else "CP not read – tap ✎"
            return ScanResult(sp, read.cp, read.hp, Solution(est, 0.0, 99), 0, false, msg).also { LastScan.result = it }
        }
        // Try the normal form and every regional form with this name (e.g. Vulpix / Vulpix (Alola)).
        val hundo = read.hundoBadge
        val start = if (hundo) Ivs(15, 15, 15) else est
        val byForm = data.formsOf(sp).ifEmpty { listOf(sp) }
            .map { f -> f to IvSolver.solve(data, f, read.cp, read.hp, start).sortedBy { if (Badge.matches(it.ivs, read.stars, hundo)) 0 else 1 } }
            .filter { it.second.isNotEmpty() }
        // Prefer forms whose answers agree with the badge; then the normal form.
        val ranked = byForm.sortedWith(compareBy({ if (it.second.any { s -> Badge.matches(s.ivs, read.stars, hundo) }) 0 else 1 }, { if ('~' in it.first.key) 1 else 0 }))
        val chosen = ranked.firstOrNull()
        val species = chosen?.first ?: sp
        val sols = chosen?.second ?: emptyList()
        val badgeSols = sols.filter { Badge.matches(it.ivs, read.stars, hundo) }.ifEmpty { sols }
        val distinct = badgeSols.map { it.ivs }.distinct()
        val best = badgeSols.firstOrNull()
        val otherForms = ranked.drop(1).map { it.first.name }
        val confident = distinct.size == 1 && otherForms.isEmpty()
        if (confident && best != null) cal.learn(read.fractions, best.ivs, false)
        LastScan.species = species
        val msg = when {
            best == null -> "No IVs fit CP ${read.cp} – tap ✎"
            otherForms.isNotEmpty() -> "Could also be ${otherForms.joinToString()} – check ✎"
            distinct.size > 1 -> "${distinct.size} possible – check ✎"
            else -> null
        }
        return ScanResult(species, read.cp, read.hp, best ?: Solution(est, 0.0, 99), distinct.size, confident, msg).also { LastScan.result = it }
    }

    private fun estimate(cal: Calibration, r: ScreenRead, i: Int): Int =
        if (r.fullBars[i]) 15 else if (r.fractions[i] < 0) 8 else cal.estimate(r.fractions[i]).coerceAtMost(14)

    /** Overlay text: left = current level; right = one line per evolution & league. */
    fun format(ctx: Context, r: ScanResult): Pair<String, String> {
        val b = r.best
        if (r.species == null || b == null || b.level == 0.0) return "?" to (r.message ?: "")
        val left = "LVL${LeagueCalc.fmtLevel(b.level)}\n${b.ivs} · ${b.ivs.percent}%"
        val rows = LeagueCalc.forChain(DataRepository.get(ctx), r.species, b.ivs, b.level).joinToString("\n") {
            if (it.tooHigh) "${it.species.name} – ${it.cp}CP – over ${it.cap}"
            else "${it.species.name} – ${it.cp}CP – LVL${LeagueCalc.fmtLevel(it.level)}"
        }
        return left to (if (r.message != null) "$rows\n⚠ ${r.message}" else rows)
    }
}
