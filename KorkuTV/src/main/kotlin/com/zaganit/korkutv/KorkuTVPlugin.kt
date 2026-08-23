package com.zaganit.korkutv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KorkuTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KorkuTVProvider())
    }
}
