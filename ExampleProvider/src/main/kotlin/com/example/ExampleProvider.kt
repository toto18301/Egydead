package com.example

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse

/**
 * Minimal provider just to get the build green.
 * We will add real tv2.egydead.live logic once CI succeeds.
 */
class ExampleProvider : MainAPI() {
    override var mainUrl = "https://tv2.egydead.live"
    override var name = "EgyDead (Stub)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(listOf())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }
}
