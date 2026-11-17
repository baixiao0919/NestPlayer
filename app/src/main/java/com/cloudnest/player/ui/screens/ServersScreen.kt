package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.data.ProtocolType
import com.cloudnest.player.data.ServerConfig
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.DlnaDiscovery
import com.cloudnest.player.net.FsManager
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(app: AppState) {
    val data by Store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, start = 10.dp, end = 10.dp),
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
                    "服务器", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(com.cloudnest.player.ui.theme.BrandGradient)
                        .clickable { showAdd = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "支持 SMB（飞牛/群晖/威联通）、WebDAV、FTP、NFS v3、DLNA。\n同一台 NAS 可以加多个协议，各取所长。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(com.cloudnest.player.ui.theme.BrandGradient)
                        .clickable { app.openLogin() }
                        .padding(14.dp)
                ) {
                    Text("NAS 账号登录", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "填地址 + 账号 + 密码，自动识别共享并建好服务器，视频和音乐共用一套凭据",
                        color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }
            items(data.servers, key = { it.id }) { s ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            app.activeServerId = s.id
                            app.push(Screen.Browse(s.id, "", s.name.ifBlank { s.defaultName() }))
                        }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s.protocol.name.take(2),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { s.defaultName() }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${s.protocol.label} · ${s.hostLabel}" +
                                    if (s.basePath.isNotBlank()) " · ${s.basePath}" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    s.lastScan == 0L -> "未扫描"
                                    else -> "上次扫描 " + android.text.format.DateUtils.getRelativeTimeSpanString(s.lastScan)
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (testing == s.id) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    testing = s.id
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching { FsManager.create(s).use { it.list("") .size } }
                                    }
                                    testing = null
                                    r.onSuccess { app.notify("连接成功，根目录 ${it} 项") }
                                        .onFailure { app.notify("连接失败：${it.message}") }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "测试连接")
                            }
                            IconButton(onClick = { editing = s }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑")
                            }
                            IconButton(onClick = {
                                FsManager.invalidate(s.id)
                                Store.deleteServer(s.id)
                                app.notify("已删除")
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = {
                            app.activeServerId = s.id
                            app.scanServer(s, s.libraryPaths)
                        }, shape = RoundedCornerShape(8.dp)) { Text("扫描影库", fontSize = 12.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            app.activeServerId = s.id
                            app.push(Screen.Browse(s.id, "", s.name.ifBlank { s.defaultName() }))
                        }, shape = RoundedCornerShape(8.dp)) { Text("浏览文件", fontSize = 12.sp) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("添加服务器") }
            }
        }
    }

    if (showAdd || editing != null) {
        ServerEditorDialog(
            initial = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { cfg ->
                Store.upsertServer(cfg)
                FsManager.invalidate(cfg.id)
                app.activeServerId = cfg.id
                showAdd = false
                editing = null
                app.notify("已保存")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditorDialog(
    initial: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    var cfg by remember { mutableStateOf(initial ?: ServerConfig()) }
    val scope = rememberCoroutineScope()
    var discovering by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<DlnaDiscovery.Device>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加服务器" else "编辑服务器") },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                ProtocolPicker(cfg.protocol) { p ->
                    cfg = cfg.copy(protocol = p, port = 0)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = cfg.name,
                    onValueChange = { cfg = cfg.copy(name = it) },
                    label = { Text("名称（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cfg.host,
                    onValueChange = { cfg = cfg.copy(host = it) },
                    label = {
                        Text(
                            when (cfg.protocol) {
                                ProtocolType.WEBDAV -> "主机或完整地址（如 https://nas:5006）"
                                else -> "主机 IP 或域名"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (cfg.protocol != ProtocolType.DLNA) {
                    OutlinedTextField(
                        value = if (cfg.port == 0) "" else cfg.port.toString(),
                        onValueChange = { v ->
                            cfg = cfg.copy(port = v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                        },
                        label = { Text("端口（默认 ${cfg.protocol.defaultPort}）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (cfg.protocol != ProtocolType.DLNA && cfg.protocol != ProtocolType.NFS) {
                    OutlinedTextField(
                        value = cfg.username,
                        onValueChange = { cfg = cfg.copy(username = it) },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cfg.password,
                        onValueChange = { cfg = cfg.copy(password = it) },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = cfg.basePath,
                    onValueChange = { cfg = cfg.copy(basePath = it) },
                    label = {
                        Text(
                            when (cfg.protocol) {
                                ProtocolType.SMB -> "共享名/子目录，如 video 或 video/Movies（必填）"
                                ProtocolType.WEBDAV -> "起始路径，如 /dav 或 /remote.php/dav/files/user"
                                ProtocolType.FTP -> "起始目录（留空为 /）"
                                ProtocolType.NFS -> "导出路径，如 /volume1/video"
                                ProtocolType.DLNA -> "容器 ID（一般留空）"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (cfg.protocol == ProtocolType.DLNA) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            discovering = true
                            DlnaDiscovery.discoverAsync(context) { list ->
                                devices = list
                                discovering = false
                            }
                        }, shape = RoundedCornerShape(8.dp)) {
                            Text(if (discovering) "正在搜索…" else "搜索局域网设备")
                        }
                    }
                    devices.forEach { d ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    cfg = cfg.copy(host = d.host, name = d.name, controlUrl = d.controlUrl, udn = d.udn)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(d.controlUrl, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!discovering && devices.isEmpty()) {
                        Text("没搜到设备？检查手机和 NAS 是否在同一 Wi‑Fi，部分路由器会屏蔽组播",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cfg.host.isNotBlank(),
                onClick = {
                    val final = cfg.copy(
                        name = cfg.name.ifBlank { cfg.defaultName() }
                    )
                    onSave(final)
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolPicker(selected: ProtocolType, onSelect: (ProtocolType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("协议") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProtocolType.entries.forEach { p ->
                DropdownMenuItem(text = { Text(p.label) }, onClick = {
                    onSelect(p)
                    open = false
                })
            }
        }
    }
}
