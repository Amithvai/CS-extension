# CloudStream Provider Development Reference

> Sumber: https://recloudstream.github.io/csdocs/
> Dokumentasi API: https://recloudstream.github.io/dokka/
> Terakhir diperbarui: 2026-06-01

---

## 1. Struktur Provider

Setiap provider CloudStream mewarisi `MainAPI` dan harus mengimplementasikan 4 fungsi utama:

```kotlin
class MyProvider : MainAPI() {
    override var mainUrl = "https://example.com"
    override var name = "MyProvider"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 1. Searching
    override suspend fun search(query: String): List<SearchResponse>

    // 2. Loading Home Page
    override val mainPage = mainPageOf(
        "url1" to "Category 1",
        "url2" to "Category 2"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse

    // 3. Loading Result Page
    override suspend fun load(url: String): LoadResponse

    // 4. Loading Video Links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean
}
```

---

## 2. Properties Provider

| Property | Tipe | Default | Deskripsi |
|---|---|---|---|
| `mainUrl` | `String` | - | URL utama provider |
| `name` | `String` | - | Nama provider di UI |
| `hasMainPage` | `Boolean` | `false` | Apakah punya halaman utama |
| `hasQuickSearch` | `Boolean` | `false` | Apakah punya quick search |
| `hasDownloadSupport` | `Boolean` | `true` | Apakah support download |
| `instantLinkLoading` | `Boolean` | `false` | Apakah link langsung bisa dimuat |
| `lang` | `String` | - | Bahasa (IETF BCP 47) |
| `supportedTypes` | `Set<TvType>` | - | Tipe konten yang didukung |
| `usesWebView` | `Boolean` | `false` | Apakah perlu WebView |
| `sequentialMainPage` | `Boolean` | `false` | Load homepage satu per satu |
| `sequentialMainPageDelay` | `Long` | - | Delay antar homepage request |
| `loadLinksTimeoutMs` | `Long?` | `null` | Timeout loadLinks dalam ms |
| `searchTimeoutMs` | `Long?` | `null` | Timeout search dalam ms |
| `loadTimeoutMs` | `Long?` | `null` | Timeout load dalam ms |
| `getMainPageTimeoutMs` | `Long?` | `null` | Timeout getMainPage dalam ms |

---

## 3. TvType

```kotlin
enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    OVA,
    Live,
    Torrent
}
```

---

## 4. JSON Parsing

### Cara yang BENAR (CloudStream API)

```kotlin
// Import
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

// Parse JSON (throw exception on error)
val result = parseJson<MyDataClass>(jsonString)

// Parse JSON (return null on error) ← RECOMMENDED
val result = tryParseJson<MyDataClass>(jsonString)

// Convert object to JSON
val jsonString = myObject.toJson()
```

### Data Class untuk JSON

```kotlin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class MyDataClass(
    @JsonProperty("name") val name: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("optional_field") val optionalField: String?  // nullable untuk optional
)
```

### Aturan JSON Parsing

1. **Pakai `tryParseJson`** — lebih aman, return null on error
2. **Pakai `@JsonProperty`** untuk mapping nama field
3. **Pakai nullable** untuk field yang optional
4. **Pakai `@JsonIgnoreProperties(ignoreUnknown = true)`** untuk ignore field tambahan
5. **Jangan pakai `ObjectMapper.readValue` langsung** — tidak punya type coercion seperti `tryParseJson`

---

## 5. HTTP Requests (NiceHttp)

### Import

```kotlin
import com.lagradost.nicehttp.requestOwn
import com.lagradost.nicehttp.RequestBodyTypes
```

### GET Request

```kotlin
// Basic GET
val response = app.get("https://example.com/api/data")

// GET dengan headers
val response = app.get("https://example.com/api/data", headers = mapOf("Referer" to "https://example.com"))

// GET dengan params
val response = app.get("https://example.com/api/data", params = mapOf("page" to "1"))

// GET dengan referer
val response = app.get("https://example.com/api/data", referer = "https://example.com")

// Ambil document HTML
val document = response.document

// Ambil text response
val text = response.text

// Ambil JSON response
val json = response.parsedSafe<MyDataClass>()
```

### POST Request

```kotlin
// POST dengan form data
val response = app.post(
    "https://example.com/api/search",
    data = mapOf("query" to "search term")
)

// POST dengan JSON body
val jsonBody = mapOf("keyword" to "search").toJson()
val response = app.post(
    "https://example.com/api/search",
    requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
)

// POST dengan headers
val response = app.post(
    "https://example.com/api/search",
    data = mapOf("query" to "search term"),
    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
)
```

### Response Handling

```kotlin
// Parse JSON response
val data = response.parsedSafe<MyDataClass>()  // return null on error
val data = response.parsed<MyDataClass>()       // throw exception on error

// Parse HTML document
val document = response.document

// CSS selectors
val title = document.selectFirst("h1.title")?.text()
val links = document.select("div.content a").map { it.attr("href") }
```

---

## 6. Search Response

### Types

```kotlin
// Anime
newAnimeSearchResponse(name, url, type) {
    this.posterUrl = posterUrl
    addSub(episodeCount)
    addDub(dubCount)
}

// Movie
newMovieSearchResponse(name, url, type, fixUrl) {
    this.posterUrl = posterUrl
}

// TV Series
newTvSeriesSearchResponse(name, url, type) {
    this.posterUrl = posterUrl
}
```

### Contoh

```kotlin
override suspend fun search(query: String): List<SearchResponse> {
    return app.get("$mainUrl/?s=$query").document
        .select("div.search-item")
        .mapNotNull { element ->
            val title = element.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
}
```

---

## 7. Main Page

### Definisi

```kotlin
override val mainPage = mainPageOf(
    "$mainUrl/category/sub1/page/" to "Category 1",
    "$mainUrl/category/sub2/page/" to "Category 2",
    "$mainUrl/category/sub3/page/" to "Category 3"
)
```

### Implementasi

```kotlin
override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {
    // request.data = URL pattern yang didefinisikan di mainPage
    // page = nomor halaman (mulai dari 1)
    
    val url = request.data + page
    val document = app.get(url).document
    
    val home = document.select("div.list-item").mapNotNull { element ->
        // Konversi ke SearchResponse
        element.toSearchResponse()
    }
    
    return newHomePageResponse(request.name, home)
}
```

---

## 8. Load Response

### Anime

```kotlin
override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document
    
    val title = document.selectFirst("h1")?.text() ?: ""
    val poster = document.selectFirst("img.poster")?.attr("src")
    val plot = document.selectFirst("div.description")?.text()
    val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
    val tags = document.select("div.genre a").map { it.text() }
    
    // Ambil episodes
    val episodes = document.select("div.episode-list a").map { element ->
        val epUrl = element.attr("href")
        val epName = element.text()
        val epNum = Regex("Episode (\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()
        
        newEpisode(epUrl) {
            this.name = epName
            this.episode = epNum
        }
    }
    
    return newAnimeLoadResponse(title, url, TvType.Anime) {
        this.posterUrl = poster
        this.plot = plot
        this.year = year
        this.tags = tags
        addEpisodes(DubStatus.Subbed, episodes)
    }
}
```

### Movie

```kotlin
return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
    this.posterUrl = poster
    this.plot = plot
    this.year = year
    this.tags = tags
}
```

### TV Series

```kotlin
return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
    this.posterUrl = poster
    this.plot = plot
    this.year = year
    this.tags = tags
}
```

---

## 9. Load Links

### Struktur

```kotlin
override suspend fun loadLinks(
    data: String,           // URL atau data dari load()
    isCasting: Boolean,     // Apakah sedang casting
    subtitleCallback: (SubtitleFile) -> Unit,  // Callback subtitle
    callback: (ExtractorLink) -> Unit           // Callback video
): Boolean {
    // Implementasi extraction video
    return true
}
```

### Menggunakan LoadExtractor

```kotlin
// Load video dari URL yang didukung extractor
loadExtractor(videoUrl, referer, subtitleCallback, callback)

// Contoh
override suspend fun loadLinks(...): Boolean {
    val iframeUrl = document.selectFirst("iframe")?.attr("src")
    if (iframeUrl != null) {
        loadExtractor(iframeUrl, data, subtitleCallback, callback)
    }
    return true
}
```

### Membuat ExtractorLink Manual

```kotlin
callback.invoke(
    newExtractorLink(
        source = "SourceName",      // Nama source
        name = "MirrorName",        // Nama mirror
        url = videoUrl,             // URL video
        type = ExtractorLinkType.M3U8  // Tipe video
    ) {
        this.referer = referer
        this.quality = Qualities.P720.value
        this.headers = mapOf("Cookie" to cookieValue)
    }
)
```

### ExtractorLinkType

```kotlin
enum class ExtractorLinkType {
    M3U8,       // HLS stream
    VIDEO,      // Direct video (mp4, etc)
    DASH,       // DASH stream
    // null = INFER_TYPE (auto-detect)
}
```

---

## 10. Extractors

### Struktur

```kotlin
class MyExtractor : ExtractorApi() {
    override var name = "MyExtractor"
    override var mainUrl = "https://myextractor.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract video URL dan panggil callback
        callback.invoke(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                this.referer = referer ?: mainUrl
            }
        )
    }
}
```

### Registrasi di Plugin

```kotlin
@CloudstreamPlugin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
        registerExtractorAPI(MyExtractor())
    }
}
```

---

## 11. Subtitles

```kotlin
// Menambahkan subtitle
subtitleCallback.invoke(
    SubtitleFile(
        name = "Indonesian",           // Nama subtitle
        url = subtitleUrl,             // URL subtitle
        mimeType = SubtitleHelper.SubMimeType.vtt  // MIME type
    )
)
```

### Subtitle MIME Types

```kotlin
object SubSubtitleHelper {
    const val vtt = "text/vtt"
    const val srt = "application/x-subrip"
    const val ass = "text/x-ssa"
}
```

---

## 12. Parallel Execution

### runAllAsync

```kotlin
// Jalankan beberapa task secara parallel
runAllAsync(
    {
        // Task 1
        invokeSource1(...)
    },
    {
        // Task 2
        invokeSource2(...)
    },
    {
        // Task 3
        invokeSource3(...)
    }
)
```

### amap (Parallel Map)

```kotlin
// Map dengan parallel execution
val results = list.amap { element ->
    // Proses element
    processElement(element)
}
```

---

## 13. CSS Selectors (Jsoup)

```kotlin
// Select elements
document.select("div.classname")           // By class
document.select("div#id")                  // By ID
document.select("div > p")                 // Direct child
document.select("div p")                   // Any descendant
document.select("div.class1.class2")       // Multiple classes
document.select("div[data-attr=value]")    // By attribute
document.select("a[href^=https]")          // Attribute starts with
document.select("a[href$=.mp4]")           // Attribute ends with

// Select first element
document.selectFirst("div.classname")

// Get attributes
element.attr("href")                       // Get attribute
element.attr("src")
element.text()                             // Get text
element.ownText()                          // Get own text (excluding children)

// Iterate
document.select("div.item").map { element ->
    val title = element.selectFirst("h3")?.text()
    val url = element.selectFirst("a")?.attr("href")
}
```

---

## 14. Regex

```kotlin
// Basic regex
val regex = Regex("pattern")
val match = regex.find(text)
val group = match?.groupValues?.get(1)

// With options
val regex = Regex("""pattern""", RegexOption.IGNORE_CASE)

// Common patterns
val epNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
val year = Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()
val url = Regex("""href="(https?://[^"]+)""").find(html)?.groupValues?.get(1)
```

---

## 15. Error Handling

```kotlin
// Try-catch
try {
    val result = riskyOperation()
} catch (e: Exception) {
    // Handle error
}

// Safe call
val result = runCatching { riskyOperation() }.getOrNull()

// throw ErrorLoadingException untuk menampilkan error ke user
throw ErrorLoadingException("Unable to load content")
```

---

## 16. Important Notes dari Dokumentasi

1. **Setiap provider tidak punya try-catch built-in** — handle exception sendiri
2. **Scrape video link terlebih dahulu** — jika tidak bisa scrape video, provider tidak berguna
3. **Gunakan `tryParseJson`** — jangan `ObjectMapper.readValue` langsung
4. **Gunakan nullable** untuk field yang optional di JSON
5. **Gunakan `loadExtractor`** — jangan reinvent wheel untuk extract video
6. **Gunakan `runAllAsync`** untuk parallel execution
7. **Jangan throw exception di `loadLinks`** — return false atau handle error
8. **Gunakan `fixUrl()`** untuk fix relative URL
9. **Gunakan `getQualityFromName()`** untuk convert quality string ke int
10. **Registrasi extractor** di Plugin class

---

## 17. Header yang Sering Dipakai

| Header | Fungsi | Contoh |
|---|---|---|
| `User-Agent` | Identitas client | Mozilla/5.0... |
| `Referer` | Site asal request | https://example.com |
| `X-Requested-With` | AJAX requests | XMLHttpRequest |
| `Cookie` | Session cookies | session=abc123 |
| `Authorization` | Auth token | Bearer xxx |
| `Content-Type` | Tipe konten | application/json |
| `Origin` | Origin request | https://example.com |

---

## 18. Common Patterns

### Extract Video dari Iframe

```kotlin
val iframeUrl = document.selectFirst("iframe")?.attr("src")
if (iframeUrl != null) {
    loadExtractor(iframeUrl, data, subtitleCallback, callback)
}
```

### Extract Video dari API

```kotlin
val apiResponse = app.get("$apiUrl/video/$videoId").parsedSafe<VideoResponse>()
val videoUrl = apiResponse?.data?.videoUrl
if (videoUrl != null) {
    callback.invoke(
        newExtractorLink("Source", "Source", videoUrl, ExtractorLinkType.M3U8)
    )
}
```

### Extract Subtitle dari API

```kotlin
val subResponse = app.get("$apiUrl/subtitle/$videoId").parsedSafe<SubtitleResponse>()
subResponse?.subtitles?.forEach { sub ->
    subtitleCallback.invoke(
        SubtitleFile(sub.language ?: "Unknown", sub.url ?: return@forEach)
    )
}
```

---

## 19. Build Configuration

### build.gradle.kts (Provider)

```kotlin
version = 1

cloudstream {
    language = "id"
    description = "Provider Description"
    authors = listOf("AuthorName")
    status = 1  // 0=Down, 1=Ok, 2=Slow, 3=Beta
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://example.com/icon.png"
}
```

### build.gradle.kts (Root)

```kotlin
dependencies {
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.16")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
}
```

---

## 20. Checklist Menulis Provider

- [ ] Definisikan `mainUrl`, `name`, `lang`, `supportedTypes`
- [ ] Implementasi `search()` dengan CSS selectors
- [ ] Implementasi `getMainPage()` dengan `mainPageOf`
- [ ] Implementasi `load()` dengan metadata lengkap
- [ ] Implementasi `loadLinks()` dengan video extraction
- [ ] Register provider di Plugin class
- [ ] Register extractor jika diperlukan
- [ ] Test build dengan `./gradlew ProviderName:make`
- [ ] Test di aplikasi CloudStream
