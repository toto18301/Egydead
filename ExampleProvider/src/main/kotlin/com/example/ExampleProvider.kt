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

    // ---- Small element helper ----
    private fun Element.absPoster(): String? =
        selectFirst("img[src]")?.attr("abs:src")

    // ---- Text-based category guard (Arabic + generic) ----
    private fun isCategoryText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim().lowercase()

        // words often used on tiles that are categories, not titles
        val hints = listOf(
            "مسلسلات", "افلام", "أفلام", "كرتون", "انمي", "أنمي",
            "رمضان", "تركية", "تركي", "أجنبية", "اجنبي", "اسيوية", "آسيوية",
            "برامج", "وثائقي"
        )
        val looksShort = t.length <= 24 // category chips are usually short labels
        return looksShort && hints.any { t.contains(it) }
    }

    // ---- URL-based junk filter ----
    private fun isJunkLink(href: String, anchorText: String? = null): Boolean {
        if (!href.startsWith(mainUrl)) return true

        // if text clearly looks like a category label, skip
        if (isCategoryText(anchorText)) return true

        val normalized = href.removeSuffix("/")
        val path = normalized.removePrefix(mainUrl).lowercase()

        if (path.isBlank()) return true

        // simple root sections
        val rootSections = setOf("movies", "series", "anime", "shows")
        if (rootSections.contains(path)) return true

        // obvious non-content paths / navigations
        val badPieces = listOf(
            "category/", "categories/", "tag/", "genre/", "genres",
            "year/", "/page/", "author/", "wp-", "attachment/", "section/",
            "/search", "?s=", "feed/"
        )
        if (badPieces.any { path.startsWith(it) || path.contains(it) }) return true

        // Arabic slug for "قسم" (section)
        if (path.contains("%d9%82%d8%b3%d9%85")) return true

        // guard: if only 1 path segment and it contains category-ish words, skip
        val segs = path.split('/').filter { it.isNotBlank() }
        if (segs.size == 1 && isCategoryText(segs.first())) return true

        return false
    }

    // ---- Slug → title fallback ----
    private fun slugToTitle(href: String): String =
        href.substringAfterLast('/').substringBeforeLast('.')
            .replace('-', ' ')
            .replace(Regex("%[0-9A-Fa-f]{2}")) {
                runCatching { Char(it.value.substring(1).toInt(16)).toString() }.getOrDefault(" ")
            }
            .trim()

    /** Prefer a dedicated watch/server link on a title page.
     *  Avoid trailer/teaser buttons.
     */
    private fun Document.findWatchLink(fallback: String): String {
        val candidates = select(
            // common "watch/play" buttons
            "a[href*=/watch], a[href*=/play], a[href*=/player], " +
                    // Arabic variants on buttons
                    "a:matchesOwn(مشاهدة|مشاهده|شاهد|سيرفر|تشغيل|Play|Watch)"
        )
            .mapNotNull { a -> a.absUrl("href").ifBlank { null } }
            .filterNot { looksLikeTrailer(it) }
            .distinct()

        return candidates.firstOrNull() ?: fallback
    }

    /** Find direct media links inside arbitrary HTML. */
    private fun extractMediaUrls(html: String): List<String> {
        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")

        val rx = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        val fromRegex = rx.findAll(cleaned).map { it.value }.toMutableList()

        val rx2 = Regex(
            """(?:"file"|['"]src['"])\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        fromRegex += rx2.findAll(cleaned).map { it.groupValues[1] }

        return fromRegex
            .filterNot { looksLikeTrailer(it) }
            .distinct()
    }

    /** Heuristic to avoid trailers/teasers/YouTube embeds. */
    private fun looksLikeTrailer(url: String, contextText: String? = null): Boolean {
        val t = url.lowercase()
        val bad = listOf("youtube.com", "youtu.be", "trailer", "teaser", "/promo", "/preview", "tmdb", "imdb")
        if (bad.any { t.contains(it) }) return true
        if (contextText != null && isCategoryText(contextText)) return true
        return false
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

    // ---- Map anchors → SearchResponse safely ----
    private suspend fun anchorToItem(a: Element, parentUrl: String): SearchResponse? {
        val href = a.absUrl("href")
        val text = a.attr("title").ifBlank { a.text() }
        if (href.isBlank() || isJunkLink(href, text)) return null

        val titleGuess = text.ifBlank { slugToTitle(href) }.trim()
        val poster = a.closest("article")?.absPoster()

        // Probe the page to decide movie vs series (fast HEAD/GET)
        val pDoc = runCatching { app.get(href, referer = parentUrl).document }.getOrNull() ?: return null
        val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()

        return if (isSeries) {
            newTvSeriesSearchResponse(titleGuess, href) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(titleGuess, href) { this.posterUrl = poster }
        }
    }

    // ---- Category page fetcher (used for main page rows) ----
    private suspend fun listFromCategory(catPath: String, page: Int, title: String): HomePageList {
        val url = if (page <= 1) "$mainUrl/$catPath/" else "$mainUrl/$catPath/page/$page/"
        val doc = app.get(url, referer = mainUrl).document

        val anchors = buildList<Element> {
            addAll(doc.select("article .entry-title a[href]"))
            addAll(doc.select(".post .entry-title a[href]"))
            addAll(doc.select(".movie-item a[href], .ml-item a[href]"))
        }.distinctBy { it.absUrl("href") }

        val items = anchors.mapNotNull { anchorToItem(it, url) }
        return HomePageList(title, items)
    }

    // ---------- Main page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Define the rows you want to show (categories -> items in each row)
        val rows = listOf(
            "أحدث الأفلام" to "movies",
            "أحدث المسلسلات" to "series"
        )

        // If user paginates a specific row (right arrow), Cloudstream calls again with the row's title in request.name
        if (page > 1) {
            val path = rows.toMap()[request.name] ?: return newHomePageResponse(emptyList(), false)
            val list = listFromCategory(path, page, request.name)
            return newHomePageResponse(listOf(list), hasNext = list.list.isNotEmpty())
        }

        // First page: build all rows
        val lists = rows.map { (title, path) -> listFromCategory(path, 1, title) }
        // 'hasNext' here is per-row handled via the request/name mechanism, so false is fine.
        return newHomePageResponse(lists, hasNext = false)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, referer = mainUrl).document

        // Prefer entry/title anchors; avoid category chips
        val entryAnchors = doc.select(
            "article .entry-title a[href], .post .entry-title a[href], .movie-item a[href], .ml-item a[href]"
        ).ifEmpty {
            // Safe fallback, still avoids generic category links
            doc.select("a[href*=/watch], a[href*=/episode], a[href][title]")
        }

        val links = entryAnchors
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val text = a.attr("title").ifBlank { a.text() }
                if (href.isBlank() || isJunkLink(href, text)) null else href
            }
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
            // Movie: store the *watch* url so loadLinks can go straight there
            val watch = doc.findWatchLink(url)
            newMovieLoadResponse(title, url, TvType.Movie, data = watch) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // ---------- Extract links (servers) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is usually the watch page for movies, or an episode page for series
        val firstDoc = app.get(data, referer = mainUrl).document

        // Prefer a dedicated watch/server page, avoiding trailer links
        var pageUrl = firstDoc.findWatchLink(data)
        var doc = if (pageUrl == data) firstDoc else app.get(pageUrl, referer = data).document

        var found = false

        fun Element.frameSrc(): String? {
            val direct = this.attr("abs:src").ifBlank { null }
            val dataSrc = this.attr("abs:data-src").ifBlank { null }
            return direct ?: dataSrc
        }

        // 1) Try iframes first (actual servers). Skip obvious trailers.
        val frameCandidates = doc.select("iframe[src], iframe[data-src]")
            .mapNotNull { it.frameSrc() }
            .filter { it.isNotBlank() }
            .filterNot { looksLikeTrailer(it) }
            .distinct()

        for (src in frameCandidates) {
            // Try Cloudstream's built-in extractors first
            val ok = loadExtractor(src, pageUrl, subtitleCallback, callback)
            if (ok) found = true
            // If not recognized, try to scrape one level deeper
            if (!ok) {
                val iDoc = runCatching { app.get(src, referer = pageUrl).document }.getOrNull()
                if (iDoc != null) {
                    // nested frames too
                    val nested = iDoc.select("iframe[src], iframe[data-src]")
                        .mapNotNull { it.frameSrc() }
                        .filterNot { looksLikeTrailer(it) }
                        .distinct()
                    for (n in nested) {
                        val ok2 = loadExtractor(n, src, subtitleCallback, callback)
                        if (ok2) found = true
                    }
                    if (!found) {
                        // last resort: direct media from nested doc
                        extractMediaUrls(iDoc.outerHtml()).forEach { pushLink(src, it, callback) }
                        if (extractMediaUrls(iDoc.outerHtml()).isNotEmpty()) found = true
                    }
                }
            }
        }

        // 2) Only if nothing found, try direct <video>/<source> on the page (some sites inline HLS/MP4 for servers)
        if (!found) {
            val directMedia = doc.select("video[src], video > source[src]")
                .mapNotNull { it.attr("abs:src") }
                .filterNot { looksLikeTrailer(it) }
                .distinct()
            for (u in directMedia) {
                found = true
                pushLink(pageUrl, u, callback)
            }
        }

        // 3) Absolute last fallback: scan HTML for media urls (skip trailers)
        if (!found) {
            val scraped = extractMediaUrls(doc.outerHtml())
            for (u in scraped) {
                found = true
                pushLink(pageUrl, u, callback)
            }
        }

        return found
    }
}

