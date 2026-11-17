package com.cloudnest.player.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
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
import com.cloudnest.player.ui.screens.MusicScreen
import com.cloudnest.player.ui.screens.LoginScreen
import com.cloudnest.player.ui.MiniPlayerBar
import com.cloudnest.player.ui.screens.ServersScreen
import com.cloudnest.player.ui.screens.SettingsScreen
import com.cloudnest.player.ui.theme.NestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001) }
        }
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

    LaunchedEffect(Unit) { app.checkUpdate(false) }

    LaunchedEffect(app.message) {
        app.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            app.message = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val screen = app.current) {
            is Screen.Home -> HomeScreen(app)
            is Screen.Browse -> BrowseScreen(app, screen.serverId, screen.path, screen.title)
            is Screen.Detail -> DetailScreen(app, screen.itemId)
            is Screen.Servers -> ServersScreen(app)
            is Screen.Settings -> SettingsScreen(app)
            is Screen.Search -> SearchScreen(app)
            is Screen.Music -> MusicScreen(app)
            is Screen.Login -> LoginScreen(app)
        }
        // 迷你播放条（音乐页自己会显示，避免重复）
        if (app.current !is Screen.Music) {
            MiniPlayerBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }

    app.updateInfo?.let { info ->
        UpdateDialog(info) { app.updateInfo = null }
    }

    (app.musicScanningMessage ?: app.scanningMessage)?.let { msg ->
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

/** 发现新版本：展示版本号与更新摘要，点「去更新」打开发布页下载 */
@Composable
private fun UpdateDialog(info: com.cloudnest.player.net.UpdateChecker.Info, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF12121A)) {
            Column(Modifier.padding(20.dp).width(280.dp)) {
                Text(
                    "发现新版本 v${info.version}",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前 ${com.cloudnest.player.BuildConfig.VERSION_NAME}" +
                        if (info.size > 0) " · 新包 ${info.size / 1024 / 1024} MB" else "",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 11.5.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    info.notes.lineSequence().filter { it.isNotBlank() }.take(8).joinToString("\n"),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 10, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("稍后", color = Color.White.copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val url = info.apkUrl ?: info.pageUrl
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = com.cloudnest.player.ui.theme.BrandCyan),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("去更新", color = Color(0xFF07070C), fontWeight = FontWeight.Bold)
                    }
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
