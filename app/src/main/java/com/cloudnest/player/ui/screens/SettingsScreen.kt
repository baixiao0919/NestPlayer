package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.BuildConfig
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.net.StreamProxy
import com.cloudnest.player.ui.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppState) {
    val data by Store.data.collectAsState()
    val s = data.settings
    var tmdb by remember { mutableStateOf(s.tmdbKey) }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, start = 10.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { app.pop() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
                }
                Text(
                    "设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SettingGroup("外观") {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("跟随系统", "深色", "浅色").forEachIndexed { i, label ->
                        FilterChip(
                            selected = s.themeMode == i,
                            onClick = { Store.setSettings(s.copy(themeMode = i)) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                SettingSlider(
                    title = "海报墙列数：${s.gridColumns} 列",
                    value = s.gridColumns.toFloat(),
                    range = 2f..6f,
                    steps = 3
                ) { Store.setSettings(s.copy(gridColumns = it.toInt())) }
            }

            SettingGroup("播放") {
                SwitchRow("自动记忆播放进度", s.autoResume) { Store.setSettings(s.copy(autoResume = it)) }
                SwitchRow("硬解优先（兼容性更好关掉）", s.hardwareDecode) { Store.setSettings(s.copy(hardwareDecode = it)) }
                SwitchRow("TS/M2TS 估时拖动（个别机型闪退可关掉）", s.tsSeekEnabled) {
                    Store.setSettings(s.copy(tsSeekEnabled = it))
                }
                Spacer(Modifier.height(8.dp))
                Text("双击快进/后退步长：${s.seekStep} 秒", fontSize = 13.sp)
                Slider(
                    value = s.seekStep.toFloat(),
                    onValueChange = { Store.setSettings(s.copy(seekStep = it.toInt())) },
                    valueRange = 5f..60f,
                    steps = 10
                )
                Text("播放器默认方向", fontSize = 13.sp)
                Row {
                    listOf("自动", "竖屏", "横屏").forEachIndexed { i, label ->
                        FilterChip(
                            selected = s.playerOrientation == i,
                            onClick = { Store.setSettings(s.copy(playerOrientation = i)) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }

            SettingGroup("影库") {
                Text("扫描深度：${s.scanDepth} 层目录", fontSize = 13.sp)
                Slider(
                    value = s.scanDepth.toFloat(),
                    onValueChange = { Store.setSettings(s.copy(scanDepth = it.toInt())) },
                    valueRange = 2f..10f,
                    steps = 7
                )
                SwitchRow("显示隐藏文件", s.showHiddenFiles) { Store.setSettings(s.copy(showHiddenFiles = it)) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { app.scanServer(app.currentServer() ?: return@FilledTonalButton) }) {
                        Text("重新扫描影库")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        Store.clearHistory()
                        app.notify("观看记录已清空")
                    }) { Text("清空观看记录") }
                }
            }

            SettingGroup("影片信息（可选）") {
                Text(
                    "填 TMDB API Key 后，详情页可一键拉取封面、评分、简介。留空则完全使用 NAS 里自带的 poster.jpg。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tmdb,
                    onValueChange = { tmdb = it },
                    label = { Text("TMDB API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    Store.setSettings(s.copy(tmdbKey = tmdb.trim()))
                    app.notify("已保存 Key")
                }) { Text("保存") }
            }

            SettingGroup("关于") {
                Text("云巢影音 NestPlayer ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "五种协议统一走本地流代理（127.0.0.1:${StreamProxy.port}），支持拖动进度、外挂字幕。\n" +
                        "数据全部保存在本机，不上传任何服务器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(30.dp))
            TextButton(onClick = { FsManager.closeAll() }) { Text("断开所有 NAS 连接") }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        ) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column {
        Text(title, fontSize = 13.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
