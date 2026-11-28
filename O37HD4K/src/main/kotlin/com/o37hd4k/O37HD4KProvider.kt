package com.o37hd4k

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.providers.network.BrowserSession
import com.providers.network.CatalogPagination
import com.providers.network.withNativePlayback
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import kotlin.coroutines.cancellation.CancellationException

@CloudstreamPlugin
class O37HD4KPlugin : Plugin() {
    override fun load(context: Context) = registerMainAPI(O37HD4KProvider())
}

class O37HD4KProvider : MainAPI() {
    override var mainUrl = "https://www.037hd4k.com"
    override var name = "037HD4K"
    override var lang = "th"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = listOf(
        mainPage("$mainUrl/", "หนังอัปเดตใหม่"),
        mainPage("$mainUrl/action/", "หนังแอ็คชั่น"),
        mainPage("$mainUrl/audio/sub/", "ซับไทย"),
        mainPage("$mainUrl/year/2026/", "หนังใหม่ปี 2026"),
    )

    private val browserSession by lazy { BrowserSession() }

    private suspend fun page(url: String, fresh: Boolean = false): Document {
        if (!O37HD4KParser.sameSite(url)) throw ErrorLoadingException("037HD4K: ลิงก์ไม่อยู่ในเว็บต้นทาง")
        val response = if (fresh) app.get(url, interceptor = browserSession, cacheTime = 0)
            else app.get(url, interceptor = browserSession)
        if (response.code !in 200..299 || !O37HD4KParser.sameSite(response.url))
            throw ErrorLoadingException("037HD4K: เปิดหน้าเว็บไม่ได้ (HTTP ${response.code})")
        return response.document.apply { setBaseUri(response.url) }
    }

    private fun MovieCard.response() = newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = page(CatalogPagination.path(request.data, page))
        return newHomePageResponse(request.name, O37HD4KParser.cards(doc).map { it.response() }, O37HD4KParser.hasNext(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/".toHttpUrl().newBuilder().addQueryParameter("s", query).build().toString()
        return O37HD4KParser.cards(page(url)).map { it.response() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = page(url)
        val title = doc.selectFirst("h1.au-title")?.text()?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("037HD4K: ไม่พบรายละเอียดหนัง")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = O37HD4KParser.poster(doc.selectFirst(".au-info-card__pic img"))
            plot = doc.selectFirst("#plot .au-text")?.text()?.takeIf(String::isNotBlank)
            recommendations = O37HD4KParser.cards(doc, "#more").filter { it.url != url }.take(12).map { it.response() }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit): Boolean = withNativePlayback(callback) { emit ->
        if (!O37HD4KParser.sameSite(data)) throw ErrorLoadingException("037HD4K: ลิงก์ไม่อยู่ในเว็บต้นทาง")
        val frames = try {
            O37HD4KParser.playerFrames(page(data, fresh = true))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        }.ifEmpty {
            // The site's own Play button creates the frame for the selected movie.
            val request = WebViewResolver(
                interceptUrl = Mv18Player.capture,
                userAgent = null,
                useOkhttp = false,
                timeout = 8_000L,
                script = Mv18Player.clickScript(data.toHttpUrl()),
            ).resolveUsingWebView(data, referer = "$mainUrl/").first
            listOfNotNull(request?.url?.toString()?.takeIf(Mv18Player::isEmbed))
        }
        if (frames.isEmpty()) throw ErrorLoadingException("037HD4K: ไม่พบลิงก์ตัวเล่นในหน้าเว็บ")
        for (frame in frames) {
            try {
                val player = app.get(frame, referer = "$mainUrl/", interceptor = browserSession, cacheTime = 0, allowRedirects = false)
                if (player.code !in 200..299) continue
                val doc = player.document.apply { setBaseUri(player.url) }
                for (stream in Mv18Player.streams(doc)) {
                    emit(newExtractorLink(name, "$name - ${stream.label}", stream.url, ExtractorLinkType.M3U8) {
                        referer = player.url
                    })
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
        true
    }
}
