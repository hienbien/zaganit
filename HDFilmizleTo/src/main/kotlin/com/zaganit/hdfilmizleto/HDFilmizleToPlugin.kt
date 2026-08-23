package com.zaganit.hdfilmizleto

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HDFilmizleToPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HDFilmizleToProvider())
    }
}

