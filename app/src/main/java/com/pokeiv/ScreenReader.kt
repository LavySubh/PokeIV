package com.pokeiv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class ScreenRead(
    val cp: Int?, val hp: Int?, val name: String?,
    /** Fill fraction 0..1 of the Attack/Defense/HP bars; -1 when a bar wasn't found. */
    val fractions: DoubleArray,
    val fullBars: BooleanArray,
    val appraisalFound: Boolean,
    /** Filled stars on the badge (0–3), or -1 if the badge wasn't found. */
    val stars: Int = -1,
    /** Red/pink badge = 100% (hundo). */
    val hundoBadge: Boolean = false,
    /** Shadow Pokémon: violet flame around the sprite, or the PURIFY button / "SHADOW BONUS" text was read. */
    val shadow: Boolean = false,
    /** Purified Pokémon: the charged move "Return" was read and there is no PURIFY button (informational only). */
    val purified: Boolean = false,
    /** Set as the active buddy: the white speech bubble with two buddy faces next to the sprite. Adds one level to the CP. */
    val activeBuddy: Boolean = false,
)

/** Reads CP, HP, name (text) and the three appraisal bars (pixels) from a screenshot. */
object ScreenReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bmp: Bitmap, done: (ScreenRead?, Exception?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { done(parse(bmp, it), null) }
            .addOnFailureListener { done(null, it) }
    }

    private fun digits(s: String) = s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1').replace('S', '5')

    private fun parse(bmp: Bitmap, text: Text): ScreenRead {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val h = bmp.height

        // CP: "CP4020" near the top of the screen
        var cp: Int? = null; var cpBox: Rect? = null
        val cpRe = Regex("""[cC][pP]\s*([0-9OoIl|S]{2,5})""")
        for (l in lines.filter { it.boundingBox!!.top < h * 0.3 }) {
            cpRe.find(l.text.replace(" ", ""))?.let { m -> digits(m.groupValues[1]).toIntOrNull()?.let { if (cp == null) { cp = it; cpBox = l.boundingBox } } }
        }

        // HP: "225 / 225 HP" -> max HP 225
        val hpRe = Regex("""(\d{1,4})\s*/\s*([0-9OoIl]{1,4})\s*HP""", RegexOption.IGNORE_CASE)
        val hpLine = lines.firstOrNull { hpRe.containsMatchIn(it.text) }
        val hp = hpLine?.let { hpRe.find(it.text)!!.groupValues[2].let(::digits).toIntOrNull() }

        // Name: the text line just above the HP line
        val name = hpLine?.let { hl ->
            val hb = hl.boundingBox!!
            lines.filter { val b = it.boundingBox!!; b.bottom <= hb.top + 2 && hb.top - b.bottom < hb.height() * 6 && it.text.any(Char::isLetter) }
                .maxByOrNull { it.boundingBox!!.bottom }?.text?.replace(Regex("^[^A-Za-z]+"), "")?.trim()
        }

        // Appraisal labels in the lower half
        fun label(word: String) = lines.filter { it.text.trim().equals(word, true) && it.boundingBox!!.top > h * 0.45 }
        val atk = label("Attack").firstOrNull()?.boundingBox
        val def = label("Defense").firstOrNull()?.boundingBox
        val hpL = label("HP").filter { def == null || it.boundingBox!!.top > def.top }.minByOrNull { it.boundingBox!!.top }?.boundingBox

        val fr = DoubleArray(3) { -1.0 }; val full = BooleanArray(3)
        listOf(atk, def, hpL).forEachIndexed { i, r -> r?.let { measureBar(bmp, it)?.let { (f, red) -> fr[i] = f; full[i] = red } } }
        val (stars, hundo) = atk?.let { readBadge(bmp, it) } ?: (-1 to false)

        // Modifiers. The sprite area lies between the CP line and the name/HP line; during an appraisal the
        // lower half (PURIFY button, moves) is hidden behind the leader's panel, so pixels are the main cue.
        val allText = lines.joinToString(" ") { it.text }
        val purifyText = allText.contains("PURIFY", true) || allText.contains("SHADOW BONUS", true)
        val returnMove = lines.any { it.boundingBox!!.top > h * 0.5 && Regex("""^\s*Return\b""").containsMatchIn(it.text) }
        val spriteTop = cpBox?.bottom ?: (h * 0.08).toInt()
        val spriteBottom = (hpLine?.boundingBox?.top ?: (h * 0.42).toInt()) - 1
        val shadow = purifyText || (spriteBottom > spriteTop && shadowFlame(bmp, spriteTop, spriteBottom))
        val buddy = cpBox?.let { buddyBubble(bmp, it) } ?: false
        return ScreenRead(cp, hp, name, fr, full, atk != null || def != null, stars, hundo, shadow, !shadow && returnMove, buddy)
    }

    /** Violet flame colour: blue well above red, red well above green. Dark navy/blue backgrounds don't pass. */
    private fun isViolet(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); return b > 120 && b > r + 20 && r > g + 35 }

    /** True when a good share of the sprite area is the violet shadow flame. Samples every 4th pixel. */
    private fun shadowFlame(bmp: Bitmap, top: Int, bottom: Int): Boolean {
        val x0 = bmp.width / 5; val x1 = bmp.width * 4 / 5
        var n = 0; var violet = 0
        var y = top
        while (y < bottom) { var x = x0; while (x < x1) { n++; if (isViolet(bmp.getPixel(x, y))) violet++; x += 4 }; y += 4 }
        return n > 0 && violet > n * 0.06
    }

    private fun isWhite(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); return minOf(r, g, b) > 225 && maxOf(r, g, b) - minOf(r, g, b) < 22 }
    private fun isDark(c: Int): Boolean = Color.red(c) + Color.green(c) + Color.blue(c) < 360

    /**
     * Active-buddy speech bubble: a white circle (≈2.75× the CP text height across) with two dark faces inside,
     * just below the CP line on the left of the sprite (measured on the Abomasnow CP 2498 screen).
     * Looks for a round white blob of the right size that contains dark pixels.
     */
    private fun buddyBubble(bmp: Bitmap, cp: Rect): Boolean {
        val ch = cp.height().coerceAtLeast(8)
        val x0 = bmp.width / 20; val x1 = bmp.width * 9 / 20
        val y0 = cp.bottom; val y1 = minOf(bmp.height, cp.bottom + 5 * ch)
        val w = x1 - x0; val ht = y1 - y0
        if (w <= 0 || ht <= 0) return false
        val px = IntArray(w * ht); bmp.getPixels(px, 0, w, x0, y0, w, ht)
        val seen = BooleanArray(px.size); val stack = IntArray(px.size)
        val minArea = (2.5 * ch * ch).toInt(); val maxArea = (12.0 * ch * ch).toInt()
        for (i in px.indices) {
            if (seen[i] || !isWhite(px[i])) continue
            var sp = 0; var n = 0; stack[sp++] = i; seen[i] = true
            var bx0 = w; var bx1 = 0; var by0 = ht; var by1 = 0
            while (sp > 0) {
                val p = stack[--sp]; n++
                val x = p % w; val y = p / w
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < ht - 1) p + w else -1)) {
                    if (q >= 0 && !seen[q] && isWhite(px[q])) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n < minArea || n > maxArea) continue
            val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
            val aspect = bw.toDouble() / bh
            if (aspect < 0.7 || aspect > 1.5) continue
            if (n < bw * bh * 0.5) continue
            // The two faces: dark pixels inside the middle of the blob's box.
            var dark = 0
            for (y in by0 + bh / 4..by1 - bh / 4) for (x in bx0 + bw / 4..bx1 - bw / 4) if (isDark(px[y * w + x])) dark++
            if (dark > bw * bh * 0.02) return true
        }
        return false
    }

    private fun isStar(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); return r > 230 && g in 150..225 && b < 150 && g - b > 45 }
    private fun isPinkBadge(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); return r > 230 && b > g + 10 && g < 190 }

    /**
     * The badge sits above-left of the "Attack" label. Counts filled (orange/yellow) star blobs and
     * measures how much of the area is the hundo pink. Tuned on a real hundo screenshot (3 blobs ≈ 1.6×h² px each).
     */
    private fun readBadge(bmp: Bitmap, attack: Rect): Pair<Int, Boolean> {
        val h = attack.height().coerceAtLeast(6)
        val x0 = maxOf(0, attack.left - 4 * h); val x1 = minOf(bmp.width, attack.left + 6 * h)
        val y0 = maxOf(0, attack.top - 15 * h); val y1 = maxOf(y0, attack.top - 2 * h)
        val w = x1 - x0; val ht = y1 - y0
        if (w <= 0 || ht <= 0) return -1 to false
        val px = IntArray(w * ht); bmp.getPixels(px, 0, w, x0, y0, w, ht)
        val seen = BooleanArray(px.size); var pink = 0; var blobs = 0
        val minBlob = (0.6 * h * h).toInt(); val stack = IntArray(px.size)
        for (i in px.indices) {
            if (isPinkBadge(px[i])) pink++
            if (seen[i] || !isStar(px[i])) continue
            var sp = 0; var n = 0; stack[sp++] = i; seen[i] = true
            while (sp > 0) {
                val p = stack[--sp]; n++
                val x = p % w; val y = p / w
                for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < ht - 1) p + w else -1)) {
                    if (q >= 0 && !seen[q] && isStar(px[q])) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n >= minBlob) blobs++
        }
        return blobs.coerceAtMost(3) to (pink > px.size * 0.08)
    }

    private fun isFilled(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c); return r > 180 && r - b > 70 && g < r - 25 }
    private fun isRed(c: Int): Boolean { val r = Color.red(c); val g = Color.green(c); return isFilled(c) && r - g > 95 }
    private fun isTrack(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return mx - mn < 22 && (r + g + b) / 3 in 165..240
    }

    /** Scans rows under a label for the bar; returns median fill fraction and whether it is the red "15" colour. */
    private fun measureBar(bmp: Bitmap, label: Rect): Pair<Double, Boolean>? {
        val lh = label.height().coerceAtLeast(6)
        val gapMax = maxOf(3, lh / 3)
        val fracs = mutableListOf<Double>(); var redVotes = 0; var fillVotes = 0
        val yEnd = minOf(bmp.height - 1, label.bottom + (lh * 2.5).toInt())
        for (y in label.bottom..yEnd) {
            val xStartMin = maxOf(0, label.left - lh); val xStartMax = minOf(bmp.width - 1, label.left + lh * 2)
            var x0 = -1
            for (x in xStartMin..xStartMax) { val c = bmp.getPixel(x, y); if (isFilled(c) || isTrack(c)) { x0 = x; break } }
            if (x0 < 0) continue
            var x = x0; var gap = 0; var lastBar = x0; var lastFill = if (isFilled(bmp.getPixel(x0, y))) x0 else -1
            while (x < bmp.width - 1 && gap <= gapMax) {
                x++
                val c = bmp.getPixel(x, y)
                when {
                    isFilled(c) -> { lastBar = x; gap = 0; if (lastFill >= 0 && x - lastFill <= gapMax + 1) lastFill = x; if (isRed(c)) redVotes++ else fillVotes++ }
                    isTrack(c) -> { lastBar = x; gap = 0 }
                    else -> gap++
                }
            }
            val total = lastBar - x0 + 1
            if (total < lh * 4) continue
            fracs += if (lastFill < 0) 0.0 else (lastFill - x0 + 1).toDouble() / total
        }
        if (fracs.size < 2) return null
        fracs.sort()
        return fracs[fracs.size / 2] to (redVotes > fillVotes)
    }
}
