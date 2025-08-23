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

    // ------------------ MAIN PAGE (best-effort) ------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url, referer = mainUrl).document

        // Try several common layouts; fall back to generic anchors
        val cards = buildList {
            addAll(doc.select("article .entry-title a[href]"))
            addAll(doc.select("article a[href][title]"))
            addAll(doc.select(".post a[href][title], .movie-item a[href], .ml-item a[href]"))
            if (isEmpty()) addAll(doc.select("a[href]"))
        }.distinctBy { it.absUrl("href") }

        val items = cards.mapNotNull { a ->
            val href = a.absUrl("href")
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            if (isJunkLink(href)) return@mapNotNull null

            val titleGuess = a.attr("title").ifBlank { a.text() }.ifBlank { slugToTitle(href) }.trim()
            val poster = a.closest("article")?.absPoster()

            // Heuristic: fetch the page once to decide movie vs series
            val pDoc = kotlin.runCatching { app.get(href, referer = url).document }.getOrNull()
                ?: return@mapNotNull null
            val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()

            if (isSeries)
                newTvSeriesSearchResponse(titleGuess, href) { this.posterUrl = poster }
            else
                newMovieSearchResponse(titleGuess, href) { this.posterUrl = poster }
        }

        val hasNext = doc.selectFirst("a.next, .pagination a:matchesOwn(التالي|Next)") != null
        return newHomePageResponse(listOf(HomePageList("Latest", items)), hasNext)
    }

    // ------------------ SEARCH (robust) ------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, referer = mainUrl).document

        // Collect many anchors then filter aggressively
        val links = doc.select("a[href]").map { it.absUrl("href") }
            .filter { it.startsWith(mainUrl) && !isJunkLink(it) }
            .distinct()
            .take(20)

        val results = mutableListOf<SearchResponse>()
        for (href in links) {
            val pDoc = kotlin.runCatching { app.get(href, referer = url).document }.getOrNull() ?: continue

            val title = pDoc.selectFirst("h1, .entry-title, meta[property=og:title]")?.let {
                it.attr("content").ifBlank { it.text() }
            }?.trim()?.ifBlank { slugToTitle(href) } ?: continue

            val poster = pDoc.selectFirst("meta[property=og:image], .post img, .entry-content img")
                ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

            val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()
            results += if (isSeries)
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            else
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
        }
        return results
    }

    // ------------------ LOAD A TITLE PAGE ------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1, .entry-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image], .post img, .entry-content img")
            ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

        val plot = doc.selectFirst(".entry-content p, .post p")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val eps = doc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").map { a ->
            val epUrl = a.absUrl("href")
            val epName = a.text().trim()
            val epNum = Regex("(\\d+)").find(epName)?.value?.toIntOrNull()
            newEpisode(epUrl) { name = epName; episode = epNum }
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

    // ------------------ EXTRACT LINKS ------------------
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

        // direct video tags
        for (el in doc.select("video[src], video > source[src]")) {
            push(el.absUrl("src"))
        }
        // og:video
        for (m in doc.select("meta[property=og:video][content]")) {
            push(m.attr("abs:content"))
        }
        // 1 hop iframes
        for (iframe in doc.select("iframe[src]")) {
            val src = iframe.absUrl("src")
            if (src.isBlank()) continue
            kotlin.runCatching {
                val iDoc = app.get(src, referer = data).document
                for (el in iDoc.select("video[src], video > source[src]")) push(el.absUrl("src"))
                for (m in iDoc.select("meta[property=og:video][content]")) push(m.attr("abs:content"))
            }
        }
        return found
    }

    // ------------------ helpers ------------------
    private fun isJunkLink(href: String): Boolean {
        val bad = listOf("/category/", "/tag/", "/genre", "/genres", "/year/", "/page/",
            "/feed", "/author/", "/wp-", "/attachment/")
        return bad.any { href.contains(it) }
    }

    private fun slugToTitle(href: String): String =
        href.substringAfterLast('/').substringBeforeLast('.')
            .replace('-', ' ')
            .replace(Regex("%[0-9A-Fa-f]{2}")) { runCatching { Char(it.value.substring(1).toInt(16)).toString() }.getOrDefault(" ") }
            .trim()
} 
