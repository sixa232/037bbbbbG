package com.o37hd4k

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class Mv18Stream(val url: String, val label: String)

internal object Mv18Player {
    private val json = ObjectMapper()
    private val playPath = Regex("/play/[A-Za-z0-9_-]+/?")
    private val manifestPath = Regex("/hls/(?:n/)?[A-Za-z0-9_-]+\\.m3u8")
    private val config = Regex("""window\.PLAYER_CONFIG\s*=\s*(\{.*});\s*$""", RegexOption.DOT_MATCHES_ALL)
    val capture = Regex("""^https://play\.mv18k\.com/play/[A-Za-z0-9_-]+/?(?:\?.*)?$""")

    private fun playerHost(url: HttpUrl): Boolean =
        url.isHttps && url.host == "play.mv18k.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty()

    fun isEmbed(url: String): Boolean =
        url.toHttpUrlOrNull()?.let { playerHost(it) && playPath.matches(it.encodedPath) } == true

    fun clickScript(movie: HttpUrl): String {
        val path = json.writeValueAsString(movie.encodedPath.trimEnd('/'))
        return """
            (() => {
                if (window !== window.top || location.protocol !== 'https:' || location.port ||
                    !['037hd4k.com', 'www.037hd4k.com'].includes(location.hostname) ||
                    location.pathname.replace(/\/$/, '') !== $path || window.__cs037Started) return;
                window.__cs037Started = true;
                const play = () => document.querySelector('#au-screen button#au-play')?.click();
                if (document.readyState === 'complete') play();
                else window.addEventListener('load', play, { once: true });
            })();
        """.trimIndent()
    }

    fun streams(doc: Document): List<Mv18Stream> {
        val embed = doc.baseUri().toHttpUrlOrNull()?.takeIf { isEmbed(it.toString()) } ?: return emptyList()
        val literal = doc.select("script:not([src])").firstNotNullOfOrNull {
            config.find(it.data())?.groupValues?.get(1)
        } ?: return emptyList()
        val sources = runCatching { json.readTree(literal).path("sources") }.getOrNull() ?: return emptyList()
        if (!sources.isArray) return emptyList()
        return sources.mapNotNull { source ->
            // The website uses nativeHls for native playback, rather than its browser JS stream.
            val url = sequenceOf("nativeHls", "hls").mapNotNull { key ->
                source.path(key).asText().takeIf(String::isNotBlank)?.let(embed::resolve)
            }.firstOrNull { playerHost(it) && manifestPath.matches(it.encodedPath) } ?: return@mapNotNull null
            val audio = when (source.path("audio").asText()) {
                "dub" -> "พากย์ไทย"
                "sub" -> "ซับไทย"
                else -> null
            }
            val server = source.path("server").asText().toIntOrNull()?.takeIf { it > 0 }?.let { "ตัวเล่น $it" }
            Mv18Stream(url.toString(), listOfNotNull(audio, server).joinToString(" - ").ifBlank { "MV18K" })
        }.distinctBy { it.url }
    }
}
