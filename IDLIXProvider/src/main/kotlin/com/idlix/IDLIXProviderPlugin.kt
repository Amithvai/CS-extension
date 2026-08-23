package com.idlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IDLIXProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IDLIXProvider())
        // Register common extractors used by IDLIX/LK21/Rebahin mirrors
        registerExtractorAPI(FileMoonExtractor())
        registerExtractorAPI(StreamtapeExtractor())
        registerExtractorAPI(DoodstreamExtractor())
        registerExtractorAPI(MixDropExtractor())
        registerExtractorAPI(StreamWishExtractorIdlix())
        registerExtractorAPI(HxfileExtractor())
        registerExtractorAPI(VideonodeExtractor())
    }
}
