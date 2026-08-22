package com.zaganit.tvjustin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TVJustinPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TVJustinProvider())
    }
}
