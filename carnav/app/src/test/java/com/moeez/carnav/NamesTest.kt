package com.moeez.carnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Urdu road name below came back from OSRM on real Lahore routes. */
class NamesTest {

    private val urdu = Regex("[\\u0600-\\u06FF]")

    @Test fun mixedNamesKeepTheEnglishPart() {
        assertEquals("Abbott Road", Names.english("Abbott Road ایبٹ روڈ"))
        assertEquals("Empress Road", Names.english("Empress Road ایمپریس روڈ"))
    }

    @Test fun realLahoreRoadsBecomeEnglish() {
        val expected = mapOf(
            "مال روڈ" to "Mall Road",
            "نہر سڑک‎" to "Canal Road",
            "ڈیوس روڈ" to "Davis Road",
            "ڈیورنڈ روڈ" to "Durand Road",
            "علامہ اقبال روڈ" to "Allama Iqbal Road",
            "فیروزپور روڈ" to "Ferozepur Road",
            "عزیز بھٹی روڈ" to "Aziz Bhatti Road",
            "سرکلر روڈ" to "Circular Road",
            "والٹن روڈ" to "Walton Road",
            "نسبت روڈ" to "Nisbat Road",
            "اکیڈمی روڈ" to "Academy Road",
            "اسٹریٹ 13" to "Street 13",
            "خیبان جناح" to "Khayaban Jinnah",
            "بازار حکیماں" to "Bazaar Hakiman",
            "بانساں والا بازار" to "Bansan Wala Bazaar",
            "گلی نور محلہ" to "Gali Noor Mohalla"
        )
        for ((ur, en) in expected) assertEquals(ur, en, Names.english(ur))
    }

    @Test fun unknownWordsAreSpelledInRomanUrdu() {
        // Not in the dictionary: must still come out as readable Latin text.
        for (w in listOf("حکیم", "سمن آباد", "شاہ جمال", "رحمان پورہ", "کرشن نگر", "۱۲ بلاک")) {
            val out = Names.english(w)
            assertTrue("empty for $w", out.isNotBlank())
            assertFalse("still Urdu: $w -> $out", urdu.containsMatchIn(out))
        }
        assertEquals("Hakim", Names.transliterate("حکیم"))
        assertEquals("12 Block", Names.english("۱۲ بلاک"))
    }

    @Test fun englishAndEmptyPassThrough() {
        assertEquals("Main Boulevard Gulberg", Names.english("Main Boulevard Gulberg"))
        assertEquals("", Names.english(""))
        assertEquals("", Names.english(null))
    }

    @Test fun instructionsUseEnglishNames() {
        val step = Step("turn", "left", "مال روڈ", null, null, LatLon(0.0, 0.0), 0.0, 0.0)
        assertEquals("Turn left onto Mall Road", Instructions.text(step))
    }
}

/** Validation of names looked up online (real Nominatim answers, 2026-11-21). */
class NameLookupTest {

    @Test fun acceptsTheRealLookups() {
        val real = mapOf(
            "مزنگ روڈ" to "Mozang Road",
            "اکیڈمی روڈ" to "Academy Road",
            "والٹن روڈ" to "Walton Road",
            "خیبان جناح" to "Khayaban-e-Jinnah",
            "اسٹریٹ 13" to "Street 13",
            "گلی نور محلہ" to "Gali Noor Mohallah",
            "بازار حکیماں" to "Hakiman Bazaar",
            "سرکلر روڈ" to "Circular Road",
            "فیروزپور روڈ" to "Firozpur Road",
            "نہر سڑک" to "East Canal Bank Road",
            "بانساں والا بازار" to "Bansaanwala Bazaar",
            "نسبت روڈ" to "Nisbet Road",
            "مال روڈ" to "Mall Road",
            "عزیز بھٹی روڈ" to "Aziz Bhatti Road",
            "علامہ اقبال روڈ" to "Allama Iqbal Road"
        )
        for ((ur, en) in real) assertTrue("$ur: ${Names.english(ur)} vs $en", Names.plausible(Names.english(ur), en))
    }

    @Test fun rejectsALookupThatHitTheWrongRoad() {
        // The probe for Davis Road landed on a different feature.
        assertFalse(Names.plausible(Names.english("ڈیوس روڈ"), "Simla Hill"))
        assertFalse(Names.plausible("Mall Road", "Simla Hill"))
        assertFalse(Names.plausible("Mall Road", "Jail Road"))
    }

    @Test fun resolvedNamesWinOverOfflineConversion() {
        val step = Step("turn", "right", "مزنگ روڈ", null, null, LatLon(0.0, 0.0), 0.0, 0.0)
        assertEquals("Turn right onto Mazng Road", Instructions.text(step)) // offline guess
        Instructions.resolvedName = { if (it == "مزنگ روڈ") "Mozang Road" else null }
        try {
            assertEquals("Turn right onto Mozang Road", Instructions.text(step))
        } finally {
            Instructions.resolvedName = { null }
        }
    }

    @Test fun pointAlongTheRoute() {
        val r = Route(listOf(LatLon(31.5, 74.3), LatLon(31.5, 74.31)), 0.0, 0.0, emptyList())
        val mid = r.pointAt(r.length / 2)
        assertEquals(74.305, mid.lon, 1e-6)
        assertEquals(r.points.last(), r.pointAt(r.length + 100))
    }
}
