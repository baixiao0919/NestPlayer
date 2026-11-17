package com.cloudnest.player.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.cloudnest.player.lib.CrashLog
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
                val crash = remember { CrashLog.peek(this@MainActivity) }
                if (crash != null) {
                    CrashDialog(crash) { CrashLog.clear(this@MainActivity) }
                }
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

/** 上次闪退的栈，直接显示给用户截图反馈 */
@Composable
private fun CrashDialog(text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF12121A)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("上次运行闪退了", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "把下面这段截图发我，能定位到具体哪一行。",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "关闭",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "删除日志",
                        color = Color(0xFF2EE6D6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)
                    )
                }
            }
        }
    }
}
