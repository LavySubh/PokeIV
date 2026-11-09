# PokéIV

Reads CP, name, HP and the three appraisal bars from the Pokémon GO screen, works out the exact IVs and level, and shows the best level to stay under 1500 CP (Great League) and 2500 CP (Ultra League) for every evolution.

```
LVL21          │  Cofagrigus – 1490CP – LVL25        ✎
0/11/6 · 38%   │  Cofagrigus – 2490CP – LVL35
```

## Using it
1. Install the APK and open **PokéIV**. On first launch it downloads the full Pokédex (base stats, evolutions, CP multipliers).
2. Tap **Start PokéIV overlay**. Allow *Display over other apps* and *screen capture* when asked.
3. In Pokémon GO, open a Pokémon and **Appraise** until the three bars are showing.
4. Tap the orange **IV** button. The result strip appears at the top. Tap it to close.
5. If a reading is wrong (✎ turns yellow when it's unsure), tap **✎**, fix the name, CP, HP or IVs, and tap **Save**. The app learns from the correction.

## How it works
| Step | File |
|---|---|
| Screen capture + floating button + result strip | `OverlayService.kt` |
| Text (CP, HP, name) via ML Kit OCR; bar fill measured pixel by pixel | `ScreenReader.kt` |
| Exact IV + level search that reproduces the on-screen CP & HP | `IvSolver.kt` |
| Highest level under 1500 / 2500 CP for each evolution | `IvSolver.kt` (`LeagueCalc`) |
| Learning from confirmed scans and manual corrections; "Relearn" resets | `Calibration.kt` |
| Manual correction screen | `EditActivity.kt` |
| Pokédex data (same data the GO Hub CP calculator uses), offline after first download | `DataRepository.kt` |

**Reading rules:** each bar holds 15 points in 3 sections of 5. IV ≈ filled length ÷ full grey track × 15. An empty bar = 0. A red/pink bar = 15. The bar estimate only narrows the search: the final IVs are the ones that give *exactly* the CP and HP on screen.

## Building the APK
- **GitHub (no setup):** push this folder to a GitHub repository. The included workflow (`.github/workflows/build-apk.yml`) runs the tests and builds the APK. Download it from the run's **Artifacts** section (`PokeIV-apk`).
- **Android Studio:** open the folder, let Gradle sync, then *Build → Build APK(s)*.

Requires Android 8.0+.
