// use an integer for version numbers
version = 92

android {
    namespace = "com.phisher98"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        buildConfigField("String", "TMDB_API_KEY", "\"1865f43a0549ca50d341dd9ab8b29f49\"")
        buildConfigField("String", "CINEMETA_URL", "\"https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb\"")
    }
}

cloudstream {
    description = "#1 Best Extension – MultiAPI-Based with 4K Torrent Support (Debian) Use Extension Settings"
    language    = "en"
    authors = listOf("Phisher98", "MWK")

    status = 1

    tvTypes = listOf("Movie","Torrent","AsianDrama","TvSeries","Anime")

    iconUrl = "https://torrentio.strem.fun/images/logo_v1.png"
    requiresResources = true
    isCrossPlatform = false
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
