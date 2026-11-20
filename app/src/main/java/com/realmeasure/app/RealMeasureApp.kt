package com.realmeasure.app

import android.app.Application
import org.opencv.android.OpenCVLoader

/**
 * Application entry point.
 *
 * Loads the OpenCV native library (`libopencv_java4.so`) that ships inside the
 * official `org.opencv:opencv` Maven artifact. `initLocal()` requires no NDK,
 * no manual `.so` copying and no legacy OpenCV Manager app - it links the
 * bundled libraries directly at process start.
 */
class RealMeasureApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initialized = OpenCVLoader.initLocal()
    }

    companion object {
        /** True once the OpenCV native runtime is available in this process. */
        var initialized: Boolean = false
            private set
    }
}
