package com.phatbn11

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class Vlxx : MainAPI() {

    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.sex"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    /* ================= MAIN PAGE ================= */

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("div.data h3 a")?.text() ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document

        val list = doc.select("div.items article")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, list)
    }

    /* ================= SEARCH ================= */

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1)
            "$mainUrl/?s=$query"
        else
            "$mainUrl/page/$page/?s=$query"

        val doc = app.get(url).document

        val results = doc.select("div.items article")
            .mapNotNull { it.toMainPageResult() }

        val hasNext = doc.selectFirst("a.next") != null

        return newSearchResponseList(results, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).results
    }

    /* ================= LOAD ================= */

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("div.data h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(doc.selectFirst("div.poster img")?.attr("src"))
        val banner = fixUrlNull(doc.selectFirst("div.backdrop img")?.attr("src")) ?: poster
        val plot = doc.selectFirst("#info .wp-content p")?.text()?.trim()

        val actors = doc.select("#cast .persons .person").mapNotNull {
            val name = it.selectFirst("meta[itemprop=name]")?.attr("content")
                ?: it.selectFirst(".name a")?.text()
            val img = fixUrlNull(it.selectFirst("img")?.attr("src"))
            if (name.isNullOrBlank()) null else Actor(name, img)
        }

        val episodes = doc.select("#seasons .se-c .se-a ul li").mapNotNull {
            val epUrl = it.selectFirst(".episodiotitle a")?.attr("href") ?: return@mapNotNull null
            val epNum = it.selectFirst(".num")?.text()?.toIntOrNull()
            val epName = it.selectFirst(".episodiotitle a")?.text()

            newEpisode(fixUrl(epUrl)) {
                name = epName
                episode = epNum
            }
        }

        val watchBtn = doc.selectFirst("div.sgeneros a")?.attr("href")

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.NSFW,
                episodes = episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = banner
                this.plot = plot
                addActors(actors)
            }
        } else {
            val playUrl = when {
                watchBtn != null -> fixUrl(watchBtn)
                else -> {
                    val slug = url.trimEnd('/').substringAfterLast("/")
                    "$mainUrl/watch-$slug?sv=1&ep=1"
                }
            }

            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.NSFW,
                dataUrl = playUrl
            ) {
                posterUrl = poster
                backgroundPosterUrl = banner
                this.plot = plot
                addActors(actors)
            }
        }
    }

    /* ================= LINKS ================= */

    @SuppressLint("SetJavaScriptEnabled", "PrivateApi")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val context = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context ?: return false

        val m3u8 = suspendCancellableCoroutine<String?> { cont ->
            Handler(Looper.getMainLooper()).post {

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                }

                webView.webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && cont.isActive) {
                            cont.resume(reqUrl)
                            view.post { view.destroy() }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            """
                            setInterval(() => {
                                if (typeof jwplayer === 'function') jwplayer().play();
                                document.querySelector(
                                    '.jw-display-icon-display, .videoapi-btn'
                                )?.click();
                            }, 1000);
                            """.trimIndent(),
                            null
                        )
                    }
                }

                webView.loadUrl(data)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (cont.isActive) {
                        cont.resume(null)
                        webView.destroy()
                    }
                }, 30_000)
            }
        }

        return m3u8?.let {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = it,
                    quality = Qualities.P720.value,
                    isM3u8 = true,
                    headers = mapOf("Referer" to mainUrl)
                )
            )
            true
        } ?: false
    }
}
