# SamehadakuProvider Implementation Plan

**Goal:** Add a new CloudStream provider `Samehadaku` that scrapes samehadaku (anime sub-Indo) — homepage, search, series detail with episode list, and multi-server streaming via AJAX player-option → iframe → `loadExtractor`.

**Architecture:** Single `MainAPI` provider (follows existing repo convention like Kuronime/Donghub). Unlike Animeku (direct MP4), Samehadaku uses a server selector (`#server > ul > li > div.east_player_option`) that triggers an AJAX POST to `/wp-admin/admin-ajax.php` (action `player_ajax`) returning an `<iframe src>`. The iframe points to a third-party host, so we delegate to CloudStream's built-in `loadExtractor` (which already supports StreamWish, Blogger, file hosts, etc.). SharedModule extractors (AllExtractors, Jeniusplay, StreamWish) are available as fallback.

**Tech Stack:** Kotlin, CloudStream3 `MainAPI`, Jsoup (`document.select`), `app.get`/`app.post`, `loadExtractor`, `newExtractorLink`/`newEpisode`, `tryParseJson` (NEVER `parseJson`/`ObjectMapper`/`Gson` per AGENTS.md).

## Global Constraints
- Use `tryParseJson` only — NO `parseJson`/`ObjectMapper`/`Gson`/`ObjectMapper`.
- Bump provider `version` on every change (start at `1`).
- `lang = "id"`.
- Author format: new provider → `listOf("MWK")`.
- All `app.get`/`app.post` calls use `timeout = 15_000L`.
- Register module in root `build.gradle.kts` `subprojects` automatically (Gradle picks up folders with `build.gradle.kts`).
- Domain: canonical is `v1.samehadaku.how` but live serves on `v2.samehadaku.how`. Use `https://v2.samehadaku.how` as `mainUrl` (follow redirects handled by NiceHttp).

---

## Research Findings (from HTML analysis + existing CloudStream Samehadaku provider)

### Selectors
| Purpose | Selector |
|---------|----------|
| Card wrapper | `div.animepost` (or `article.animpost` on search) |
| Card anchor → detail | `div.animepost > .animposx > a[href*="/anime/"]` |
| Card title | `div.animepost > .animposx > a > .data > .title > h2` |
| Card poster | `div.animepost > .animposx > a > .content-thumb > img.anmsa` (attr `src`) |
| Detail title | `h1.entry-title` |
| Detail poster | `div.infoanime > div.thumb > img.anmsa` |
| Detail description | `div.infoanime > div.infox > div.desc > div.entry-content` |
| Detail tags | `div.infoanime > div.infox > div.genre-info > a[rel="tag"]` |
| Episode list | `div.lstepsiode.listeps > ul > li` |
| Episode number | `li > div.epsright > span.eps > a` (text = number) |
| Episode title | `li > div.epsleft > span.lchx > a` (text) |
| Episode href | same `a[href]` (links to `/<anime>-episode-<N>/`) |
| Server option | `#server > ul > li > div.east_player_option` (attrs `data-post`, `data-nume`, `data-type`) |

### URL patterns
- Home: `https://v2.samehadaku.how/`
- List: `https://v2.samehadaku.how/daftar-anime-2/page/%d/`
- Search: `https://v2.samehadaku.how/?s=QUERY` (301 → `/search/QUERY/`, HTML served)
- Detail: `https://v2.samehadaku.how/anime/<slug>/`
- Episode: `https://v2.samehadaku.how/<anime>-episode-<N>[-<suffix>]/`

### Player (loadLinks) flow
1. GET episode page → select `#server > ul > li > div.east_player_option` (may be multiple servers).
2. For each server option, POST to `https://v2.samehadaku.how/wp-admin/admin-ajax.php`:
   - body: `action=player_ajax`, `post=data-post`, `nume=data-nume`, `type=data-type`
   - headers: `X-Requested-With: XMLHttpRequest`, `Referer: <episode page url>`
3. Response HTML contains `<iframe src="...">`. Extract `src` via regex `src=["']([^"']+)["']`.
4. Call `loadExtractor(iframeSrc, referer = episodeUrl, subtitleCallback, callback)` — CloudStream handles the host (StreamWish, Blogger, file hosts, direct mp4/m3u8, etc.).

---

## File Structure
```
SamehadakuProvider/
├── build.gradle.kts                                       # version + cloudstream{} metadata
└── src/main/kotlin/com/samehadaku/
    ├── SamehadakuProvider.kt                              # MainAPI implementation
    └── SamehadakuProviderPlugin.kt                        # @CloudstreamPlugin entry (Plugin/load)
```

---

### Task 1: Provider metadata + plugin entry

**Files:**
- Create: `SamehadakuProvider/build.gradle.kts`
- Create: `SamehadakuProvider/src/main/kotlin/com/samehadaku/SamehadakuProviderPlugin.kt`

**Content `build.gradle.kts`:**
```kotlin
version = 1

cloudstream {
    description = "Samehadaku — Streaming Anime Subtitle Indonesia"
    language = "id"
    authors = listOf("MWK")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://samehadaku.how&size=%size%"
}
```

**Content `SamehadakuProviderPlugin.kt`:**
```kotlin
package com.samehadaku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SamehadakuProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SamehadakuProvider())
    }
}
```

- [ ] **Step 1:** Create both files with above content.
- [ ] **Step 2:** `git add SamehadakuProvider && git commit -m "feat: add SamehadakuProvider scaffold (v1)"`
- [ ] **Step 3:** Verify Gradle picks it up (`./gradlew :SamehadakuProvider:tasks` succeeds).

---

### Task 2: `SamehadakuProvider` class skeleton + homepage

**Files:**
- Create: `SamehadakuProvider/src/main/kotlin/com/samehadaku/SamehadakuProvider.kt`

**Interfaces:**
- Consumes: `mainUrl = "https://v2.samehadaku.how"`, CloudStream `MainAPI`.
- Produces: `getMainPage`, `search`, `load`, `loadLinks`.

**Pseudocode (skeleton + homepage + card mapper):**
```kotlin
package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SamehadakuProvider : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/anime-terbaru/" to "Anime Terbaru",
        "$mainUrl/daftar-anime-2/page/%d/" to "Daftar Anime",
        "$mainUrl/anime-movie/" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("%d", page.toString())
        val document = app.get(url, timeout = 15_000L).document
        val items = document.select("div.animepost").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".animposx > a") ?: return null
        val href = anchor.attr("abs:href").takeIf { it.contains("/anime/") } ?: return null
        val title = anchor.selectFirst(".data .title h2")?.text()?.trim() ?: return null
        val poster = fixUrlNull(anchor.selectFirst(".content-thumb img.anmsa")?.attr("abs:src"))
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = poster
        }
    }
}
```
> NOTE: Homepage "Home" uses the `.animepost` grid from the generic section; the "Anime Terbaru" widget uses a different `ul li` layout — if `.animepost` is empty on `/`, fall back to `div.post-show ul li` cards. Verify during execution.

- [ ] **Step 1:** Write skeleton + `getMainPage` + `toSearchResult`.
- [ ] **Step 2:** `git commit -m "feat: SamehadakuProvider homepage + card mapper"`

---

### Task 3: `search()`

**Pseudocode (add to class):**
```kotlin
override suspend fun search(query: String): List<SearchResponse> {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val document = app.get("$mainUrl/?s=$encoded", timeout = 15_000L).document
    return document.select("div.animepost, article.animpost").mapNotNull { el ->
        val anchor = el.selectFirst(".animposx > a")
            ?: el.selectFirst("a[href*=\"/anime/\"]") ?: return@mapNotNull null
        val href = anchor.attr("abs:href").takeIf { it.contains("/anime/") } ?: return@mapNotNull null
        val title = anchor.selectFirst(".data .title h2")?.text()?.trim()
            ?: anchor.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val poster = fixUrlNull(anchor.selectFirst("img.nmsa, img.anmsa")?.attr("abs:src"))
        newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = poster }
    }.distinctBy { it.url }
}
```

- [ ] **Step 1:** Add `search()` method.
- [ ] **Step 2:** `git commit -m "feat: SamehadakuProvider search"`

---

### Task 4: `load()` — series detail + episodes

**Pseudocode:**
```kotlin
override suspend fun load(url: String): LoadResponse {
    val document = app.get(url, timeout = 15_000L).document

    val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
    val poster = fixUrlNull(document.selectFirst("div.infoanime > div.thumb > img.nmsa, div.infoanime > div.thumb > img.anmsa")?.attr("abs:src"))
    val description = document.selectFirst("div.infoanime > div.infox > div.desc > div.entry-content")?.text()?.trim()
    val tags = document.select("div.infoanime > div.infox > div.genre-info > a[rel=tag]").map { it.text().trim() }

    val episodes = document.select("div.lstepsiode.listeps > ul > li").mapNotNull { li ->
        val link = li.selectFirst("div.epsleft > span.lchx > a, div.epsright > span.eps > a")
            ?: return@mapNotNull null
        val epHref = link.attr("abs:href")
        val epTitle = li.selectFirst("div.epsleft > span.lchx > a")?.text()?.trim() ?: "Episode"
        val epNum = li.selectFirst("div.epsright > span.eps > a")?.text()
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        newEpisode(fixUrl(epHref)) {
            this.name = epTitle
            this.episode = epNum
            this.posterUrl = poster
        }
    }.reversed() // site lists newest-first

    return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
        this.posterUrl = poster
        this.plot = description
        this.tags = tags
    }
}
```

- [ ] **Step 1:** Add `load()` method.
- [ ] **Step 2:** `git commit -m "feat: SamehadakuProvider load (detail + episodes)"`

---

### Task 5: `loadLinks()` — AJAX player-option → iframe → loadExtractor

**Pseudocode:**
```kotlin
companion object {
    private val IFRAME_SRC_REGEX = Regex("""src=["']([^"']+)["']""")
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data, timeout = 15_000L).document
    val serverOptions = document.select("#server > ul > li > div.east_player_option")

    if (serverOptions.isEmpty()) {
        throw ErrorLoadingException("Tidak ada server di Samehadaku")
    }

    var foundAny = false
    for (opt in serverOptions) {
        val post = opt.attr("data-post")
        val nume = opt.attr("data-nume")
        val type = opt.attr("data-type").ifBlank { "schtml" }
        if (post.isBlank() || nume.isBlank()) continue

        try {
            val ajaxHtml = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                ),
                timeout = 15_000L
            ).text

            val iframeSrc = IFRAME_SRC_REGEX.find(ajaxHtml)?.groupValues?.get(1)?.let { fixUrl(it) }
                ?: continue

            foundAny = true
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        } catch (_: Exception) {
            // try next server
        }
    }

    if (!foundAny) throw ErrorLoadingException("Tidak ada sumber video di Samehadaku")
    return true
}
```

**Server handling rationale:**
- The `iframeSrc` host varies (StreamWish, Blogger, filedon, krakenfiles, direct mp4/m3u8, etc.).
- `loadExtractor` delegates to CloudStream's built-in extractors (which already cover most common hosts). SharedModule extractors (StreamWish, Jeniusplay) provide fallback.
- `Referer = data` (the episode page URL) is required by most embeds.

- [ ] **Step 1:** Add `loadLinks()` with AJAX POST + iframe extraction + `loadExtractor`.
- [ ] **Step 2:** `git commit -m "feat: SamehadakuProvider loadLinks (AJAX player-option + loadExtractor)"`

---

### Task 6: Build & CI verification

**Files:**
- Verify: root `build.gradle.kts` auto-includes `SamehadakuProvider` subproject.

- [ ] **Step 1:** Run `./gradlew :SamehadakuProvider:make` (or full `./gradlew make`) locally if possible; expect `.cs3` build.
- [ ] **Step 2:** Push to `master` → GitHub Actions builds → `builds` branch gets `SamehadakuProvider.cs3`.
- [ ] **Step 3:** Install `.cs3` in CloudStream, test: search "Naruto", open series, play an episode, verify servers resolve and stream.
- [ ] **Step 4:** If a specific host fails, add a dedicated extractor or pass extra headers; bump version.
- [ ] **Step 5:** `git commit -m "fix: SamehadakuProvider <issue>"` if needed.

---

## Open Questions / Risks
1. **Homepage card selector** — `/` may use the `ul li` "Anime Terbaru" layout instead of `.animepost`. Verify during Task 2; add fallback if needed.
2. **Search redirect** — `?s=` 301-redirects to `/search/QUERY/`. NiceHttp follows redirects; if it mangles the query, use the `/search/QUERY/` URL directly.
3. **AJAX action name** — Confirmed `player_ajax` from existing CloudStream Samehadaku provider + Samehadaku downloader gist. If site changed, inspect `front.ajax.js` / network call.
4. **Domain rotation** — `v1`/`v2`/`email` variants exist. Use `v2` now; update `mainUrl` if it dies.
5. **Subtitle** — hardcoded sub Indo in video; no separate `SubtitleFile` needed.

## Execution Handoff
After saving, choose: **Subagent-Driven** (recommended — agent writes files + commits per task) or **Inline Execution**.
