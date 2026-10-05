package io.github.szzgx.smartskip.analysis.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the text normaliser and keyword layer.
 *
 * The evasion cases here are not hypothetical: zero-width characters, full-width Latin
 * and mathematical bold letters are all real techniques used to defeat naive keyword
 * matching in splash ads.
 */
class TextNormalizerTest {

    @Test
    fun `trims and lowercases plain ascii`() {
        assertEquals("skip", TextNormalizer.normalize("  SKIP  "))
        assertEquals("skip ad", TextNormalizer.normalize("Skip   Ad"))
    }

    @Test
    fun `folds full-width latin via NFKC`() {
        // Ｓｋｉｐ — the classic full-width evasion.
        assertEquals("skip", TextNormalizer.normalize("\uFF33\uFF4B\uFF49\uFF50"))
    }

    @Test
    fun `strips zero-width characters injected inside a keyword`() {
        // "跳\u200b过" renders identically to "跳过".
        assertEquals("跳过", TextNormalizer.normalize("跳\u200b过"))
        assertEquals("skip", TextNormalizer.normalize("sk\u200bip"))
    }

    @Test
    fun `folds mathematical bold latin`() {
        // 𝐒𝐤𝐢𝐩 — NFKC does not fold this block, so the normaliser must.
        assertEquals("skip", TextNormalizer.normalize("\uD835\uDC12\uD835\uDC24\uD835\uDC22\uD835\uDC29"))
    }

    @Test
    fun `collapses whitespace runs to a single space`() {
        assertEquals("skip ad", TextNormalizer.normalize("Skip\u00A0\u3000 Ad"))
        assertEquals("a b", TextNormalizer.normalize("a\t\n  b"))
    }

    @Test
    fun `removes whitespace entirely from CJK text when matching`() {
        // Chinese does not use spaces between words, so a space inserted into a phrase
        // carries no meaning and is a known way to defeat keyword matching.
        assertEquals("跳过广告", TextNormalizer.matchingForm("跳过\u00A0\u3000广告"))
        assertEquals("跳过广告", TextNormalizer.matchingForm("跳 过 广 告"))
        assertEquals("跳过", TextNormalizer.matchingForm("跳 过"))

        // The canonical form keeps a single space, because it is also what the inspector
        // shows the user and it should look like what was on screen.
        assertEquals("跳过 广告", TextNormalizer.normalize("跳过\u00A0\u3000广告"))
    }

    @Test
    fun `removing whitespace from CJK also removes an ascii word break`() {
        // Documented consequence of the CJK rule: once text contains CJK, its spaces go,
        // including between any Latin words it mixes in. Mixed-script labels on an ad's
        // skip button are short and this is the safer direction for matching.
        assertEquals("跳过ad", TextNormalizer.matchingForm("跳过 ad"))
    }

    @Test
    fun `latin text keeps its spaces`() {
        assertEquals("skip ad", TextNormalizer.matchingForm("Skip Ad"))
        assertTrue(
            "a space is a real word boundary in Latin text and must be preserved",
            TextNormalizer.normalizeDetailed("skip ad").compacted == "skip ad",
        )
    }

    @Test
    fun `keyword matching defeats injected whitespace in both scripts`() {
        assertTrue(
            "spaced Chinese must still match the skip keyword",
            KeywordMatcher.exactMatch("跳 过", KeywordRules.SKIP_EXACT),
        )
        assertTrue(
            "spaced Chinese with disclosure must still match",
            KeywordMatcher.exactMatch("跳过 广告", KeywordRules.SKIP_EXACT),
        )
        assertTrue(
            "spaced Latin must still match",
            KeywordMatcher.exactMatch("Skip  Ad", KeywordRules.SKIP_EXACT),
        )
        assertFalse(
            "an unrelated phrase must not match",
            KeywordMatcher.exactMatch("跳过之后继续", KeywordRules.SKIP_EXACT),
        )
    }

    @Test
    fun `blank input normalises to empty string`() {
        assertEquals("", TextNormalizer.normalize(null))
        assertEquals("", TextNormalizer.normalize("   "))
        assertEquals("", TextNormalizer.normalize("\u200b"))
    }

    @Test
    fun `digest is stable and does not leak the input`() {
        val a = TextNormalizer.digest("跳过")
        val b = TextNormalizer.digest("跳\u200b过")
        assertEquals("normalisation must happen before hashing", a, b)
        assertFalse("digest must not contain the raw text", a.contains("跳"))
    }

    @Test
    fun `countdown parsing accepts real countdown labels only`() {
        assertEquals(3, TextSignalAnalyzer.parseCountdownSeconds("3"))
        assertEquals(3, TextSignalAnalyzer.parseCountdownSeconds("3s"))
        assertEquals(3, TextSignalAnalyzer.parseCountdownSeconds("3 秒"))
        assertEquals(15, TextSignalAnalyzer.parseCountdownSeconds("剩余15秒"))
        assertEquals(5, TextSignalAnalyzer.parseCountdownSeconds("跳过 5"))

        // Rejections.
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("v3.2.1"))
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("跳过"))
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("2026"))
    }

    @Test
    fun `countdown separators are matched however the ad spaced them`() {
        /*
         * Regression test for two consecutive mistakes in the "skip + countdown" pattern.
         *
         * `\D{0,4}` could not match "跳过 5" at all: `\D` consumed the space, so the following
         * `\d` had to match another space. Replacing it with `[\s\D]{0,4}?` was worse — `\D` also
         * matches digits, so the lazy quantifier swallowed the "5" before the capture group saw it
         * and "跳过5" stopped being a countdown.
         *
         * Both errors were silent: a countdown attached to a skip button simply was not recognised,
         * which lowers the score of exactly the controls this project most wants to identify.
         */
        assertEquals(5, TextSignalAnalyzer.parseCountdownSeconds("跳过 5"))
        assertEquals(5, TextSignalAnalyzer.parseCountdownSeconds("跳过5"))
        assertEquals(5, TextSignalAnalyzer.parseCountdownSeconds("跳过  5"))
        assertEquals(10, TextSignalAnalyzer.parseCountdownSeconds("跳过 10"))
        assertEquals(3, TextSignalAnalyzer.parseCountdownSeconds("Skip 3"))
        assertEquals(3, TextSignalAnalyzer.parseCountdownSeconds("跳过 3s"))
    }

    @Test
    fun `bait labels are recognised structurally, not by word list`() {
        /*
         * The fake-skip shape: skip wording that keeps talking, because the real purpose of the
         * control is the reward. A word list can never be finished (领取, 领奖, 领奖励, 赢好礼,
         * 抽大奖, 天天领 ...), whereas the skip vocabulary is small and closed.
         */
        assertTrue(KeywordMatcher.looksLikeBaitLabel("跳过领取"))
        assertTrue(KeywordMatcher.looksLikeBaitLabel("跳过领奖"))
        assertTrue(KeywordMatcher.looksLikeBaitLabel("跳过引导"))
        assertTrue(KeywordMatcher.looksLikeBaitLabel("跳过得奖励"))

        // Established skip phrasings must NOT be flagged. These two are in SKIP_EXACT because they
        // are how ordinary ads label the very button this guard exists to protect; flagging them
        // would mean never skipping an ad whose button says "跳过广告".
        assertFalse(KeywordMatcher.looksLikeBaitLabel("跳过"))
        assertFalse(KeywordMatcher.looksLikeBaitLabel("跳过广告"))
        assertFalse(KeywordMatcher.looksLikeBaitLabel("跳过此广告"))

        // A countdown and a Latin phrase are not bait either.
        assertFalse(KeywordMatcher.looksLikeBaitLabel("跳过 5"))
        assertFalse(KeywordMatcher.looksLikeBaitLabel("跳过 Ad"))
        assertFalse(KeywordMatcher.looksLikeBaitLabel("Skip Ad"))

        // And a sentence that merely contains the word is not bait, because "跳过" there does not
        // start a word.
        assertFalse(KeywordMatcher.looksLikeBaitLabel("如何跳过步骤"))
    }

    @Test
    fun `a bare number is only a countdown when it is short enough to be one`() {        /*
         * Calibrated from a real capture of Zhihu's feed, which produced
         * `desc='赞同58' text='58' id=statusTv` and `desc='收藏42' text='42'` — engagement
         * counters being read as countdowns. Splash-ad countdowns are short; counters are not.
         */
        assertEquals(9, TextSignalAnalyzer.parseCountdownSeconds("9"))
        assertEquals(15, TextSignalAnalyzer.parseCountdownSeconds("15"))
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("31"))
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("42"))
        assertEquals(null, TextSignalAnalyzer.parseCountdownSeconds("58"))

        // A *labelled* countdown of the same magnitude is still accepted, because the label is
        // real evidence that the number means seconds.
        assertEquals(31, TextSignalAnalyzer.parseCountdownSeconds("剩余31秒"))
        assertEquals(31, TextSignalAnalyzer.parseCountdownSeconds("31s"))
    }

    @Test
    fun `keyword sets do not accidentally treat disclosure as skip wording`() {
        // "广告" is disclosure, never skip wording.
        assertFalse("广告" in KeywordRules.SKIP_EXACT)
        assertFalse("广告" in KeywordRules.SKIP_CONTAINS)
        assertTrue("广告" in KeywordRules.AD_DISCLOSURE)
    }

    @Test
    fun `dangerous wording excludes the word for advertisement itself`() {
        // "广告" must not be dangerous, or every splash screen would veto itself.
        assertFalse("广告" in KeywordRules.STRONG_DANGER)
        assertTrue("立即下载" in KeywordRules.STRONG_DANGER)
        assertTrue("立即支付" in KeywordRules.STRONG_DANGER)
    }
}
