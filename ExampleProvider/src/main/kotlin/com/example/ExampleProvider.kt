package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://tv2.egydead.live"
    override var name = "EgyDead"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private fun Element.absPoster(): String? =
        selectFirst("img[src]")?.attr("abs:src")

    // ---------- Main page (Latest) ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url).document

        val cards = doc.select("article a[href], .post a[href]").distinctBy { it.absUrl("href") }
        val items = cards.mapNotNull { a ->
            val href = a.absUrl("href")
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = a.closest("article")?.absPoster()

            if (title.contains("الحلقة") || title.contains("حلقه", true)) {
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.selectFirst("a.next, .pagination a:matchesOwn(التالي|Next)") != null
        return newHomePageResponse(listOf(HomePageList("Latest", items)), hasNext)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url).document

        val cards = doc.select("article a[href], .post a[href]").distinctBy { it.absUrl("href") }
        return cards.mapNotNull { a ->
            val href = a.absUrl("href")
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = a.closest("article")?.absPoster()

            if (title.contains("الحلقة") || title.contains("حلقه", true)) {
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
            }
        }
    }

    // ---------- Load a title page ----------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1, .entry-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image], .post img, .entry-content img")
            ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

        val plot = doc.selectFirst(".entry-content p, .post p")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val eps = doc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").map { a ->
            val epUrl = a.absUrl("href")
            val epName = a.text().trim()
            val epNum = Regex("(\\d+)").find(epName)?.value?.toIntOrNull()
            newEpisode(epUrl) {
                name = epName
                episode = epNum
            }
        }

        return if (eps.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster; this.plot = plot; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, data = url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
            }
        }
    }

    // ---------- Extract links (uses suspend newExtractorLink + for-loops) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false

        suspend fun push(url: String) {
            if (url.isBlank()) return
            val isM3u8 = url.contains(".m3u8", ignoreCase = true)
            val link = newExtractorLink(
                source = "EgyDead",
                name = if (isM3u8) "EgyDead HLS" else "EgyDead",
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = data
                quality = Qualities.Unknown.value
            }
            callback(link)
            found = true
        }

        // 1) Direct <video>/<source> tags
        val direct = doc.select("video[src], video > source[src]")
        for (el in direct) {
            push(el.absUrl("src"))
        }

        // 2) Open Graph video meta
        val og = doc.select("meta[property=og:video][content]")
        for (m in og) {
            push(m.attr("abs:content"))
        }

        // 3) One-hop into iframes
        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.absUrl("src")
            if (src.isNotBlank()) {
                kotlin.runCatching {
                    val iframeDoc = app.get(src, referer = data).document
                    val vids = iframeDoc.select("video[src], video > source[src]")
                    for (el in vids) {
                        push(el.absUrl("src"))
                    }
                    val og2 = iframeDoc.select("meta[property=og:video][content]")
                    for (m in og2) {
                        push(m.attr("abs:content"))
                    }
                }
            }
        }

        return found
    }
}
