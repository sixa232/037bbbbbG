package com.o37hd4k

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class O37HD4KParserTest {
    private val base = "https://www.037hd4k.com/"

    @Test fun `catalog only uses main grid cards on the source website`() {
        fun card(href: String) = """
            <article class="au-card"><a class="au-card__link" href="$href">
                <span class="au-card__pic"><img src="/poster.jpg"></span>
                <span class="au-card__title">Clashing Through The Snow (2026)</span>
            </a></article>
        """.trimIndent()
        val doc = Jsoup.parse("""
            ${card("/featured/")}
            <section class="au-list"><div class="au-grid">
                ${card("/clashing-through-the-snow/")}
                ${card("/clashing-through-the-snow/")}
                ${card("https://037hd4k.com.example/movie/")}
            </div>
            <nav class="au-pager"><a class="next page-numbers" href="/page/2/">Next</a></nav></section>
        """, base)
        assertEquals(listOf(MovieCard("Clashing Through The Snow (2026)",
            "${base}clashing-through-the-snow/", "${base}poster.jpg")), O37HD4KParser.cards(doc))
        assertTrue(O37HD4KParser.hasNext(doc))
        doc.selectFirst("a.next")!!.attr("href", "#")
        assertFalse(O37HD4KParser.hasNext(doc))
    }

    @Test fun `a play button alone is not a video link and unrelated iframes are ignored`() {
        val doc = Jsoup.parse("""
            <iframe src="https://ad.example/banner"></iframe>
            <div id="au-screen" data-id="24332"><button id="au-play">Play</button></div>
        """, base)
        assertTrue(O37HD4KParser.playerFrames(doc).isEmpty())
        doc.selectFirst("#au-screen")!!.append(
            """<iframe src="about:blank" data-src="https://play.mv18k.com/play/test_id"></iframe>"""
        )
        assertEquals(listOf("https://play.mv18k.com/play/test_id"), O37HD4KParser.playerFrames(doc))
    }
    @Test fun `player uses native HLS with the advertised audio and ignores foreign media`() {
        val host = "https://play.mv18k.com"
        val doc = Jsoup.parse("""
            <script>window.PLAYER_CONFIG = {"sources":[
                {"server":1,"audio":"dub","nativeHls":"/hls/n/dub_id.m3u8?token=test","hls":"/hls/web_id.m3u8"},
                {"server":2,"audio":"sub","hls":"/hls/sub_id.m3u8"},
                {"server":3,"nativeHls":"https://ads.example/hls/n/ad.m3u8"}
            ]};</script>
        """, "$host/play/example_id")
        assertEquals(listOf(
            Mv18Stream("$host/hls/n/dub_id.m3u8?token=test", "พากย์ไทย - ตัวเล่น 1"),
            Mv18Stream("$host/hls/sub_id.m3u8", "ซับไทย - ตัวเล่น 2"),
        ), Mv18Player.streams(doc))
        doc.setBaseUri("https://play.mv18k.com.example/play/example_id")
        assertTrue(Mv18Player.streams(doc).isEmpty())
    }
}
