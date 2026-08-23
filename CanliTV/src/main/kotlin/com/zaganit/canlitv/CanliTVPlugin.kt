package com.zaganit.canlitv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CanliTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CanliTVProvider())
    }
}
