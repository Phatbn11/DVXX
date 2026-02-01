package com.jacekun

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
        val resp = app.get(url, referer = referer, interceptor = interceptor)
        Log.i(DEV, "Page Response Code => ${resp.code}")
        return resp
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getPage(mainUrl, mainUrl).document
        val all = ArrayList<HomePageList>()
        
        Log.i(DEV, "Fetching homepage videos...")
        
        // Try multiple selectors for homepage
        var elements = document.select("div#video-list > div.video-item")
            .mapNotNull {
                val firstA = it.selectFirst("a")
                val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst("div.video-name")?.text() 
                    ?: it.selectFirst("a")?.attr("title") 
                    ?: it.text()
                Log.i(DEV, "Homepage item => $name | $link")
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }

        // If first selector fails, try alternative
        if (elements.isEmpty()) {
            Log.i(DEV, "First selector empty, trying alternative...")
            elements = document.select("div.video-item, .video-block, .item")
                .mapNotNull {
                    val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                    val img = it.selectFirst("img")?.attr("data-original") 
                        ?: it.selectFirst("img")?.attr("src")
                    val name = it.selectFirst(".video-name, .title, h3, h4")?.text() 
                        ?: it.selectFirst("a")?.attr("title") 
                        ?: "Video"
                    Log.i(DEV, "Alt homepage item => $name | $link")
                    newMovieSearchResponse(
                        name = name,
                        url = link,
                        type = globaltvType,
                    ) {
                        this.posterUrl = img
                    }
                }.distinctBy { it.url }
        }

        if (elements.isNotEmpty()) {
            all.add(HomePageList("Homepage", elements))
        }
        
        Log.i(DEV, "Total homepage items: ${elements.size}")
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/${query}/"
        Log.i(DEV, "Search URL: $searchUrl")
        
        val document = getPage(searchUrl, mainUrl).document
        
        // Try multiple selectors
        var results = document.select(".video-list .video-item, #video-list .video-item")
            .mapNotNull {
                val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst(".video-name")?.text() 
                    ?: it.selectFirst("a")?.attr("title") 
                    ?: ""
                Log.i(DEV, "Search result => $name | $link")
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                    this.posterHeaders = interceptor.getCookieHeaders(searchUrl).toMap()
                }
            }.distinctBy { it.url }

        // Try alternative selector if empty
        if (results.isEmpty()) {
            Log.i(DEV, "First search selector empty, trying alternative...")
            results = document.select("div.video-item, .video-block, .item")
                .mapNotNull {
                    val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                    val img = it.selectFirst("img")?.attr("data-original") 
                        ?: it.selectFirst("img")?.attr("src")
                    val name = it.selectFirst(".video-name, .title, h3")?.text() 
                        ?: it.selectFirst("a")?.attr("title") 
                        ?: ""
                    Log.i(DEV, "Alt search result => $name | $link")
                    newMovieSearchResponse(
                        name = name,
                        url = link,
                        type = globaltvType,
                    ) {
                        this.posterUrl = img
                        this.posterHeaders = interceptor.getCookieHeaders(searchUrl).toMap()
                    }
                }.distinctBy { it.url }
        }
        
        Log.i(DEV, "Total search results: ${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url, url).document
        Log.i(DEV, "Loading page: $url")

        val container = doc.selectFirst("div#container, .container, .content")
        val title = container?.selectFirst("h2, h1, .title")?.text() ?: "No Title"
        val descript = container?.selectFirst("div.video-description, .description, p")?.text()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster")
        
        Log.i(DEV, "Loaded: $title")
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            type = globaltvType,
        ) {
            this.posterUrl = poster
            this.plot = descript
            this.posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.i(DEV, "Loading links for: $data")
            
            val pathSplits = data.split("/").filter { it.isNotEmpty() }
            val id = pathSplits.getOrNull(pathSplits.size - 1) ?: pathSplits.last()
            Log.i(DEV, "Extracted ID: $id")
            
            val cookies = interceptor.getCookieHeaders(data).toMap()
            
            val res = app.post(
                "$mainUrl/ajax.php",
                headers = cookies + mapOf("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                ),
                referer = data
            ).text
            
            Log.i(DEV, "Ajax response length: ${res.length}")
            Log.i(DEV, "Ajax response preview: ${res.take(500)}")

            // Try to find video URL in response with multiple methods
            var foundLinks = false
            
            // Method 1: Parse sources JSON
            val patterns = listOf(
                "sources:" to "}]",
                "sources: " to "}]",
                "\"sources\":" to "}]",
                "'sources':" to "}]"
            )
            
            for ((key, end) in patterns) {
                val json = getParamFromJS(res, key, end)
                if (json != null) {
                    Log.i(DEV, "Found JSON with pattern '$key': $json")
                    tryParseJson<List<Sources?>>(json)?.forEach { vidlink ->
                        vidlink?.file?.let { file ->
                            Log.i(DEV, "Parsed link: $file (${vidlink.label})")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = file,
                                    type = if (file.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ).apply {
                                    this.referer = data
                                    this.quality = getQualityFromName(vidlink.label)
                                }
                            )
                            foundLinks = true
                        }
                    }
                    if (foundLinks) break
                }
            }
            
            // Method 2: Direct URL extraction
            if (!foundLinks) {
                Log.i(DEV, "JSON parse failed, trying direct URL extraction")
                val urlPattern = Regex("(https?://[^\"'\\s]+\\.(m3u8|mp4)[^\"'\\s]*)")
                urlPattern.findAll(res).forEach { match ->
                    val url = match.groupValues[1]
                    Log.i(DEV, "Found direct URL: $url")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = url,
                            type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ).apply {
                            this.referer = data
                        }
                    )
                    foundLinks = true
                }
            }
            
            // Method 3: Check for embed player in original page
            if (!foundLinks) {
                Log.i(DEV, "Trying to find embed player in page")
                val doc = getPage(data, data).document
                val iframeSrc = doc.selectFirst("iframe[src*=player], iframe[src*=embed]")?.attr("src")
                if (iframeSrc != null) {
                    Log.i(DEV, "Found iframe: $iframeSrc")
                    val iframeDoc = getPage(fixUrl(iframeSrc), data).document
                    val scriptText = iframeDoc.select("script").joinToString("\n") { it.html() }
                    
                    urlPattern.findAll(scriptText).forEach { match ->
                        val url = match.groupValues[1]
                        Log.i(DEV, "Found URL in iframe: $url")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = url,
                                type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ).apply {
                                this.referer = data
                            }
                        )
                        foundLinks = true
                    }
                }
            }
            
            if (!foundLinks) {
                Log.e(DEV, "No links found with any method")
            }
            
            return foundLinks
            
        } catch (e: Exception) {
            Log.e(DEV, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            logError(e)
            return false
        }
    }

    private fun getParamFromJS(str: String, key: String, keyEnd: String): String? {
        try {
            val firstIndex = str.indexOf(key)
            if (firstIndex == -1) return null
            
            val startIndex = firstIndex + key.length
            val temp = str.substring(startIndex)
            val lastIndex = temp.indexOf(keyEnd)
            if (lastIndex == -1) return null
            
            val jsonConfig = temp.substring(0, lastIndex + keyEnd.length)

            return jsonConfig
                .replace("\\r", "")
                .replace("\\t", "")
                .replace("\\\"", "\"")
                .replace("\\\\\\/", "/")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim()
            
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
}
