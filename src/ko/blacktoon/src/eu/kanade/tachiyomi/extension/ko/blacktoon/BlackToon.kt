package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.injectLazy
import kotlin.math.min
import kotlin.random.Random

class BlackToon : HttpSource() {

    override val name = "블랙툰"

    override val lang = "ko"

    private var currentBaseUrlHost = ""

    // override val baseUrl = "https://blacktoon.me"
    override val baseUrl = "https://blacktoon410.com"

    // private val cdnUrl = "https://blacktoonimg.com/"
    private val cdnUrl = "https://webimg7.com/"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        if (currentBaseUrlHost.isBlank()) {
            noRedirectClient.newCall(GET(baseUrl, headers)).execute().use {
                currentBaseUrlHost = it.headers["location"]?.toHttpUrlOrNull()?.host
                    ?: throw IOException("unable to get updated url")
            }
        }

        val request = chain.request().newBuilder().apply {
            if (chain.request().url.toString().startsWith(baseUrl)) {
                url(
                    chain.request().url.newBuilder()
                        .host(currentBaseUrlHost)
                        .build(),
                )
            }
            header("Referer", "https://$currentBaseUrlHost/")
            header("Origin", "https://$currentBaseUrlHost")
        }.build()

        return@addInterceptor chain.proceed(request)
    }.build()

    private val noRedirectClient = network.cloudflareClient.newBuilder()
        .followRedirects(false)
        .build()

    private val json by injectLazy<Json>()

    private val db by lazy {
        val doc = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        doc.select("script[src*=data/webtoon]").flatMap { scriptEl ->
            var listIdx: Int
            client.newCall(GET(scriptEl.absUrl("src"), headers))
                .execute().body.string()
                .also {
                    listIdx = it.substringBefore(" = ")
                        .substringAfter("data")
                        .toInt()
                }
                .substringAfter(" = ")
                .removeSuffix(";")
                .let { json.decodeFromString<List<SeriesItem>>(it) }
                .onEach { it.listIndex = listIdx }
        }
    }

    private fun List<SeriesItem>.getPageChunk(page: Int): MangasPage {
        return MangasPage(
            mangas = subList((page - 1) * 24, min(page * 24, size))
                .map { it.toSManga(cdnUrl) },
            hasNextPage = (page + 1) * 24 <= size,
        )
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.just(
            db.sortedByDescending { it.hot }.getPageChunk(page),
        )
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.just(
            db.sortedByDescending { it.updatedAt }.getPageChunk(page),
        )
    }
