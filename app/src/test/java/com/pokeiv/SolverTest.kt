package com.pokeiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SolverTest {
    private val data = GameData.fromJson(File("src/main/assets/seed_data.json").readText())
    private val ted = data.find("Teddiursa")!!

    @Test fun teddiursaScreensFromManual() {
        // (CP, HP, true IVs, level) from the user's six appraisal screens
        val cases = listOf(
            Triple(85 to 34, Ivs(0, 11, 6), 3.0), Triple(583 to 87, Ivs(13, 4, 4), 17.0),
            Triple(52 to 26, Ivs(13, 1, 2), 2.0), Triple(123 to 40, Ivs(11, 2, 5), 4.0),
            Triple(85 to 33, Ivs(4, 9, 2), 3.0), Triple(86 to 34, Ivs(9, 2, 6), 3.0))
        for ((cphp, iv, lvl) in cases) {
            // Bar estimate deliberately off by one on attack to mimic a misread.
            val est = Ivs((iv.atk + 1).coerceAtMost(15), iv.def, iv.sta)
            val sols = IvSolver.solve(data, ted, cphp.first, cphp.second, est)
            assertTrue("no solution for $iv", sols.any { it.ivs == iv && it.level == lvl })
        }
    }

    @Test fun slakingHundo() {
        val s = IvSolver.solve(data, data.find("slaking")!!, 4020, 225, Ivs(15, 15, 15))
        assertEquals(Ivs(15, 15, 15), s.first().ivs)
        assertEquals(33.5, s.first().level, 0.0)
    }

    @Test fun fuzzyName() { assertEquals("teddiursa", data.fuzzyFind("Teddlursa")!!.key) }

    @Test fun leaguesForTeddiursa() {
        val r = LeagueCalc.forChain(data, ted, Ivs(0, 11, 6), 3.0)
        assertTrue(r.any { it.species.name == "Ursaring" && it.cap == 1500 && it.cp <= 1500 })
        assertTrue(r.any { it.species.name == "Ursaluna" && it.cap == 2500 && it.cp <= 2500 })
    }
}

class FormsAndBadgeTest {
    private val data = GameData.fromJson(File("src/main/assets/seed_data.json").readText())

    @Test fun formsShareScreenName() {
        val v = data.fuzzyFind("Vulpix")!!
        assertEquals(listOf("vulpix", "vulpix~alola"), data.formsOf(v).map { it.key })
        assertEquals("Ninetales (Alola)", data.evolutionsOf(data.find("Vulpix (Alola)")!!).single().name)
    }

    @Test fun galarianMeowthSolvesOnlyAsGalarian() {
        val g = data.find("meowth~galarian")!!; val iv = Ivs(10, 12, 14)
        val m = data.cpm[20.0]!!
        val cp = CpCalc.cp(g, iv, m); val hp = CpCalc.hp(g, iv, m)
        assertTrue(IvSolver.solve(data, g, cp, hp, iv).any { it.ivs == iv && it.level == 20.0 })
        assertTrue(IvSolver.solve(data, data.find("meowth")!!, cp, hp, iv, maxLevel = 51.0).none { it.ivs == iv })
    }

    @Test fun badgeRanges() {
        assertTrue(Badge.matches(Ivs(15, 15, 15), 3, true))
        assertTrue(!Badge.matches(Ivs(15, 15, 14), 3, true))
        assertTrue(Badge.matches(Ivs(0, 11, 6), 0, false))   // 37% Teddiursa
        assertTrue(!Badge.matches(Ivs(0, 11, 6), 1, false))
        assertTrue(Badge.matches(Ivs(13, 4, 4), 0, false))   // 46%
        assertTrue(Badge.matches(Ivs(12, 15, 12), 3, false)) // manual example, 87%
    }
}

class HighLevelTest {
    @Test fun jellicentReachesLevel46WhenDownloadStopsAt45() {
        // Simulate a downloaded CP-multiplier table that ends at level 45.
        val base = GameData.fromJson(File("src/main/assets/seed_data.json").readText())
        val cut = base.cpm.filterKeys { it <= 45.0 }
        val data = GameData(base.species, GameData.withAllLevels(cut))
        val j = data.find("Jellicent")!!; val iv = Ivs(14, 15, 14)
        val r = LeagueCalc.forChain(data, j, iv, 20.0)
        val ul = r.single { it.cap == 2500 }
        assertEquals(2500, ul.cp); assertEquals(46.0, ul.level, 0.0)
        val gl = r.single { it.cap == 1500 }
        assertEquals(22.5, gl.level, 0.0)
    }
}

/** Design decisions 8 & 9: Best Buddy boost, purification, and the what-if calculations. */
class ModifierTest {
    private val data = GameData.fromJson(File("src/main/assets/seed_data.json").readText())
    private val abo = data.find("Abomasnow")!!
    private val hundo = Ivs(15, 15, 15)

    @Test fun abomasnowRibbonOnlyIsLevel43Half() {
        // Manual screen: Best Buddy ribbon earned but not the active buddy – CP unchanged.
        val s = IvSolver.solve(data, abo, 2468, 179, hundo)
        assertEquals(hundo, s.first().ivs); assertEquals(43.5, s.first().level, 0.0)
    }

    @Test fun abomasnowActiveBuddyIsOneLevelHigher() {
        // Same Pokémon set as the active buddy: CP 2498 / 180 HP. Real level is still 43.5.
        assertTrue("must not fit at the real level", IvSolver.solve(data, abo, 2498, 180, hundo).none { it.ivs == hundo && it.level == 43.5 })
        val s = IvSolver.solve(data, abo, 2498, 180, hundo, buddy = true)
        assertEquals(hundo, s.first().ivs); assertEquals(43.5, s.first().level, 0.0)
    }

    @Test fun purifiedIvsAndLevel() {
        assertEquals(Ivs(2, 13, 8), Modifiers.purifiedIvs(Ivs(0, 11, 6)))
        assertEquals(Ivs(15, 15, 15), Modifiers.purifiedIvs(Ivs(13, 14, 15)))
        assertEquals(25.0, Modifiers.purifiedLevel(3.0), 0.0)
        assertEquals(33.5, Modifiers.purifiedLevel(33.5), 0.0)
    }

    @Test fun bestBuddyWhatIfCanUseLevel51() {
        // Jellicent 14/15/14 hits exactly 2500 at level 46; as active buddy the same CP needs level 45.
        val j = data.find("Jellicent")!!; val iv = Ivs(14, 15, 14)
        val plain = LeagueCalc.forChain(data, j, iv, 20.0).single { it.cap == 2500 }
        val bb = LeagueCalc.forChain(data, j, iv, 20.0, buddy = true).single { it.cap == 2500 }
        assertEquals(46.0, plain.level, 0.0); assertEquals(45.0, bb.level, 0.0); assertEquals(2500, bb.cp)
        // A level-50 cap with the boost uses the level-51 multiplier.
        val m50 = Modifiers.cpm(data, 50.0, true)!!; assertEquals(data.cpm[51.0]!!, m50, 0.0)
    }

    @Test fun purifiedWhatIfRecomputesLeagues() {
        val ted = data.find("Teddiursa")!!
        // Purifying forces level 25, so Ursaring (1950 CP there) can no longer fit Great League; it fits Ultra at level 34.
        val iv = Modifiers.purifiedIvs(Ivs(0, 11, 6)); val lvl = Modifiers.purifiedLevel(3.0)
        val rows = LeagueCalc.forChain(data, ted, iv, lvl)
        val gl = rows.single { it.species.name == "Ursaring" && it.cap == 1500 }
        assertTrue(gl.tooHigh); assertEquals(25.0, gl.level, 0.0)
        val ul = rows.single { it.species.name == "Ursaring" && it.cap == 2500 }
        assertTrue(!ul.tooHigh); assertEquals(2497, ul.cp); assertEquals(34.0, ul.level, 0.0)
        // Ursaluna is over both caps at level 25, so it collapses to one "over" row.
        assertTrue(rows.single { it.species.name == "Ursaluna" }.tooHigh)
    }
}
