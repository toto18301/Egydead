package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class ExamplePlugin : CloudstreamPlugin() {
    override fun load(context: Context) {
        // Register the provider
        registerMainAPI(ExampleProvider())
    }
}
