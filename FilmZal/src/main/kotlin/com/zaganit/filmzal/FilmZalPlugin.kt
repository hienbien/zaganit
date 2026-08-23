package com.zaganit.filmzal

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmZalPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmZalProvider())
    }
}

