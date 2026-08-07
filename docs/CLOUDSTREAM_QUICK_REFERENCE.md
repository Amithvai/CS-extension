# CloudStream Quick Reference

## Import Wajib

```kotlin
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
```

## JSON Parsing

```kotlin
// ✅ BENAR — pakai tryParseJson
val data = tryParseJson<MyClass>(jsonString)

// ❌ SALAH — jangan pakai ObjectMapper langsung
val data = objectMapper.readValue(jsonString, MyClass::class.java)
```

## Data Class JSON

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class MyClass(
    @JsonProperty("name") val name: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("optional") val optional: String?  // nullable
)
```

## HTTP Requests

```kotlin
// GET
val doc = app.get(url).document
val text = app.get(url).text
val json = app.get(url).parsedSafe<MyClass>()

// POST form
val res = app.post(url, data = mapOf("key" to "value"))

// POST JSON
val body = mapOf("key" to "value").toJson()
val res = app.post(url, requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()))
```

## Search

```kotlin
override suspend fun search(query: String): List<SearchResponse> {
    return app.get("$mainUrl/?s=$query").document
        .select("div.item")
        .mapNotNull { el ->
            val title = el.selectFirst("h3")?.text() ?: return@mapNotNull null
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime)
        }
}
```

## Home Page

```kotlin
override val mainPage = mainPageOf(
    "$mainUrl/category/1/page/" to "Category 1",
    "$mainUrl/category/2/page/" to "Category 2"
)

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val doc = app.get(request.data + page).document
    val home = doc.select("div.item").mapNotNull { it.toSearchResponse() }
    return newHomePageResponse(request.name, home)
}
```

## Load

```kotlin
override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).document
    val title = doc.selectFirst("h1")?.text() ?: ""
    val episodes = doc.select("div.ep-list a").map { 
        newEpisode(it.attr("href")) { this.name = it.text() }
    }
    return newAnimeLoadResponse(title, url, TvType.Anime) {
        addEpisodes(DubStatus.Subbed, episodes)
    }
}
```

## Load Links

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Cara 1: Load dari iframe
    val iframe = app.get(data).document.selectFirst("iframe")?.attr("src")
    if (iframe != null) loadExtractor(iframe, data, subtitleCallback, callback)
    
    // Cara 2: Buat manual
    callback(newExtractorLink("Source", "Name", url, ExtractorLinkType.M3U8))
    
    return true
}
```

## Parallel Execution

```kotlin
runAllAsync(
    { invokeSource1() },
    { invokeSource2() },
    { invokeSource3() }
)
```

## CSS Selectors

```kotlin
document.select("div.class")           // By class
document.select("div#id")              // By ID  
document.select("div > p")             // Direct child
document.select("a[href]")             // With attribute
document.selectFirst("h1")             // First match
element.attr("href")                   // Get attribute
element.text()                         // Get text
```

## Regex

```kotlin
val num = Regex("Episode\\s?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
val url = Regex("""href="(https?[^"]+)""").find(html)?.groupValues?.get(1)
```

## Error Handling

```kotlin
try { riskyOperation() } catch (e: Exception) { }
val result = runCatching { operation() }.getOrNull()
throw ErrorLoadingException("Message")
```

## Plugin Registration

```kotlin
@CloudstreamPlugin
class MyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyProvider())
        registerExtractorAPI(MyExtractor())
    }
}
```

## Build Command

```bash
./gradlew ProviderName:make
```
