package app.shadey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.shadey.ui.MapScreen
import app.shadey.ui.theme.ShadeyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShadeyTheme {
                MapScreen()
            }
        }
    }
}
