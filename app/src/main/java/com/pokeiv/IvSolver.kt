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
    fun solve(data: GameData, s: Species, cp: Int, hp: Int?, est: Ivs?, window: Int = 1, maxLevel: Double = 51.0): List<Solution> {
        fun range(v: Int?) = if (v == null) 0..15 else (v - window).coerceAtLeast(0)..(v + window).coerceAtMost(15)
        var res = search(data, s, cp, hp, range(est?.atk), range(est?.def), range(est?.sta), est, maxLevel)
        if (res.isEmpty() && est != null) res = search(data, s, cp, hp, 0..15, 0..15, 0..15, est, maxLevel)
        return res.sortedWith(compareBy({ it.distance }, { -it.ivs.percent }))
    }

    private fun search(data: GameData, s: Species, cp: Int, hp: Int?, ra: IntRange, rd: IntRange, rs: IntRange, est: Ivs?, maxLevel: Double): List<Solution> {
        val out = mutableListOf<Solution>()
        for ((lvl, m) in data.cpm) {
            if (lvl > maxLevel) continue
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

    fun forChain(data: GameData, s: Species, iv: Ivs, currentLevel: Double, maxLevel: Double = 50.0): List<LeagueResult> {
        val out = mutableListOf<LeagueResult>()
        val chain = data.evolutionsOf(s).ifEmpty { listOf(s) }
        for (e in chain) for (cap in CAPS) {
            var best: LeagueResult? = null
            for ((lvl, m) in data.cpm.toSortedMap()) {
                if (lvl < currentLevel || lvl > maxLevel) continue
                val c = CpCalc.cp(e, iv, m)
                if (c <= cap) best = LeagueResult(e, cap, c, lvl, false)
            }
            if (best == null) {
                val m = data.cpm[currentLevel] ?: continue
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
