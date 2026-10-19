package com.bytedance.spatialagendawall.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.bytedance.spatialagendawall.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
