package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class ExamplePlugin {
    fun load(context: Context) {
        // Register our provider class above
        registerMainAPI(ExampleProvider())
    }
