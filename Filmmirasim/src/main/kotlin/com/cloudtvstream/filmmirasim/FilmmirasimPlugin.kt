package com.cloudtvstream.filmmirasim

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmmirasimPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmmirasimProvider())
    }
}
