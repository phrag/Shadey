package app.shadey

import android.app.Application
import org.maplibre.android.MapLibre

class ShadeyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MapLibre needs no API key and sends no telemetry. Fully offline-capable.
        MapLibre.getInstance(this)
    }
}
