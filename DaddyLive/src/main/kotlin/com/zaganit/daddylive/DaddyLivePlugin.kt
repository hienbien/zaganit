package com.zaganit.daddylive

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DaddyLivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DaddyLiveProvider())
    }
}
