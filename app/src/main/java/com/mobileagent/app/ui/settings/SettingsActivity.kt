package com.mobileagent.app.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mobileagent.app.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.mobileagent.app.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<android.view.View>(R.id.settingsContainer)?.let {
            com.mobileagent.app.util.ThemeManager.applyDynamicBackground(it)
        }
    }
}
