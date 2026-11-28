package com.o37hd4k

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class MovieCard(val title: String, val url: String, val poster: String?)

internal object O37HD4KParser {
    private val hosts = setOf("037hd4k.com", "www.037hd4k.com")
    private const val listing = ".au-list .au-grid"
    fun sameSite(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.isHttps && it.host in hosts && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
    } == true

    fun cards(doc: Document, section: String = listing): List<MovieCard> =
        doc.select("$section article.au-card").mapNotNull { card ->
            val link = card.selectFirst("a.au-card__link[href]")?.absUrl("href")?.takeIf(::sameSite)
                ?: return@mapNotNull null
            val title = card.selectFirst(".au-card__title")?.text()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            MovieCard(title, link, poster(card.selectFirst(".au-card__pic img")))
        }.distinctBy { it.url }

    fun hasNext(doc: Document): Boolean = doc.select(".au-list .au-pager a.next[href]").any {
        val url = it.absUrl("href")
        sameSite(url) && com.providers.network.CatalogPagination.isNext(doc.baseUri(), url)
    }

    fun poster(img: Element?): String? = img?.let {
        sequenceOf("data-src", "src").filter { key -> it.attr(key).isNotBlank() }
            .map(it::absUrl).firstOrNull { url -> url.toHttpUrlOrNull() != null }
    }

    fun playerFrames(doc: Document): List<String> = doc.select("#au-screen iframe").mapNotNull { frame ->
        sequenceOf("data-src", "src").filter { frame.attr(it).isNotBlank() }
            .map(frame::absUrl).firstOrNull(Mv18Player::isEmbed)
    }.distinct()
}
