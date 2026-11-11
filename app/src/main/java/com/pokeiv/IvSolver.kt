package com.pokeiv

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class Ivs(val atk: Int, val def: Int, val sta: Int) {
    val percent get() = Math.round((atk + def + sta) * 100f / 45f)
    override fun toString() = "$atk/$def/$sta"
}

data class Solution(val ivs: Ivs, val level: Double, val distance: Int)

/**
 * Modifiers (design decision 8). Only an *active* Best Buddy changes the CP on screen: it is
 * computed one level higher (level 50 shows as 51). The Best Buddy ribbon alone, Shadow and
 * Lucky change nothing. Purification is a one-time change: +2 to every IV (max 15), level ≥ 25.
 */
object Modifiers {
    const val BUDDY_LEVELS = 1.0
    const val PURIFY_MIN_LEVEL = 25.0

    /** CP multiplier used for a Pokémon at [level]; an active buddy uses the next level's multiplier. */
    fun cpm(data: GameData, level: Double, buddy: Boolean): Double? = data.cpm[if (buddy) level + BUDDY_LEVELS else level]

    fun purifiedIvs(iv: Ivs) = Ivs((iv.atk + 2).coerceAtMost(15), (iv.def + 2).coerceAtMost(15), (iv.sta + 2).coerceAtMost(15))
    fun purifiedLevel(level: Double) = maxOf(level, PURIFY_MIN_LEVEL)
}

object CpCalc {
    fun cp(s: Species, iv: Ivs, cpm: Double): Int =
        max(10, floor((s.atk + iv.atk) * sqrt((s.def + iv.def).toDouble()) * sqrt((s.sta + iv.sta).toDouble()) * cpm * cpm / 10).toInt())

    fun hp(s: Species, iv: Ivs, cpm: Double): Int = max(10, floor((s.sta + iv.sta) * cpm).toInt())
}

/**
 * Finds the IV + level combinations that reproduce the exact CP (and HP if known) on screen.
 * Bar estimates narrow the search; if nothing fits inside the window, all 4096 IV combos are tried.
 */
object IvSolver {
    /** [buddy]: the Pokémon is the active Best Buddy, so its CP/HP are computed one level higher than its real level. */
    fun solve(data: GameData, s: Species, cp: Int, hp: Int?, est: Ivs?, window: Int = 1, maxLevel: Double = 51.0, buddy: Boolean = false): List<Solution> {
        fun range(v: Int?) = if (v == null) 0..15 else (v - window).coerceAtLeast(0)..(v + window).coerceAtMost(15)
        val top = if (buddy) minOf(maxLevel, 50.0) else maxLevel
        var res = search(data, s, cp, hp, range(est?.atk), range(est?.def), range(est?.sta), est, top, buddy)
        if (res.isEmpty() && est != null) res = search(data, s, cp, hp, 0..15, 0..15, 0..15, est, top, buddy)
        return res.sortedWith(compareBy({ it.distance }, { -it.ivs.percent }))
    }

    private fun search(data: GameData, s: Species, cp: Int, hp: Int?, ra: IntRange, rd: IntRange, rs: IntRange, est: Ivs?, maxLevel: Double, buddy: Boolean): List<Solution> {
        val out = mutableListOf<Solution>()
        for (lvl in data.cpm.keys) {
            if (lvl > maxLevel) continue
            val m = Modifiers.cpm(data, lvl, buddy) ?: continue
            for (a in ra) for (d in rd) for (st in rs) {
                val iv = Ivs(a, d, st)
                if (hp != null && CpCalc.hp(s, iv, m) != hp) continue
                if (CpCalc.cp(s, iv, m) != cp) continue
                val dist = if (est == null) 0 else abs(a - est.atk) + abs(d - est.def) + abs(st - est.sta)
                out += Solution(iv, lvl, dist)
            }
        }
        return out
    }
}

data class LeagueResult(val species: Species, val cap: Int, val cp: Int, val level: Double, val tooHigh: Boolean)

/** For each evolution, the highest level whose CP stays at or under each league cap (manual Steps 04–05). */
object LeagueCalc {
    val CAPS = intArrayOf(1500, 2500)

    /** [buddy]: compute every level as an active Best Buddy (one level higher), so level 50 can reach the level-51 CP. */
    fun forChain(data: GameData, s: Species, iv: Ivs, currentLevel: Double, maxLevel: Double = 50.0, buddy: Boolean = false): List<LeagueResult> {
        val out = mutableListOf<LeagueResult>()
        val chain = data.evolutionsOf(s).ifEmpty { listOf(s) }
        for (e in chain) for (cap in CAPS) {
            var best: LeagueResult? = null
            for (lvl in data.cpm.keys.sorted()) {
                if (lvl < currentLevel || lvl > maxLevel) continue
                val m = Modifiers.cpm(data, lvl, buddy) ?: continue
                val c = CpCalc.cp(e, iv, m)
                if (c <= cap) best = LeagueResult(e, cap, c, lvl, false)
            }
            if (best == null) {
                val m = Modifiers.cpm(data, currentLevel, buddy) ?: continue
                out += LeagueResult(e, cap, CpCalc.cp(e, iv, m), currentLevel, true)
            } else out += best
        }
        // If a species can't get near a cap even at max level, both rows are identical — keep one.
        return out.distinctBy { Triple(it.species.key, it.level, it.cp) }
    }

    fun fmtLevel(l: Double) = if (l % 1.0 == 0.0) l.toInt().toString() else l.toString()
}

/** Appraisal badge: stars (0–3) and the red/pink hundo badge. Used to check and rank solutions. */
object Badge {
    fun matches(iv: Ivs, stars: Int, hundo: Boolean): Boolean {
        val sum = iv.atk + iv.def + iv.sta
        if (hundo) return sum == 45
        return when (stars) {
            0 -> sum <= 22
            1 -> sum in 23..29
            2 -> sum in 30..36
            3 -> sum in 37..45
            else -> true // badge not read
        }
    }
}
