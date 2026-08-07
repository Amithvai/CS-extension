<p align="center">
  <img src="https://github.com/Amithvai.png" width="120" style="border-radius:50%;" />
</p>

<h1 align="center">CS Extension</h1>

<p align="center">
  <b>CloudStream3 Extension Repository</b><br/>
  Streaming Film, Anime & Drama dengan Subtitle Indonesia
</p>

<p align="center">
  <a href="https://github.com/Amithvai/CS-extension/actions">
    <img src="https://img.shields.io/github/actions/workflow/status/Amithvai/CS-extension/build.yml?branch=master&label=Build&style=flat-square" alt="Build Status" />
  </a>
  <img src="https://img.shields.io/github/last-commit/Amithvai/CS-extension?style=flat-square" alt="Last Commit" />
  <img src="https://img.shields.io/github/repo-size/Amithvai/CS-extension?style=flat-square" alt="Repo Size" />
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Indonesian-blue?style=flat-square" alt="Language" />
</p>

---

## Installation

1. Download [CloudStream3](https://github.com/recloudstream/cloudstream/releases)
2. Buka CloudStream → **Settings** → **Extensions**
3. Tambahkan repository URL berikut:

```
https://raw.githubusercontent.com/Amithvai/CS-extension/master/repo.json
```

4. Install provider yang kamu inginkan dari tab **Extensions**

---

## Providers

| Provider | Tipe | Deskripsi |
|:---------|:-----|:----------|
| **Anichin** | Anime | Streaming Donghua Subtitle Indonesia |
| **CineMax21** | Movie & TV | Streaming Movie dan TV Series (TMDB-based, multi-source) |
| **Donghub** | Anime | Streaming Donghua Subtitle Indonesia |
| **Drachin** | Asian Drama | Streaming Drama China Sub Indo (YouTube) |
| **DutaMovie** | Movie & TV | Streaming Movie dan TV Series |
| **Kisskh** | Movie & TV | Streaming Movie dan TV Series (TMDB-based) |
| **KlikXXI** | Movie & TV | Streaming Movie dan TV Series |
| **Kuronime** | Anime | Streaming Anime Subtitle Indonesia |
| **MovieBox** | Movie & TV | Streaming Movie, TV Series & Drama |
| **NgeFilm21** | Movie & TV | Streaming Movie dan TV Series |
| **Nomat** | Movie & TV | Streaming Movie dan TV Series |
| **OneTouchTV** | Movie & TV | Streaming Movie dan TV Series (multi-source) |
| **OtakuDesu** | Anime | Streaming Anime Subtitle Indonesia |
| **PencuriMovie** | Movie & TV | Streaming Movie dan TV Series |
| **TorraStream** | Movie, TV & Anime | Multi-API berbasis Torrentio (TMDB + torrent indexer, magnet streaming) |


---

## Features

- Subtitle Indonesia untuk semua provider
- Parallel loading untuk kecepatan optimal
- Support Movie, TV Series, Anime, dan Asian Drama
- Multi-source extraction (jika satu source down, source lain tetap jalan)
- Auto-update via GitHub Actions

---

## Tech Stack

| Komponen | Versi |
|:---------|:------|
| CloudStream3 | pre-release |
| Kotlin | 2.3.0 |
| Gradle | 8.13 |
| Android SDK | 35 |
| NiceHttp | 0.4.16 |
| Jsoup | 1.22.1 |
| Jackson | 2.13.1 |

---

## Build dari Source

```bash
git clone https://github.com/Amithvai/CS-extension.git
cd CS-extension
./gradlew build
```

Build individual provider:
```bash
./gradlew ProviderName:make
```

---

## Sumber Kode

Provider di repositori ini dikembangkan/diadaptasi dari berbagai sumber:

| Sumber | Repo |
|:-------|:-----|
| **HatsuneMikuUwU** | [cloudstream-extensions-uwu](https://github.com/HatsuneMikuUwU/cloudstream-extensions-uwu) — Referensi provider |
| **phisher98** | [cloudstream-extensions](https://github.com/phisher98/cloudstream-extensions) — Referensi struktur & loading |
| **recloudstream** | [extensions](https://github.com/recloudstream/extensions) — CloudStream API & pola umum |
| **CuxPlug** | [CuxPlug](https://github.com/CuxPlug/CuxPlug) — Optimasi & extractor |

## Disclaimer

Extension ini hanya untuk tujuan edukasi dan penelitian. Kami tidak menyediakan, menghosting, atau mendistribusikan konten apapun. Semua konten berasal dari sumber pihak ketiga. Gunakan dengan bijak dan tanggung jawab kamu sendiri.

---

<p align="center">
  <b>Made with ❤️ for Indonesian streaming community</b>
</p>
