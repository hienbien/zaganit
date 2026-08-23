package com.zaganit.netdizihd

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NetDiziHDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NetDiziHDProvider())
    }
}
