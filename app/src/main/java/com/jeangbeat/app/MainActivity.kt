package com.jeangbeat.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jeangbeat.app.ui.StudioScreen
import com.jeangbeat.core.ui.JeangBeatTheme
import com.jeangbeat.core.ui.R as UiR

class MainActivity : ComponentActivity() {
    private val viewModel: StudioViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.ensureProject(
            defaultName = getString(UiR.string.my_first_beat),
            patternName = getString(UiR.string.pattern_default_name, 1),
        )
        setContent {
            JeangBeatTheme {
                StudioScreen(
                    viewModel = viewModel,
                    onLanguageChange = { language ->
                        (application as JeangBeatApp).settings.language = language
                        recreate()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }
}
