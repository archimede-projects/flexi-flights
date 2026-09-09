package com.archimedeprojects.volaflex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.archimedeprojects.volaflex.data.ApiKeyStore
import com.archimedeprojects.volaflex.ui.VolaFlexApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val versionName = getAppVersionName()
        val apiKeyStore = ApiKeyStore(applicationContext)

        setContent {
            VolaFlexApp(
                versionName = versionName,
                apiKeyStore = apiKeyStore
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getAppVersionName(): String {
        return packageManager
            .getPackageInfo(packageName, 0)
            .versionName
            ?: "unknown"
    }
}
