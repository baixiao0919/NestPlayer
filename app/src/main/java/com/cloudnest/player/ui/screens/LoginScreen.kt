package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.data.ProtocolType
import com.cloudnest.player.data.ServerConfig
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.FnLogin
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.theme.BrandCyan
import com.cloudnest.player.ui.theme.BrandGradient
import com.cloudnest.player.ui.theme.BrandViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NAS 账号登录：填地址 + 账号 + 密码，登录成功后自动建好服务器条目，
 * 视频与音乐都直接用这套凭据，不用再手填 SMB/WebDAV 参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var shareHint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FnLogin.Ok?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, start = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { app.pop() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ArrowBack, "返回", modifier = Modifier.size(18.dp))
                }
                Text(
                    "账号登录", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 顶部说明卡
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(BrandViolet.copy(alpha = 0.22f), BrandCyan.copy(alpha = 0.14f))))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(BrandGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Cloud, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("登录你的 NAS", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "填一次账号密码，视频和音乐都用它访问",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 11.5.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (result == null) {
                Field("地址", host, "192.168.1.10（可含端口）") { host = it }
                Spacer(Modifier.height(10.dp))
                Field("SMB 端口", port, "445") { port = it.filter { c -> c.isDigit() } }
                Spacer(Modifier.height(10.dp))
                Field("账号", user, "登录 NAS 用的账号") { user = it }
                Spacer(Modifier.height(10.dp))
                Field("密码", pass, "登录 NAS 用的密码", password = true) { pass = it }
                Spacer(Modifier.height(10.dp))
                Field("共享名（可留空自动识别）", shareHint, "例如 homes / video") { shareHint = it }

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.5.sp, lineHeight = 18.sp)
                }

                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(BrandGradient)
                        .clickable(enabled = !busy) {
                            if (busy) return@clickable
                            error = null
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching {
                                        FnLogin.probe(
                                            host, port.toIntOrNull() ?: 445, user, pass, shareHint
                                        )
                                    }
                                }
                                busy = false
                                r.onSuccess {
                                    result = it
                                    picked = it.shares.firstOrNull()
                                }.onFailure {
                                    error = "登录失败：${it.message ?: "网络或账号有问题"}"
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (busy) "登录验证中…" else "登录",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "只在本机保存账号密码；登录只做一次 SMB 认证，不会上传任何数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp
                )
            } else {
                Text("登录成功", color = BrandCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result!!.host}:${result!!.port} · ${result!!.user}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                Text("选择要访问的共享目录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                result!!.shares.forEach { name ->
                    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    val brush = if (name == picked) {
                        Brush.linearGradient(listOf(BrandViolet.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.2f)))
                    } else {
                        Brush.linearGradient(listOf(surface, surface))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(brush)
                            .clickable { picked = name }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (name == picked) Text("已选", color = BrandCyan, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(BrandGradient)
                        .clickable {
                            val r = result ?: return@clickable
                            val share = picked ?: return@clickable
                            val server = ServerConfig(
                                name = "飞牛 · " + r.host,
                                protocol = ProtocolType.SMB,
                                host = r.host,
                                port = r.port,
                                username = r.user,
                                password = pass,
                                basePath = share
                            )
                            Store.upsertServer(server)
                            app.notify("已添加服务器「${server.name.ifBlank { r.host }}」，视频和音乐都能用了")
                            app.backToHome()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("保存并使用", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "保存后可在「服务器」里改共享、加子目录；音乐页点右上角刷新即可扫歌。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 12.5.sp) },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
    }
}
