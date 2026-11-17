package com.cloudnest.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudnest.player.data.Store
import com.cloudnest.player.net.MediaFiles
import com.cloudnest.player.ui.AppState
import com.cloudnest.player.ui.EmptyState
import com.cloudnest.player.ui.RemoteImage
import com.cloudnest.player.ui.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: AppState) {
    val data by Store.data.collectAsState()
    var query by remember { mutableStateOf("") }
    val results = remember(query, data.library) {
        if (query.isBlank()) emptyList()
        else data.library.filter {
            it.title.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
        }.take(200)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 12.dp),
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
                Icon(Icons.Rounded.ArrowBack, "返回", modifier = Modifier.size(18.dp))
            }
            Text(
                "搜索影库",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("片名 / 文件名关键词", fontSize = 13.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
        }

        if (query.isBlank()) {
            EmptyState("想找哪部片？", "输入片名、关键词或文件名的任意片段")
            return@Column
        }
        if (results.isEmpty()) {
            EmptyState("没找到", "换个关键词试试，或先扫描影库")
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { app.push(Screen.Detail(item.id)) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = 46.dp, height = 66.dp)) {
                        RemoteImage(
                            serverId = item.serverId,
                            path = item.posterPath,
                            modifier = Modifier.fillMaxSize(),
                            fallbackLabel = item.title,
                            corner = 12.dp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                item.year?.let { append("$it · ") }
                                if (item.episode != null) append("第${item.episode}集 · ")
                                append(MediaFiles.formatSize(item.size))
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
