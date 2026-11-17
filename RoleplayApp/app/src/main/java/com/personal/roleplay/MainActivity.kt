package com.personal.roleplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import com.personal.roleplay.di.AppContainer
import com.personal.roleplay.di.AppViewModelFactory
import com.personal.roleplay.ui.navigation.RoleplayNavHost
import com.personal.roleplay.ui.theme.RoleplayAppTheme

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("未提供 AppContainer")
}

val LocalViewModelFactory = staticCompositionLocalOf<ViewModelProvider.Factory> {
    error("未提供 ViewModelFactory")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RoleplayApplication).container

        setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalViewModelFactory provides AppViewModelFactory(container)
            ) {
                RoleplayAppTheme {
                    RoleplayNavHost()
                }
            }
        }
    }
}
