package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ExampleProvider : MainAPI() {

    override var mainUrl = "https://tv2.egydead.live"
    override var name = "EgyDead"
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private fun Element.absPoster(): String? = selectFirst("img[src]")?.attr("abs:src")

    // ---------- Helpers ----------
    private fun isJunkLink(href: String): Boolean {
        if (!href.startsWith(mainUrl)) return true

        val normalized = href.removeSuffix("/")
        val path = normalized.removePrefix(mainUrl).lowercase()

        if (path.isBlank()) return true

        // Root sections that are not individual titles
        val rootSections = setOf("movies", "series", "anime", "shows")
        if (rootSections.contains(path)) return true

        // Obvious non-content paths / paginations / tags / categories
        val badStarts = listOf(
            "category/", "tag/", "genres", "genre/", "year/", "page/",
            "author/", "wp-", "attachment/", "section/", "search", "?s="
        )
        if (badStarts.any { path.startsWith(it) || path.contains(it) }) return true

        // Arabic slug for "قسم" (section)
        if (path.contains("%d9%82%d8%b3%d9%85")) return true

        return false
    }

    private fun slugToTitle(href: String): String =
        href.substringAfterLast('/').substringBeforeLast('.')
            .replace('-', ' ')
            .replace(Regex("%[0-9A-Fa-f]{2}")) {
                runCatching { Char(it.value.substring(1).toInt(16)).toString() }.getOrDefault(" ")
            }
            .trim()

    /** Try to find a dedicated "watch" page/button on a title page. */
    private fun Document.findWatchLink(fallback: String): String {
        val btn = select(
            "a[href*=/watch], a[href*=/play], a[href*=/player], " +
                "a:matchesOwn(مشاهدة|مشاهده|شاهد|Play|Watch), " +
                "button:matchesOwn(مشاهدة|Play)"
        ).firstOrNull()
        return btn?.absUrl("href")?.ifBlank { null } ?: fallback
    }

    /** Extract direct media urls from HTML (m3u8/mp4/tok-en codes in scripts). */
    private fun extractMediaUrls(html: String): List<String> {
        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")

        val rx = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        val urls = rx.findAll(cleaned).map { it.value }.toMutableList()

        // Try common JSON fields: "file": "...", "src": "..."
        val rx2 = Regex(
            """(?:"file"|['"]src['"])\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        urls += rx2.findAll(cleaned).map { it.groupValues[1] }

        return urls.distinct()
    }

    private suspend fun pushLink(
        pageUrl: String,
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = mediaUrl.contains(".m3u8", true)
        val link = newExtractorLink(
            source = "EgyDead",
            name = if (isM3u8) "EgyDead HLS" else "EgyDead",
            url = mediaUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            referer = pageUrl
            quality = Qualities.Unknown.value
        }
        callback(link)
    }

    // ---------- Main page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(url, referer = mainUrl).document

        // Prefer real post/card entries, avoid falling back to "all links" which leaks categories
        val anchors = buildList<Element> {
            addAll(doc.select("article .entry-title a[href]"))
            addAll(doc.select(".post .entry-title a[href]"))
            addAll(doc.select(".movie-item a[href], .ml-item a[href]"))
            // No all-links fallback here on purpose
        }.distinctBy { it.absUrl("href") }

        val items = anchors.mapNotNull { a ->
            val href = a.absUrl("href")
            if (isJunkLink(href)) return@mapNotNull null

            val titleGuess = a.attr("title").ifBlank { a.text() }.ifBlank { slugToTitle(href) }.trim()
            val poster = a.closest("article")?.absPoster()

            // Probe the page to decide movie vs series
            val pDoc = runCatching { app.get(href, referer = url).document }.getOrNull() ?: return@mapNotNull null
            val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()

            if (isSeries) {
                newTvSeriesSearchResponse(titleGuess, href) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(titleGuess, href) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.selectFirst("a.next, .pagination a:matchesOwn(التالي|Next)") != null
        return newHomePageResponse(listOf(HomePageList("Latest", items)), hasNext)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, referer = mainUrl).document

        // Prefer entry/title anchors; if none, try a safe fallback of likely title links (not all links)
        val entryAnchors = doc.select(
            "article .entry-title a[href], .post .entry-title a[href], .movie-item a[href], .ml-item a[href]"
        ).ifEmpty {
            doc.select("a[href*=/watch], a[href*=/episode], a[href][title]")
        }

        val links = entryAnchors
            .map { it.absUrl("href") }
            .filter { !isJunkLink(it) }
            .distinct()
            .take(40)

        val out = mutableListOf<SearchResponse>()
        for (href in links) {
            val pDoc = runCatching { app.get(href, referer = url).document }.getOrNull() ?: continue

            val title = pDoc.selectFirst("h1, .entry-title, meta[property=og:title]")
                ?.let { it.attr("content").ifBlank { it.text() } }
                ?.trim()
                ?.ifBlank { slugToTitle(href) }
                ?: continue

            val poster = pDoc.selectFirst("meta[property=og:image], .post img, .entry-content img")
                ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

            val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()

            out += if (isSeries) {
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
            }
        }
        return out
    }

    // ---------- Load ----------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1, .entry-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image], .post img, .entry-content img")
            ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

        val plot = doc.selectFirst(".entry-content p, .post p")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val eps = doc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)")
            .map { a ->
                val epUrl = a.absUrl("href")
                val epName = a.text().trim()
                val epNum = Regex("(\\d+)").find(epName)?.value?.toIntOrNull()
                newEpisode(epUrl) { name = epName; episode = epNum }
            }

        return if (eps.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // Movie: save the *watch* url in data so loadLinks can go straight there
            val watch = doc.findWatchLink(url)
            newMovieLoadResponse(title, url, TvType.Movie, data = watch) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // ---------- Extract links ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data may already be a watch url (for movies). If not, use the page and find the watch url.
        val firstDoc = app.get(data, referer = mainUrl).document
        var pageUrl = firstDoc.findWatchLink(data)
        var doc = if (pageUrl == data) firstDoc else app.get(pageUrl, referer = data).document

        var found = false

        suspend fun addAll(urls: List<String>) {
            for (u in urls) {
                found = true
                pushLink(pageUrl, u, callback)
            }
        }

        // 1) direct video/source tags
        addAll(doc.select("video[src], video > source[src]").mapNotNull { it.absUrl("src") })

        // 2) og:video
        addAll(doc.select("meta[property=og:video][content]").mapNotNull { it.attr("abs:content") })

        // 3) any scripts containing media urls
        addAll(extractMediaUrls(doc.outerHtml()))

        // 4) one-hop iframes -> try known extractors first, then fallback to scraping
        for (iframe in doc.select("iframe[src]")) {
            val src = iframe.absUrl("src")
            if (src.isBlank()) continue

            // Preferred: let Cloudstream's extractor system handle known hosts
            val ok = loadExtractor(src, pageUrl, subtitleCallback, callback)
            if (ok) {
                found = true
                continue
            }

            // Fallback: fetch the iframe page and repeat 1..3
            val iDoc = runCatching { app.get(src, referer = pageUrl).document }.getOrNull() ?: continue
            pageUrl = src // update referer for nested pages

            addAll(iDoc.select("video[src], video > source[src]").mapNotNull { it.absUrl("src") })
            addAll(iDoc.select("meta[property=og:video][content]").mapNotNull { it.attr("abs:content") })
            addAll(extractMediaUrls(iDoc.outerHtml()))
        }

        return found
    }
}
