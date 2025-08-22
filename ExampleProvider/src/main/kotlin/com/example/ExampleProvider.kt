package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils

/**
 * Minimal provider to get the build GREEN.
 * Once it compiles, we’ll add the real tv2.egydead.live logic.
 */
class ExampleProvider : MainAPI() {
    override var mainUrl = "https://tv2.egydead.live"
    override var name = "EgyDead (Stub)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // empty homepage to prove build works
        return newHomePageResponse(listOf())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // no-op search (we’ll implement after build succeeds)
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // no-op load for now
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // no links yet
        return false
    }
}
