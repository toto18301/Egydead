package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ExampleProvider : MainAPI() {

    override var mainUrl = "https://tv2.egydead.live"
    override var name = "EgyDead"
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    // -------------------- helpers: text / posters --------------------

    private fun Element.absPoster(): String? =
        selectFirst("img[src], img[data-src], img[data-lazy-src]")?.let {
            it.attr("abs:src").ifBlank {
                it.attr("abs:data-src").ifBlank { it.attr("abs:data-lazy-src") }
            }
        }

    private fun isCategoryText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim().lowercase()
        val hints = listOf(
            "مسلسلات", "افلام", "أفلام", "فيلم", "كرتون", "انمي", "أنمي",
            "رمضان", "تركية", "تركي", "أجنبية", "اجنبي", "اسيوية", "آسيوية",
            "برامج", "وثائقي", "سلاسل", "مميز", "مميزة", "مفضلة"
        )
        val looksShort = t.length <= 24
        return looksShort && hints.any { t.contains(it) }
    }

    private fun isJunkLink(href: String, anchorText: String? = null): Boolean {
        if (!href.startsWith("http")) return true
        if (!href.startsWith(mainUrl)) return true
        if (isCategoryText(anchorText)) return true

        val normalized = href.removeSuffix("/")
        val path = normalized.removePrefix(mainUrl).lowercase()
        if (path.isBlank()) return true

        val rootSections = setOf("movies", "series", "anime", "shows")
        if (rootSections.contains(path)) return true

        val badPieces = listOf(
            "category/", "categories/", "tag/", "genre/", "genres",
            "year/", "/page/", "author/", "wp-", "attachment/", "section/",
            "/search", "?s=", "feed/"
        )
        if (badPieces.any { path.startsWith(it) || path.contains(it) }) return true

        // slug for "قسم" (section)
        if (path.contains("%d9%82%d8%b3%d9%85")) return true

        val segs = path.split('/').filter { it.isNotBlank() }
        if (segs.size == 1 && isCategoryText(segs.first())) return true

        return false
    }

    private fun slugToTitle(href: String): String =
        href.substringAfterLast('/').substringBeforeLast('.')
            .replace('-', ' ')
            .replace(Regex("%[0-9A-Fa-f]{2}")) {
                runCatching { Char(it.value.substring(1).toInt(16)).toString() }.getOrDefault(" ")
            }
            .trim()

    // -------------------- find "watch" link on a title page --------------------

    private fun Document.findWatchLink(fallback: String): String {
        val candidates = select(
            // “watch / server / play” buttons and links
            "a[href*=/watch], a[href*=/play], a[href*=/player], a[href*=/server], " +
            "a:matchesOwn(مشاهدة|مشاهده|شاهد|سيرفر|تشغيل|Play|Watch|Server), " +
            "button:matchesOwn(مشاهدة|تشغيل|Play)"
        ).mapNotNull { a -> a.absUrl("href").ifBlank { null } }
            .filterNot { looksLikeTrailer(it) }
            .distinct()

        return candidates.firstOrNull() ?: fallback
    }

    // -------------------- trailer filter (keep strict to YouTube only) --------------------

    private fun looksLikeTrailer(url: String, contextText: String? = null): Boolean {
        val t = url.lowercase()
        if (t.contains("youtube.com") || t.contains("youtu.be")) return true
        if (contextText != null) {
            val c = contextText.lowercase()
            if (c.contains("trailer") || c.contains("teaser") || c.contains("اعلان")) return true
        }
        return false
    }

    // -------------------- media url scraping --------------------

    private fun extractMediaUrls(html: String): List<String> {
        val cleaned = html.replace("\\/", "/").replace("\\u002F", "/")

        val rx = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        val fromRegex = rx.findAll(cleaned).map { it.value }.toMutableList()

        val rx2 = Regex(
            """(?:"file"|['"]src['"])\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        fromRegex += rx2.findAll(cleaned).map { it.groupValues[1] }

        return fromRegex.distinct()
    }

    private fun maybeDecodeBase64(s: String): String? {
        val trimmed = s.trim()
        val base64ish = trimmed.matches(Regex("^[A-Za-z0-9+/=\\r\\n]+$")) && trimmed.length % 4 == 0
        if (!base64ish) return null
        // Try java.util then android.util as fallback
        return runCatching {
            val jdk = runCatching { String(java.util.Base64.getDecoder().decode(trimmed)) }.getOrNull()
            val out = jdk ?: String(android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT))
            if (out.startsWith("http")) out else null
        }.getOrNull()
    }

    private suspend fun pushLink(
        pageUrl: String,
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (mediaUrl.startsWith("//")) "https:$mediaUrl" else mediaUrl
        val isM3u8 = url.contains(".m3u8", true)
        val link = newExtractorLink(
            source = "EgyDead",
            name = if (isM3u8) "EgyDead HLS" else "EgyDead",
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            referer = pageUrl
            quality = Qualities.Unknown.value
        }
        callback(link)
    }

    // -------------------- map an anchor to a SearchResponse --------------------

    private suspend fun anchorToItem(a: Element, parentUrl: String): SearchResponse? {
        val href = a.absUrl("href")
        val text = a.attr("title").ifBlank { a.text() }
        if (href.isBlank() || isJunkLink(href, text)) return null

        val titleGuess = text.ifBlank { slugToTitle(href) }.trim()
        val poster = a.closest("article")?.absPoster() ?: a.absPoster()

        // Probe the page to decide type
        val pDoc = runCatching { app.get(href, referer = parentUrl).document }.getOrNull() ?: return null
        val isSeries = pDoc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+)").isNotEmpty()

        return if (isSeries) {
            newTvSeriesSearchResponse(titleGuess, href) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(titleGuess, href) { this.posterUrl = poster }
        }
    }

    // -------------------- section discovery for the Home page --------------------

    /** Return rows from the homepage by pairing each visible heading with its items. */
    private suspend fun discoverHomeSections(doc: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        // 1) Find headings in DOM order.
        val headings = doc.select(
            "h2, h3, .section-title, .widget-title, .block-title, .home-title, .title, .cat-title"
        ).filter { it.text().isNotBlank() }

        for (h in headings) {
            val title = h.text().trim()

            // Find a container near the heading that holds the cards
            val container = sequenceOf(
                h.parent(),
                h.parent()?.parent(),
                h.parent()?.parent()?.parent()
            ).firstOrNull { parent ->
                parent != null && parent.select(
                    "article .entry-title a[href], .post .entry-title a[href], " +
                    ".movie-item a[href], .ml-item a[href], .ml-item .title a[href], .item a[href]"
                ).size >= 3
            } ?: continue

            // Collect anchors for items inside this section
            val anchors = buildList<Element> {
                addAll(container.select("article .entry-title a[href]"))
                addAll(container.select(".post .entry-title a[href]"))
                addAll(container.select(".movie-item a[href], .ml-item a[href], .ml-item .title a[href]"))
                addAll(container.select(".owl-carousel .item a[href]"))
            }.distinctBy { it.absUrl("href") }

            val items = mutableListOf<SearchResponse>()
            for (a in anchors) {
                val item = anchorToItem(a, doc.location())
                if (item != null) items += item
            }

            if (items.size >= 3) {
                rows += HomePageList(title, items)
            }
        }

        // 2) Fallback rows if discovery found nothing
        if (rows.isEmpty()) {
            rows += listFromCategoryPath("movies", "أفلام")
            rows += listFromCategoryPath("series", "مسلسلات")
        }

        return rows
    }

    private suspend fun listFromCategoryPath(path: String, rowTitle: String): HomePageList {
        val url = "$mainUrl/$path/"
        val doc = app.get(url, referer = mainUrl).document
        val anchors = buildList<Element> {
            addAll(doc.select("article .entry-title a[href]"))
            addAll(doc.select(".post .entry-title a[href]"))
            addAll(doc.select(".movie-item a[href], .ml-item a[href], .ml-item .title a[href]"))
        }.distinctBy { it.absUrl("href") }

        val items = mutableListOf<SearchResponse>()
        for (a in anchors) {
            val item = anchorToItem(a, url)
            if (item != null) items += item
        }
        return HomePageList(rowTitle, items)
    }

    // -------------------- Main page --------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, referer = mainUrl).document
        val lists = discoverHomeSections(doc)
        return newHomePageResponse(lists, hasNext = false)
    }

    // -------------------- Search --------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, referer = mainUrl).document

        val entryAnchors = doc.select(
            "article .entry-title a[href], .post .entry-title a[href], .movie-item a[href], .ml-item a[href], .ml-item .title a[href]"
        ).ifEmpty {
            doc.select("a[href*=/watch], a[href*=/episode], a[href][title]")
        }

        val links = entryAnchors
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val text = a.attr("title").ifBlank { a.text() }
                if (href.isBlank() || isJunkLink(href, text)) null else href
            }
            .distinct()
            .take(50)

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

    // -------------------- Load title page --------------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1, .entry-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.trim() ?: return null

        val poster = doc.selectFirst("meta[property=og:image], .post img, .entry-content img")
            ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }

        val plot = doc.selectFirst(".entry-content p, .post p, .story p")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val eps = doc.select("a:matchesOwn(الحلقة|حلقه|Episode|Ep\\s*\\d+), li a:matchesOwn(الحلقة|Episode)")
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
            // For movies, stash the best watch/server page in 'data'
            val watch = doc.findWatchLink(url)
            newMovieLoadResponse(title, url, TvType.Movie, data = watch) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // -------------------- Collect server candidates from a document --------------------

    private fun collectServerUrls(doc: Document): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()

        // 1) iframes (direct and data-src)
        out += doc.select("iframe[src], iframe[data-src]").mapNotNull { f ->
            val text = f.text().ifBlank { f.attr("title") }
            val src = f.attr("abs:src").ifBlank { f.attr("abs:data-src") }
            if (src.isNotBlank()) (src to text) else null
        }

        // 2) elements with common data attributes used by server buttons
        out += doc.select("[data-url], [data-src], [data-iframe], [data-link], [data-href], [data-video], [data-file]").mapNotNull { e ->
            val label = e.text()
            val raw = e.attr("abs:data-url")
                .ifBlank { e.attr("abs:data-src") }
                .ifBlank { e.attr("abs:data-iframe") }
                .ifBlank { e.attr("abs:data-link") }
                .ifBlank { e.attr("abs:data-href") }
                .ifBlank { e.attr("abs:data-video") }
                .ifBlank { e.attr("abs:data-file") }
            if (raw.isBlank()) null
            else {
                val decoded = maybeDecodeBase64(raw) ?: raw
                (decoded to label)
            }
        }

        // 3) anchors that look like player/servers
        val hostHint = Regex("(embed|player|watch|play|server|download|stream|vid|mcloud|moon|uqload|ok\\.ru|streamtape|voe|dood|doodstream|filemoon|vudeo|mixdrop|vidhide|fast)", RegexOption.IGNORE_CASE)
        out += doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.isBlank() || !hostHint.containsMatchIn(href)) null else (href to a.text())
        }

        return out.distinctBy { it.first }
    }

    // -------------------- Load links --------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is a watch page for movies, or an episode page for series
        val firstDoc = app.get(data, referer = mainUrl).document

        // Prefer a dedicated watch/server page
        var pageUrl = firstDoc.findWatchLink(data)
        var doc = if (pageUrl == data) firstDoc else app.get(pageUrl, referer = data).document

        var found = false

        suspend fun tryPushDirect(urls: List<String>) {
            for (u in urls) {
                if (u.isBlank()) continue
                if (looksLikeTrailer(u)) continue
                found = true
                pushLink(pageUrl, u, callback)
            }
        }

        // A) Try all server candidates with Cloudstream's extractors first
        val serverCandidates = collectServerUrls(doc).filterNot { looksLikeTrailer(it.first, it.second) }
        for ((srv, lbl) in serverCandidates) {
            val ok = loadExtractor(srv, pageUrl, subtitleCallback, callback)
            if (ok) found = true
        }

        // B) If nothing yet, chase one more level deep for each candidate
        if (!found) {
            for ((srv, _) in serverCandidates) {
                val iDoc = runCatching { app.get(srv, referer = pageUrl).document }.getOrNull() ?: continue
                val nested = collectServerUrls(iDoc).filterNot { looksLikeTrailer(it.first, it.second) }
                for ((n, _) in nested) {
                    val ok2 = loadExtractor(n, srv, subtitleCallback, callback)
                    if (ok2) found = true
                }
                if (!found) {
                    // last resort on nested page: direct urls from HTML
                    tryPushDirect(extractMediaUrls(iDoc.outerHtml()))
                }
            }
        }

        // C) Try direct <video>/<source> on the current page
        if (!found) {
            val directMedia = doc.select("video[src], video > source[src]")
                .mapNotNull { it.attr("abs:src") }
                .distinct()
            tryPushDirect(directMedia)
        }

        // D) Absolute last fallback: scan HTML for media urls
        if (!found) {
            tryPushDirect(extractMediaUrls(doc.outerHtml()))
        }

        return found
    }
}
