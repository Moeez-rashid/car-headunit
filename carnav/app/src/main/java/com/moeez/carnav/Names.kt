package com.moeez.carnav

/**
 * Makes OpenStreetMap names readable in English. Many Pakistani roads are
 * tagged with an Urdu-only `name` (e.g. "مال روڈ" for Mall Road), and the
 * routing service returns that name as-is. Offline, no network:
 *
 *  1. Mixed names ("Empress Road ایمپریس روڈ") keep just the English part.
 *  2. Urdu-only names: known words and phrases are translated from a
 *     dictionary (roads, places, common name parts) …
 *  3. … and anything else is spelled out phonetically in Roman Urdu.
 */
object Names {

    fun english(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val s = raw.replace(BIDI, "").trim()
        if (!URDU.containsMatchIn(s)) return s

        if (LATIN.containsMatchIn(s)) {
            // Mixed: drop the Urdu words, tidy leftover brackets/dashes/spaces.
            return s.split(Regex("\\s+"))
                .filterNot { URDU.containsMatchIn(it) }
                .joinToString(" ")
                .replace(Regex("[()\\[\\]]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '–', '/', ',', '،')
        }

        // Urdu only: phrases first, then word by word.
        var text = s.replace("،", " ").replace("-", " ")
        for ((ur, en) in PHRASES) text = text.replace(ur, " $en ")
        return text.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when {
                    !URDU.containsMatchIn(word) -> word
                    word in WORDS -> WORDS.getValue(word)
                    else -> transliterate(word)
                }
            }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Normalised lookup key for a raw OSM name (bidi marks removed). */
    fun key(raw: String?): String = raw.orEmpty().replace(BIDI, "").trim()

    /** True for names written only in Urdu script (these need converting). */
    fun isUrduOnly(s: String): Boolean = URDU.containsMatchIn(s) && !LATIN.containsMatchIn(s)

    /**
     * Does an English name looked up online plausibly refer to the same road as
     * our offline conversion? Compares consonant skeletons word by word, which
     * tolerates spelling differences (Ferozepur/Firozpur, Nisbat/Nisbet) but
     * rejects a lookup that landed on a different road (Davis Road ≠ Simla Hill).
     */
    fun plausible(local: String, candidate: String): Boolean {
        fun skel(w: String) = w.lowercase().filter { it in 'a'..'z' || it.isDigit() }
            .replace(Regex("[aeiou]"), "").replace(Regex("(.)\\1+"), "$1")
        fun tokens(s: String) = s.split(Regex("[^A-Za-z0-9]+")).map(::skel)
            .filter { it.length >= 2 || it.any(Char::isDigit) }
        val l = tokens(local)
        val c = tokens(candidate)
        if (l.isEmpty() || c.isEmpty()) return false
        // Short tokens must match exactly; longer ones may be contained (Bansan ⊂ Bansaanwala).
        fun match(t: String) = c.any { it == t || (minOf(it.length, t.length) >= 3 && (it.contains(t) || t.contains(it))) }
        val specific = l.filterNot { it in GENERIC }
        if (specific.isEmpty()) return l.any(::match)
        return specific.count(::match) * 2 >= specific.size
    }

    // Skeletons of road-type words, which mustn't decide a match on their own.
    private val GENERIC = setOf("rd", "strt", "st", "gl", "bzr", "chwk", "ln", "vn", "blvrd", "lnk", "mn", "brdg")

    /** Rough Roman-Urdu spelling for words not in the dictionary. */
    fun transliterate(word: String): String {
        // Digits first (Urdu ۰-۹ and Arabic-Indic ٠-٩).
        val w = word.map { c ->
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        }.joinToString("")
        if (!URDU.containsMatchIn(w)) return w

        // Split into sound units: consonant (+ ھ aspiration) or vowel letter.
        data class Unit(val text: String, val vowel: Boolean)
        val units = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val first = units.isEmpty()
            val last = i == w.lastIndex || (i == w.lastIndex - 1 && w[i + 1] == 'ں')
            val u: Unit? = when (c) {
                'ا' -> Unit("a", true)
                'آ' -> Unit("aa", true)
                'ع' -> Unit("a", true)
                'و' -> if (first) Unit("w", false) else Unit("o", true)
                'ی', 'ي', 'ى' -> if (first) Unit("y", false) else Unit("i", true) // ے is the "e"
                'ے' -> Unit("e", true)
                'ئ' -> Unit("i", true)
                'ؤ' -> Unit("o", true)
                'ہ', 'ه', 'ة' -> if (last && !first) Unit("a", true) else Unit("h", false)
                'ھ' -> { // aspiration joins the previous consonant: ب+ھ = bh
                    val prev = units.lastOrNull()
                    if (prev != null && !prev.vowel) { units[units.lastIndex] = Unit(prev.text + "h", false); null }
                    else Unit("h", false)
                }
                'ء' -> null
                else -> CONSONANTS[c]?.let { Unit(it, false) }
            }
            if (u != null) units += u
            i++
        }
        if (units.isEmpty()) return ""

        // Urdu omits short vowels; adding "a" after an opening consonant that's
        // followed by another consonant reads naturally (حکیماں → Hakiman).
        val sb = StringBuilder()
        for ((k, u) in units.withIndex()) {
            sb.append(u.text)
            if (k == 0 && !u.vowel && units.getOrNull(1)?.vowel == false) sb.append('a')
        }
        return sb.toString().replaceFirstChar { it.uppercase() }
    }

    private val BIDI = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val URDU = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val LATIN = Regex("[A-Za-z]")

    private val CONSONANTS = mapOf(
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r",
        'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t",
        'ظ' to "z", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ک' to "k", 'ك' to "k", 'گ' to "g",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'ں' to "n"
    )

    /** Multi-word phrases, matched before splitting into words. */
    private val PHRASES = listOf(
        "نہر سڑک" to "Canal Road",
        "فلائی اوور" to "Flyover",
        "انڈر پاس" to "Underpass",
        "جی ٹی روڈ" to "GT Road",
        "قائد اعظم" to "Quaid-e-Azam",
        "علامہ اقبال" to "Allama Iqbal",
        "ماڈل ٹاؤن" to "Model Town",
        "گارڈن ٹاؤن" to "Garden Town",
        "جوہر ٹاؤن" to "Johar Town"
    )

    /** Common words in Pakistani road and place names. */
    private val WORDS = mapOf(
        // road types
        "روڈ" to "Road", "سڑک" to "Road", "شاہراہ" to "Shahrah", "اسٹریٹ" to "Street",
        "سٹریٹ" to "Street", "گلی" to "Gali", "ایونیو" to "Avenue", "بلیوارڈ" to "Boulevard",
        "بولیوارڈ" to "Boulevard", "پل" to "Bridge", "چوک" to "Chowk", "بازار" to "Bazaar",
        "لنک" to "Link", "بائی" to "By", "پاس" to "Pass", "موٹروے" to "Motorway", "ہائی" to "High",
        "وے" to "Way", "رنگ" to "Ring", "سروس" to "Service", "مین" to "Main", "خیابان" to "Khayaban",
        "خیبان" to "Khayaban", "خیابانِ" to "Khayaban-e",
        // places and areas
        "لاہور" to "Lahore", "کراچی" to "Karachi", "اسلام" to "Islam", "آباد" to "Abad",
        "راولپنڈی" to "Rawalpindi", "ملتان" to "Multan", "فیصل" to "Faisal", "فیصل آباد" to "Faisalabad",
        "گوجرانوالہ" to "Gujranwala", "سیالکوٹ" to "Sialkot", "قصور" to "Kasur", "شیخوپورہ" to "Sheikhupura",
        "فیروزپور" to "Ferozepur", "رائیونڈ" to "Raiwind", "کینٹ" to "Cantt", "ٹاؤن" to "Town",
        "کالونی" to "Colony", "بلاک" to "Block", "فیز" to "Phase", "سیکٹر" to "Sector",
        "مارکیٹ" to "Market", "مال" to "Mall", "محلہ" to "Mohalla", "پورہ" to "Pura", "گنج" to "Ganj",
        "منڈی" to "Mandi", "کوٹ" to "Kot", "گاؤں" to "Village", "نہر" to "Canal", "دریا" to "River",
        "راوی" to "Ravi", "مسجد" to "Masjid", "دربار" to "Darbar", "ہسپتال" to "Hospital",
        "اسٹیشن" to "Station", "اڈا" to "Adda", "انارکلی" to "Anarkali", "گلبرگ" to "Gulberg",
        "ماڈل" to "Model", "گارڈن" to "Garden", "جوہر" to "Johar", "واپڈا" to "Wapda",
        "ٹاؤن شپ" to "Township", "شادمان" to "Shadman", "شالیمار" to "Shalimar", "داتا" to "Data",
        "ٹھوکر" to "Thokar", "نیاز" to "Niaz", "بیگ" to "Baig", "اکیڈمی" to "Academy",
        "جیل" to "Jail", "کالج" to "College", "یونیورسٹی" to "University", "اسکول" to "School",
        "سکول" to "School", "پارک" to "Park", "باغ" to "Bagh", "والا" to "Wala", "والی" to "Wali",
        // colonial-era and named roads
        "ایبٹ" to "Abbott", "ایمپریس" to "Empress", "ڈیوس" to "Davis", "ڈیورنڈ" to "Durand",
        "میکلوڈ" to "McLeod", "نکلسن" to "Nicholson", "کوئنز" to "Queens", "والٹن" to "Walton",
        "سرکلر" to "Circular", "نسبت" to "Nisbat", "جیل روڈ" to "Jail Road", "فین" to "Fane",
        "لارنس" to "Lawrence", "ایجرٹن" to "Egerton", "کوپر" to "Cooper", "لوئر" to "Lower",
        "اپر" to "Upper",
        // people's names common in road names
        "علامہ" to "Allama", "اقبال" to "Iqbal", "جناح" to "Jinnah", "قائد" to "Quaid",
        "اعظم" to "Azam", "لیاقت" to "Liaquat", "علی" to "Ali", "محمد" to "Muhammad",
        "حسین" to "Hussain", "حسن" to "Hassan", "عزیز" to "Aziz", "بھٹی" to "Bhatti",
        "شاہ" to "Shah", "شیخ" to "Sheikh", "میاں" to "Mian", "چوہدری" to "Chaudhry",
        "ملک" to "Malik", "خان" to "Khan", "نور" to "Noor", "احمد" to "Ahmad", "فاطمہ" to "Fatima",
        "جمال" to "Jamal", "رحمان" to "Rehman", "عبداللہ" to "Abdullah", "ظفر" to "Zafar",
        "حکیماں" to "Hakiman", "بانساں" to "Bansan"
    )
}
