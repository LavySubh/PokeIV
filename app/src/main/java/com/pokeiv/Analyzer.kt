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
    /** Shadow Pokémon (offers the "If purified" what-if). */
    val shadow: Boolean = false,
    /** Already purified (informational). */
    val purified: Boolean = false,
    /** Set as the active Best Buddy: the on-screen CP already includes the +1 level. */
    val activeBuddy: Boolean = false,
    /** No IVs fit the CP at the real level, but they do one level higher — the bubble was probably missed. */
    val buddyAssumed: Boolean = false,
) {
    val buddyBoost get() = activeBuddy || buddyAssumed
}

/** What-if toggles on the strip (design decision 9). */
data class WhatIf(val bestBuddy: Boolean = false, val purified: Boolean = false)

object Analyzer {
    fun analyze(ctx: Context, read: ScreenRead): ScanResult {
        val data = DataRepository.get(ctx); val cal = Calibration(ctx)
        if (!read.appraisalFound) return ScanResult(null, read.cp, read.hp, null, 0, false, "Open the appraisal first")
        val sp = read.name?.let { data.fuzzyFind(it) }
        LastScan.read = read; LastScan.species = sp
        val est = Ivs(estimate(cal, read, 0), estimate(cal, read, 1), estimate(cal, read, 2))
        if (sp == null || read.cp == null) {
            val msg = if (sp == null) "Name not recognised (${read.name ?: "?"}) – tap ✎" else "CP not read – tap ✎"
            return ScanResult(sp, read.cp, read.hp, Solution(est, 0.0, 99), 0, false, msg, read.shadow, read.purified, read.activeBuddy).also { LastScan.result = it }
        }
        val cp = read.cp
        val hundo = read.hundoBadge
        val start = if (hundo) Ivs(15, 15, 15) else est
        val forms = data.formsOf(sp).ifEmpty { listOf(sp) }

        // Try the normal form and every regional form with this name (e.g. Vulpix / Vulpix (Alola)).
        fun solveAll(buddy: Boolean) = forms
            .map { f -> f to IvSolver.solve(data, f, cp, read.hp, start, buddy = buddy).sortedBy { if (Badge.matches(it.ivs, read.stars, hundo)) 0 else 1 } }
            .filter { it.second.isNotEmpty() }

        // An active Best Buddy is shown one level higher. If nothing fits at the real level and the bubble
        // wasn't seen, retry with the boost before giving up (safety net for a missed bubble).
        var buddy = read.activeBuddy; var assumed = false
        var byForm = solveAll(buddy)
        if (byForm.isEmpty() && !buddy) { byForm = solveAll(true); if (byForm.isNotEmpty()) { buddy = true; assumed = true } }

        // Prefer forms whose answers agree with the badge; then the normal form.
        val ranked = byForm.sortedWith(compareBy({ if (it.second.any { s -> Badge.matches(s.ivs, read.stars, hundo) }) 0 else 1 }, { if ('~' in it.first.key) 1 else 0 }))
        val chosen = ranked.firstOrNull()
        val species = chosen?.first ?: sp
        val sols = chosen?.second ?: emptyList()
        val badgeSols = sols.filter { Badge.matches(it.ivs, read.stars, hundo) }.ifEmpty { sols }
        val distinct = badgeSols.map { it.ivs }.distinct()
        val best = badgeSols.firstOrNull()
        val otherForms = ranked.drop(1).map { it.first.name }
        val confident = distinct.size == 1 && otherForms.isEmpty() && !assumed
        if (confident && best != null) cal.learn(read.fractions, best.ivs, false)
        LastScan.species = species
        val msg = when {
            best == null -> "No IVs fit CP ${read.cp} – tap ✎"
            otherForms.isNotEmpty() -> "Could also be ${otherForms.joinToString()} – check ✎"
            distinct.size > 1 -> "${distinct.size} possible – check ✎"
            assumed -> "Fits only as active Best Buddy (+1 level) – check ✎"
            else -> null
        }
        return ScanResult(species, read.cp, read.hp, best ?: Solution(est, 0.0, 99), distinct.size, confident, msg,
            read.shadow, read.purified, read.activeBuddy, assumed).also { LastScan.result = it }
    }

    private fun estimate(cal: Calibration, r: ScreenRead, i: Int): Int =
        if (r.fullBars[i]) 15 else if (r.fractions[i] < 0) 8 else cal.estimate(r.fractions[i]).coerceAtMost(14)

    /**
     * Overlay text: left = current level; right = one line per evolution & league.
     * [w] applies the what-if toggles: purified bumps the IVs (+2, max 15) and the level (≥ 25);
     * Best Buddy computes every level one higher, so level 50 can use the level-51 CP.
     */
    fun format(ctx: Context, r: ScanResult, w: WhatIf = WhatIf()): Pair<String, String> {
        val b = r.best
        if (r.species == null || b == null || b.level == 0.0) return "?" to (r.message ?: "")
        val purify = w.purified && r.shadow
        val iv = if (purify) Modifiers.purifiedIvs(b.ivs) else b.ivs
        val level = if (purify) Modifiers.purifiedLevel(b.level) else b.level
        val buddy = r.buddyBoost || w.bestBuddy
        val tags = mutableListOf<String>()
        if (r.buddyBoost) tags += "BB" else if (w.bestBuddy) tags += "if BB"
        if (r.shadow && !purify) tags += "Shadow" else if (purify) tags += "if Purified" else if (r.purified) tags += "Purified"
        val left = "LVL${LeagueCalc.fmtLevel(level)}${if (tags.isEmpty()) "" else " " + tags.joinToString(" ")}\n$iv · ${iv.percent}%"
        val rows = LeagueCalc.forChain(DataRepository.get(ctx), r.species, iv, level, buddy = buddy).joinToString("\n") {
            if (it.tooHigh) "${it.species.name} – ${it.cp}CP – over ${LeagueCalc.CAPS.filter { c -> it.cp > c }.maxOrNull() ?: it.cap}"
            else "${it.species.name} – ${it.cp}CP – LVL${LeagueCalc.fmtLevel(it.level)}"
        }
        return left to (if (r.message != null) "$rows\n⚠ ${r.message}" else rows)
    }
}
