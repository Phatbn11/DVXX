package com.DariusBETA

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse

class Vlxx : MainAPI() {
    private val DEV = "DevDebug"
    private val globaltvType = TvType.NSFW

    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.ms"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val interceptor = CloudflareKiller()

    private suspend fun getPage(url: String, referer: String): NiceResponse {
        return app.get(url, referer = referer, interceptor = interceptor)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getPage(mainUrl, mainUrl).document
        val all = ArrayList<HomePageList>()
        
        val elements = document.select("div#video-list > div.video-item")
            .mapNotNull {
                val firstA = it.selectFirst("a")
                val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst("div.video-name")?.text() ?: it.text()
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }

        if (elements.isNotEmpty()) {
            all.add(HomePageList("Homepage", elements))
        }
        
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/${query}/"
        val document = getPage(searchUrl, mainUrl).document
        
        return document.select("div.video-item")
            .mapNotNull {
                val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst(".video-name")?.text() ?: ""
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.replace(" ", "")
        val doc = getPage(cleanUrl, cleanUrl).document
        val container = doc.selectFirst("div#container")
        val title = container?.selectFirst("h2")?.text() ?: "No Title"
        val descript = container?.selectFirst("div.video-description")?.text()
        
        return newMovieLoadResponse(
            name = title,
            url = cleanUrl,
            dataUrl = cleanUrl,
            type = globaltvType,
        ) {
            this.plot = descript
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val cleanData = data.replace(" ", "").trim()
        Log.d(DEV, "=== START loadLinks ===")
        Log.d(DEV, "URL: $cleanData")
        
        // Récupérer la page
        val response = app.get(cleanData, referer = mainUrl, interceptor = interceptor)
        val html = response.text
        
        Log.d(DEV, "Page loaded, length: ${html.length}")
        
        var foundLinks = 0
        
        // Pattern 1: Chercher "file" suivi d'une URL
        val filePattern1 = Regex("""file["'\s]*:["'\s]*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        filePattern1.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                Log.d(DEV, "Pattern1 found: $url")
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ).apply {
                            this.referer = cleanData
                        }
                    )
                    foundLinks++
                } catch (e: Exception) {
                    Log.e(DEV, "Error adding link", e)
                }
            }
        }
        
        // Pattern 2: Toutes les URLs m3u8
        val m3u8Pattern = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
        m3u8Pattern.findAll(html).forEach { match ->
            val url = match.groupValues[1].replace("\\", "")
            Log.d(DEV, "M3U8 found: $url")
            try {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ).apply {
                        this.referer = cleanData
                    }
                )
                foundLinks++
            } catch (e: Exception) {
                Log.e(DEV, "Error adding m3u8", e)
            }
        }
        
        // Pattern 3: Toutes les URLs mp4
        val mp4Pattern = Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""")
        mp4Pattern.findAll(html).forEach { match ->
            val url = match.groupValues[1].replace("\\", "")
            Log.d(DEV, "MP4 found: $url")
            try {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ).apply {
                        this.referer = cleanData
                    }
                )
                foundLinks++
            } catch (e: Exception) {
                Log.e(DEV, "Error adding mp4", e)
            }
        }
        
        // Pattern 4: Sources JSON array
        val sourcesPattern = Regex("""sources\s*:\s*(\[[^\]]+\])""", RegexOption.IGNORE_CASE)
        sourcesPattern.find(html)?.let { match ->
            val sourcesText = match.groupValues[1]
            Log.d(DEV, "Found sources array: $sourcesText")
            
            // Extraire les URLs du JSON
            val urlPattern = Regex("""["']?(https?://[^"']+)["']?""")
            urlPattern.findAll(sourcesText).forEach { urlMatch ->
                val url = urlMatch.groupValues[1]
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    Log.d(DEV, "JSON source: $url")
                    try {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = url,
                                type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ).apply {
                                this.referer = cleanData
                            }
                        )
                        foundLinks++
                    } catch (e: Exception) {
                        Log.e(DEV, "Error adding JSON source", e)
                    }
                }
            }
        }
        
        Log.d(DEV, "=== END loadLinks: found $foundLinks links ===")
        
        // Si aucun lien trouvé, logger un extrait du HTML pour debug
        if (foundLinks == 0) {
            Log.d(DEV, "HTML snippet (first 500 chars):")
            Log.d(DEV, html.take(500))
            Log.d(DEV, "HTML snippet (searching for 'video', 'player', 'source'):")
            val keywords = listOf("video", "player", "source", "file", "m3u8", "mp4")
            keywords.forEach { keyword ->
                val index = html.indexOf(keyword, ignoreCase = true)
                if (index >= 0) {
                    val start = maxOf(0, index - 100)
                    val end = minOf(html.length, index + 200)
                    Log.d(DEV, "Around '$keyword': ${html.substring(start, end)}")
                }
            }
        }
        
        return foundLinks > 0
        
    } catch (e: Exception) {
        Log.e(DEV, "Exception in loadLinks", e)
        e.printStackTrace()
        logError(e)
        false
    }
}
    data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
}

