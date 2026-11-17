package com.cloudnest.player.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.ui.screens.BrowseScreen
import com.cloudnest.player.ui.screens.DetailScreen
import com.cloudnest.player.ui.screens.HomeScreen
import com.cloudnest.player.ui.screens.SearchScreen
import com.cloudnest.player.ui.screens.ServersScreen
import com.cloudnest.player.ui.screens.SettingsScreen
import com.cloudnest.player.ui.theme.NestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        StreamProxy.start()
        setContent {
            val settings = Store.data.collectAsState().value.settings
            val dark = when (settings.themeMode) {
                1 -> true
                2 -> false
                else -> isSystemInDarkTheme()
            }
            NestTheme(dark = dark) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = remember { AppState(context, scope) }

    BackHandler {
        if (!app.pop()) {
            (context as? ComponentActivity)?.finish()
        }
    }

    LaunchedEffect(app.message) {
        app.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            app.message = null
        }
    }

    when (val screen = app.current) {
        is Screen.Home -> HomeScreen(app)
        is Screen.Browse -> BrowseScreen(app, screen.serverId, screen.path, screen.title)
        is Screen.Detail -> DetailScreen(app, screen.itemId)
        is Screen.Servers -> ServersScreen(app)
        is Screen.Settings -> SettingsScreen(app)
        is Screen.Search -> SearchScreen(app)
    }

    app.scanningMessage?.let { msg ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF12121A), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) {
                Column(
                    Modifier.padding(24.dp).width(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = com.cloudnest.player.ui.theme.BrandCyan)
                    Spacer(Modifier.height(14.dp))
                    Text(msg, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("扫描期间可以继续其他操作", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
    }
}
