package com.zaganit.filmdozu

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmDozuPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmDozuProvider())
    }
}

