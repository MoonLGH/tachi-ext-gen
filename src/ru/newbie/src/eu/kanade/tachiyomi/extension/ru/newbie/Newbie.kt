package eu.kanade.tachiyomi.extension.ru.newbie

import BookDto
import BranchesDto
import LibraryDto
import MangaDetDto
import PageDto
import PageWrapperDto
import SeriesWrapperDto
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Newbie : HttpSource() {
    override val name = "NewManga(Newbie)"

    override val id: Long = 8033757373676218584

    override val baseUrl = "https://newmanga.org"

    override val lang = "ru"

    override val supportsLatest = true

    private var branches = mutableMapOf<String, List<BranchesDto>>()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Tachiyomi")
        .add("Referer", baseUrl)

    private fun imageContentTypeIntercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.queryParameter("slice").isNullOrEmpty()) {
            return chain.proceed(chain.request())
        }

        val response = chain.proceed(chain.request())
        val image = response.body?.byteString()?.toResponseBody("image/*".toMediaType())
        return response.newBuilder().body(image).build()
    }

    override val client: OkHttpClient =
        network.client.newBuilder()
            .addInterceptor { imageContentTypeIntercept(it) }
            .build()

    private val count = 30

    override fun popularMangaRequest(page: Int) = GET("$API_URL/projects/popular?scale=month&size=$count&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$API_URL/projects/updates?only_bookmarks=false&size=$count&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage {
        val page = json.decodeFromString<PageWrapperDto<LibraryDto>>(response.body!!.string())
        val mangas = page.items.map {
            it.toSManga()
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun LibraryDto.toSManga(): SManga {
        val o = this
        return SManga.create().apply {
            // Do not change the title name to ensure work with a multilingual catalog!
            title = o.title.en
            url = "$id"
            thumbnail_url = if (image.srcset.large.isNotEmpty()) {
                "$IMAGE_URL/${image.srcset.large}"
            } else "" +
                "$IMAGE_URL/${image.srcset.small}"
        }
    }

    private val simpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) }

    private fun parseDate(date: String?): Long {
        date ?: return 0L
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$API_URL/projects/catalog?size=$count&page=$page".toHttpUrlOrNull()!!.newBuilder()
        if (query.isNotEmpty()) {
            url = "$API_URL/projects/search?size=$count&page=$page".toHttpUrlOrNull()!!.newBuilder()
            url.addQueryParameter("query", query)
        }
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    val ord = arrayOf("rating", "fresh")[filter.state!!.index]
                    url.addQueryParameter("sorting", ord)
                }
                is TypeList -> filter.state.forEach { type ->
                    if (type.state) {
                        url.addQueryParameter("types", type.id)
                    }
                }
                is StatusList -> filter.state.forEach { status ->
                    if (status.state) {
                        url.addQueryParameter("statuses", status.id)
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state) {
                        url.addQueryParameter("genres", genre.id)
                    }
                }
            }
        }
        return GET(url.toString(), headers)
    }

    private fun parseStatus(status: String): Int {
        return when (status) {
            "completed" -> SManga.COMPLETED
            "on_going" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseType(type: String): String {
        return when (type) {
            "manga" -> "??????????"
            "manhwa" -> "????????????"
            "manhya" -> "??????????????"
            "single" -> "??????????"
            "comics" -> "????????????"
            "russian" -> "??????????????"
            else -> type
        }
    }
    private fun parseAge(adult: String): String {
        return when (adult) {
            "" -> "0+"
            else -> "$adult+"
        }
    }

    private fun MangaDetDto.toSManga(): SManga {
        val ratingValue = DecimalFormat("#,###.##").format(rating * 2).replace(",", ".").toFloat()
        val ratingStar = when {
            ratingValue > 9.5 -> "???????????????"
            ratingValue > 8.5 -> "???????????????"
            ratingValue > 7.5 -> "???????????????"
            ratingValue > 6.5 -> "???????????????"
            ratingValue > 5.5 -> "???????????????"
            ratingValue > 4.5 -> "???????????????"
            ratingValue > 3.5 -> "???????????????"
            ratingValue > 2.5 -> "???????????????"
            ratingValue > 1.5 -> "???????????????"
            ratingValue > 0.5 -> "???????????????"
            else -> "???????????????"
        }
        val o = this
        return SManga.create().apply {
            // Do not change the title name to ensure work with a multilingual catalog!
            title = o.title.en
            url = "$id"
            thumbnail_url = "$IMAGE_URL/${image.srcset.large}"
            author = o.author?.name
            artist = o.artist?.name
            description = o.title.ru + "\n" + ratingStar + " " + ratingValue + "\n" + Jsoup.parse(o.description).text()
            genre = parseType(type) + ", " + adult?.let { parseAge(it) } + ", " + genres.joinToString { it.title.ru.capitalize() }
            status = parseStatus(o.status)
        }
    }

    private fun titleDetailsRequest(manga: SManga): Request {
        return GET(API_URL + "/projects/" + manga.url, headers)
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(titleDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + "/p/" + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val series = json.decodeFromString<MangaDetDto>(response.body!!.string())
        branches[series.title.en] = series.branches
        return series.toSManga()
    }

    @SuppressLint("DefaultLocale")
    private fun chapterName(book: BookDto): String {
        var chapterName = "${book.tom}. ?????????? ${DecimalFormat("#,###.##").format(book.number).replace(",", ".")}"
        if (book.name?.isNotBlank() == true) {
            chapterName += " ${book.name.capitalize()}"
        }
        return chapterName
    }

    private fun mangaBranches(manga: SManga): List<BranchesDto> {
        val response = client.newCall(titleDetailsRequest(manga)).execute()
        val series = json.decodeFromString<MangaDetDto>(response.body!!.string())
        branches[series.title.en] = series.branches
        return series.branches
    }

    private fun selector(b: BranchesDto): Boolean = b.is_default
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val branch = branches.getOrElse(manga.title) { mangaBranches(manga) }
        return when {
            branch.isEmpty() -> {
                return Observable.just(listOf())
            }
            manga.status == SManga.LICENSED -> {
                Observable.error(Exception("?????????????????????????? - ?????? ????????"))
            }
            else -> {
                val branchId = branch.first { selector(it) }.id
                client.newCall(chapterListRequest(branchId))
                    .asObservableSuccess()
                    .map { response ->
                        chapterListParse(response)
                    }
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body!!.string()
        val chapters = json.decodeFromString<SeriesWrapperDto<List<BookDto>>>(body)

        return chapters.items.filter { it.is_available }.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.number
                name = chapterName(chapter)
                url = "/chapters/${chapter.id}/pages"
                date_upload = parseDate(chapter.created_at)
                scanlator = chapter.translator
            }
        }
    }
    override fun chapterListRequest(manga: SManga): Request = throw NotImplementedError("Unused")
    private fun chapterListRequest(branch: Long): Request {
        return GET(
            "$API_URL/branches/$branch/chapters?reverse=true&size=1000000",
            headers
        )
    }

    @TargetApi(Build.VERSION_CODES.N)
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(API_URL + chapter.url, headers)
    }

    private fun pageListParse(response: Response, chapter: SChapter): List<Page> {
        val body = response.body?.string()!!
        val pages = json.decodeFromString<List<PageDto>>(body)
        val result = mutableListOf<Page>()
        pages.forEach { page ->
            (1..page.slices!!).map { i ->
                result.add(Page(result.size, API_URL + chapter.url + "/${page.id}?slice=$i"))
            }
        }
        return result
    }

    override fun pageListParse(response: Response): List<Page> = throw Exception("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response, chapter)
            }
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val bodyLength = client.newCall(GET(page.url, headers)).execute().body!!.contentLength()
        return if (bodyLength > 320)
            Observable.just(page.url)
        else
            Observable.just("$baseUrl/error-page/img/logo-fullsize.png")
    }

    override fun imageUrlRequest(page: Page): Request = throw NotImplementedError("Unused")

    override fun imageUrlParse(response: Response): String = throw NotImplementedError("Unused")

    override fun imageRequest(page: Page): Request {
        val refererHeaders = headersBuilder().build()
        return GET(page.imageUrl!!, refererHeaders)
    }

    private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

    private class TypeList(types: List<CheckFilter>) : Filter.Group<CheckFilter>("????????", types)
    private class StatusList(statuses: List<CheckFilter>) : Filter.Group<CheckFilter>("????????????", statuses)
    private class GenreList(genres: List<CheckFilter>) : Filter.Group<CheckFilter>("??????????", genres)

    override fun getFilterList() = FilterList(
        OrderBy(),
        GenreList(getGenreList()),
        TypeList(getTypeList()),
        StatusList(getStatusList())
    )

    private class OrderBy : Filter.Sort(
        "????????????????????",
        arrayOf("???? ????????????????", "???? ??????????????"),
        Selection(0, false)
    )

    private fun getTypeList() = listOf(
        CheckFilter("??????????", "manga"),
        CheckFilter("????????????", "manhwa"),
        CheckFilter("??????????????", "manhya"),
        CheckFilter("??????????", "single"),
        CheckFilter("OEL-??????????", "oel"),
        CheckFilter("????????????", "comics"),
        CheckFilter("??????????????", "russian")
    )

    private fun getStatusList() = listOf(
        CheckFilter("??????????????????????", "on_going"),
        CheckFilter("????????????????", "abandoned"),
        CheckFilter("????????????????", "completed"),
        CheckFilter("??????????????????????????", "suspended")
    )

    private fun getGenreList() = listOf(
        CheckFilter("c????????-????", "28"),
        CheckFilter("????????????", "17"),
        CheckFilter("???????????? ??????????????????", "33"),
        CheckFilter("??????????", "34"),
        CheckFilter("?????????????????? ??????????????", "3"),
        CheckFilter("?????????????????????? ??????????????", "19"),
        CheckFilter("????????????????", "35"),
        CheckFilter("????????????", "4"),
        CheckFilter("????????????????", "20"),
        CheckFilter("??????????", "36"),
        CheckFilter("????????????", "5"),
        CheckFilter("????????", "21"),
        CheckFilter("??????????", "36"),
        CheckFilter("????????????", "5"),
        CheckFilter("????????", "21"),
        CheckFilter("????????????", "37"),
        CheckFilter("??????????????", "6"),
        CheckFilter("??????????????????", "22"),
        CheckFilter("????????????", "38"),
        CheckFilter("??????????????", "7"),
        CheckFilter("????????-??????????", "23"),
        CheckFilter("????????", "39"),
        CheckFilter("??????????????", "8"),
        CheckFilter("?????????????? ????????????????????", "24"),
        CheckFilter("??????????????????", "40"),
        CheckFilter("????????????????????????????", "9"),
        CheckFilter("????????????????????????????????", "25"),
        CheckFilter("??????????????????????", "41"),
        CheckFilter("????????????????????", "10"),
        CheckFilter("??????????????????", "26"),
        CheckFilter("?????????????????????? ????????????", "42"),
        CheckFilter("????????????????????????????????????", "11"),
        CheckFilter("??????????", "27"),
        CheckFilter("??????????-????", "43"),
        CheckFilter("??????????", "13"),
        CheckFilter("??????????", "44"),
        CheckFilter("????????????", "12"),
        CheckFilter("????????????????", "29"),
        CheckFilter("??????????????", "45"),
        CheckFilter("??????????", "14"),
        CheckFilter("????????????????????", "30"),
        CheckFilter("??????????????", "46"),
        CheckFilter("??????????", "15"),
        CheckFilter("???????????????? ??????????", "1"),
        CheckFilter("??????????????", "31"),
        CheckFilter("????????", "47"),
        CheckFilter("??????", "16"),
        CheckFilter("??????", "32"),
    )

    companion object {
        private const val API_URL = "https://api.newmanga.org/v2"
        private const val IMAGE_URL = "https://storage.newmanga.org"
    }

    private val json: Json by injectLazy()
}
