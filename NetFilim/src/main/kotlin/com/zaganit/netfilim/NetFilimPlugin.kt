package com.zaganit.netfilim

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NetFilimPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NetFilimProvider())
    }
}
