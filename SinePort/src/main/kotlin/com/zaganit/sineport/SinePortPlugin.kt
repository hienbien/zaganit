package com.zaganit.sineport

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SinePortPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SinePortProvider())
    }
}

