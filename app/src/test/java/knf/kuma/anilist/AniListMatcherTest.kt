package knf.kuma.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Casos reales de "Sin coincidencia" del logcat (importación del 2026-10-28). */
class AniListMatcherTest {

    private fun match(titles: List<String>, names: List<String>) = AniListMatcher.pickMatch(titles, names)?.let(names::get)

    @Test fun romajiVariants() {
        assertEquals("Kimi to Boku no Saigo no Senjou, Arui wa Sekai ga Hajimaru Seisen",
            match(listOf("Kimi to Boku no Saigo no Senjo, Arui wa Sekai ga Hajimaru Seisen"),
                listOf("Kimi to Boku no Saigo no Senjou, Arui wa Sekai ga Hajimaru Seisen", "Kimi to Boku")))
        assertEquals("Haikyuu!! Second Season",
            match(listOf("Haikyuu!! 2nd Season"), listOf("Haikyuu!!", "Haikyuu!! Second Season", "Haikyuu!! To the Top")))
        assertEquals("Kobayashi-san Chi no Maid Dragon S",
            match(listOf("Kobayashi-san Chi no Maidragon S"),
                listOf("Kobayashi-san Chi no Maid Dragon: Samishigariya no Ryuu", "Kobayashi-san Chi no Maid Dragon S")))
        assertEquals("Chou Kadou Girl ⅙: Amazing Stranger",
            match(listOf("Chou Kadou Girl 1/6", "AMAZING STRANGER"), listOf("Chou Kadou Girl ⅙: Amazing Stranger", "Chou Kaguya-hime!")))
        assertEquals("Yahari Ore no Seishun Love Comedy wa Machigatteiru.",
            match(listOf("Yahari Ore no Seishun Love Come wa Machigatteiru."),
                listOf("Yahari Ore no Seishun Love Comedy wa Machigatteiru. Kan", "Yahari Ore no Seishun Love Comedy wa Machigatteiru.")))
    }

    @Test fun movies() {
        assertEquals("InuYasha Movie 3: Tenka Hadou no Ken",
            match(listOf("Inuyasha: Tenka Hadou no Ken"), listOf("InuYasha Movie 3: Tenka Hadou no Ken", "InuYasha", "InuYasha Movie 4: Guren no Houraijima")))
        assertEquals("Chuunibyou demo Koi ga Shitai! Movie: Take On Me",
            match(listOf("Chuunibyou demo Koi ga Shitai!: Take On Me"), listOf("Chuunibyou demo Koi ga Shitai! Movie: Take On Me", "Chuunibyou demo Koi ga Shitai!")))
    }

    @Test fun swappedAndSubtitle() {
        assertEquals("Tensura Nikki: Tensei shitara Slime Datta Ken",
            match(listOf("Tensei Shitara Slime Datta Ken: Tensura Nikki"),
                listOf("Tensura Nikki: Tensei shitara Slime Datta Ken", "Tensei shitara Slime Datta Ken")))
        assertEquals("Hanyou no Yashahime: Sengoku Otogizoushi",
            match(listOf("Hanyou no Yashahime"),
                listOf("Hanyou no Yashahime: Sengoku Otogizoushi 2nd Season", "Hanyou no Yashahime: Sengoku Otogizoushi")))
    }

    @Test fun keepsSeasonsAndPartsApart() {
        assertNull(match(listOf("Re:Zero kara Hajimeru Isekai Seikatsu 2nd Season Part 2"),
            listOf("Re:Zero kara Hajimeru Isekai Seikatsu 2nd Season", "Re:Zero kara Hajimeru Isekai Seikatsu 3rd Season")))
        assertNull(match(listOf("Dr. STONE: NEW WORLD Part 2"), listOf("Dr. Stone: New World", "Dr. Stone")))
        assertNull(match(listOf("Tensei Shitara Slime Datta Ken 3rd Season"),
            listOf("Tensei shitara Slime Datta Ken 2nd Season", "Tensei shitara Slime Datta Ken 4th Season")))
        assertNull(match(listOf("Haikyuu!!: Owari to Hajimari"), listOf("Haikyuu!!", "Haikyuu!! Movie: Gomisuteba no Kessen")))
    }

    @Test fun keepsOtherReleasesApart() {
        assertNull(match(listOf("Trinity Seven Movie - Tenkuu Toshokan to Shinku no Maou", "Trinity Seven: Heavens Library & Crimson Lord"),
            listOf("Trinity Seven")))
        assertNull(match(listOf("sin: Nanatsu no Taizai Zange-roku Specials", "Seven Mortal Sins Zange-roku Specials", "sin"),
            listOf("Sin: The Movie", "Beatless")))
        assertNull(match(listOf("Isekai Maou to Shoukan Shoujo no Dorei Majutsu ULT", "Isekai Maou to Shoukan Shoujo no Dorei Majutsu 3"),
            listOf("Isekai Maou to Shoukan Shoujo no Dorei Majutsu", "Isekai Maou to Shoukan Shoujo no Dorei Majutsu Ω")))
        assertNull(match(listOf("Mushoku Tensei: Isekai Ittara Honki Dasu Part 2"),
            listOf("Mushoku Tensei: Isekai Ittara Honki Dasu", "Mushoku Tensei II: Isekai Ittara Honki Dasu")))
    }

    @Test fun appToAniListIsStrict() {
        assertNull(AniListMatcher.pickMatch(listOf("Bocchi the Rock! Re:"), listOf("Bocchi the Rock!"), loose = false))
        assertNull(AniListMatcher.pickMatch(listOf("Trinity Seven"), listOf("Trinity Seven Movie 2: Heavens Library to Crimson Lord"), loose = false))
        assertEquals(0, AniListMatcher.pickMatch(listOf("Bocchi the Rock!"), listOf("Bocchi the Rock!"), loose = false))
    }

    @Test fun exactTitleFirstAndKinds() {
        assertEquals("New Game!", match(listOf("NEW GAME!"), listOf("New Game!!", "New Game!")))
        assertEquals("New Game!!", match(listOf("NEW GAME!!"), listOf("New Game!", "New Game!!")))
        assert(!AniListMatcher.sameKind("MOVIE", 1))
        assert(!AniListMatcher.sameKind("TV", 2))
        assert(!AniListMatcher.sameKind("OVA", 1))
        assert(!AniListMatcher.sameKind("SPECIAL", 1))
        assert(AniListMatcher.sameKind("MOVIE", 2) && AniListMatcher.sameKind("TV", 1) && AniListMatcher.sameKind("ONA", 1))
    }
}
