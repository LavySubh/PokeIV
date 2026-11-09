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
