package com.zaganit.nadirfilm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NadirFilmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NadirFilmProvider())
    }
}

