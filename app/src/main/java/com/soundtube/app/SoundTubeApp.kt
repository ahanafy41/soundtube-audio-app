package com.soundtube.app

import android.app.Application
import com.soundtube.app.data.remote.NewPipeExtractorHelper

class SoundTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize NewPipeExtractor engine
        NewPipeExtractorHelper.init()
    }
}
