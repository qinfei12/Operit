package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.TerminalContainerDetector
import com.ai.assistance.operit.core.tools.system.TerminalContainerStatus
import com.ai.assistance.operit.data.preferences.TerminalContainerPreferences
import com.ai.assistance.operit.data.preferences.terminalContainerPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.launch

private const val TAG = "TerminalContainerSettings"

/**
 * 终端容器（Linux rootfs）设置页。
 *
 * 目标：让用户自由选择一个已存在的 Linux rootfs 目录（通常来自 Droidspaces 的
 * /mnt/Droidspaces/<distro>），作为 PathMapper 与 Terminal 的容器根目录。
 *
 * 页面包含：
 * 1. 路径输入框（可手输）
 * 2. 一键扫描 /mnt/Droidspaces 子目录的快捷按钮
 * 3. 实时检测结果（缺失/无权限/OK/只读/冲突）以及"重新检测"按钮
 * 4. 冲突时显示旧内置容器残留路径，并提示用户手动清理（只提示不自动删）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalContainerSettingsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember(context) { terminalContainerPreferences }

    // 编辑态；从 prefs flow 初始化，但不实时绑定，用户点击保存后才落盘。
    var rootDirInput by remember { mutableStateOf("") }

    // 是否初始化过一次（避免每次流更新把用户正在编辑的输入覆盖掉）。
    var initialized by remember { mutableStateOf(false) }

    // 保存后最新落盘的值（用于"当前值 vs 输入"的差异判断）。
    var savedRootDir by remember { mutableStateOf("") }

    var detection by remember {
        mutableStateOf<TerminalContainerStatus?>(null)
    }

    var scanning by remember { mutableStateOf(false) }
    var scannedCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCandidateDialog by remember { mutableStateOf(false) }

    // 一次性从 flow 初始化输入框 + 做一次检测。
    LaunchedEffect(prefs) {
        prefs.containerRootDirFlow.collect { current ->
            savedRootDir = current
            detection = TerminalContainerDetector.detectFor(context, current)
            if (!initialized) {
                rootDirInput = current.ifBlank { TerminalContainerPreferences.DEFAULT_CONTAINER_ROOT_DIR }
                initialized = true
            }
        }
    }

    fun rerunDetection(rootDir: String) {
        detection = TerminalContainerDetector.detectFor(context, rootDir)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "终端容器目录",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scanning = true
                            scannedCandidates =
                                TerminalContainerDetector.scanDroidspacesCandidates()
                            scanning = false
                            showCandidateDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = if (scanning) Icons.Default.Search else Icons.Default.FolderOpen,
                            contentDescription = "扫描 /mnt/Droidspaces",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "不再内置 Ubuntu/proot 容器，请指定 Droidspaces（或其他方式）构建的 Linux rootfs 目录。默认扫描 /mnt/Droidspaces/ 下的发行版目录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== 路径输入 =====
            OutlinedTextField(
                value = rootDirInput,
                onValueChange = { value ->
                    rootDirInput = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("容器根目录（rootfs）") },
                supportingText = {
                    val det = detection
                    if (det?.state == TerminalContainerStatus.State.NOT_CONFIGURED &&
                        savedRootDir.isEmpty()
                    ) {
                        Text("默认建议：在下拉中选一个 /mnt/Droidspaces/<发行版>/ 目录")
                    } else {
                        Text("示例：/mnt/Droidspaces/ubuntu")
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { rerunDetection(rootDirInput) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新检测")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { rerunDetection(rootDirInput) }
                ),
            )

            // ===== 快捷按钮：立即检测 / 使用默认值 / 保存 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { rerunDetection(rootDirInput) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("检测当前输入")
                }

                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val normalized =
                                    TerminalContainerPreferences.DEFAULT_CONTAINER_ROOT_DIR
                                prefs.setContainerRootDir(normalized)
                                rootDirInput = normalized
                            }.onFailure { error ->
                                AppLogger.e(TAG, "apply default failed", error)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("恢复默认 /mnt/Droidspaces/")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { prefs.setContainerRootDir(rootDirInput.trim()) }
                                .onSuccess {
                                    savedRootDir = rootDirInput.trim()
                                    rerunDetection(savedRootDir)
                                }
                                .onFailure { error ->
                                    AppLogger.e(TAG, "save container root failed", error)
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存")
                }

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            // 清空 -> 强制 NOT_CONFIGURED，下次 PathMapper 会报错引导用户。
                            runCatching { prefs.setContainerRootDir("") }
                                .onSuccess {
                                    savedRootDir = ""
                                    rootDirInput = ""
                                    rerunDetection("")
                                }
                                .onFailure { error ->
                                    AppLogger.e(TAG, "clear container root failed", error)
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空未配置")
                }
            }

            // ===== 检测结果卡片 =====
            DetectionCard(
                status = detection,
                onRescan = { rerunDetection(savedRootDir.ifBlank { rootDirInput }) },
            )
        }
    }

    if (showCandidateDialog) {
        CandidatePickerDialog(
            candidates = scannedCandidates,
            onDismiss = { showCandidateDialog = false },
            onPick = { candidate ->
                rootDirInput = candidate
                rerunDetection(candidate)
                showCandidateDialog = false
            },
        )
    }
}

@Composable
private fun DetectionCard(
    status: TerminalContainerStatus?,
    onRescan: () -> Unit,
) {
    if (status == null) {
        Card(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
        ) {
            Text(
                text = "正在检测容器目录…",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val (icon, tint, title) = when (status.state) {
        TerminalContainerStatus.State.OK ->
            Triple(Icons.Default.Check, Color(0xFF2E7D32), "✅ 容器目录可用")
        TerminalContainerStatus.State.READ_ONLY ->
            Triple(Icons.Default.Warning, Color(0xFFBF6F00), "⚠ 容器目录只读")
        TerminalContainerStatus.State.CONFLICT ->
            Triple(Icons.Default.Warning, Color(0xFFBF6F00), "⚠ 检测到旧内置容器残留")
        TerminalContainerStatus.State.NOT_CONFIGURED ->
            Triple(Icons.Default.Warning, Color(0xFFBF6F00), "⚠ 尚未配置容器目录")
        TerminalContainerStatus.State.MISSING ->
            Triple(Icons.Default.Error, Color(0xFFC62828), "❌ 目录不存在或不是 Linux rootfs")
        TerminalContainerStatus.State.NO_PERMISSION ->
            Triple(Icons.Default.Error, Color(0xFFC62828), "❌ 无法访问容器目录（权限不足）")
    }

    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRescan) { Text("重新检测") }
            }

            Text(
                text = status.userMessage,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (status.rootDir.isNotEmpty()) {
                Text(
                    text = "当前配置：${status.rootDir}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status.conflictingPaths.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "疑似冲突的旧路径：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.conflictingPaths.forEach { path ->
                    Text(
                        text = "• $path",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "提示：仅提示，不自动删除。请确认上述目录不再使用后手动清理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidatePickerDialog(
    candidates: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 Droidspaces 发行版目录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (candidates.isEmpty()) {
                    Text(
                        text = "未在 /mnt/Droidspaces/ 下找到可用子目录。\n请先在 Droidspaces APP 中构建发行版，或手动粘贴绝对路径。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "已扫描到 ${candidates.size} 个候选：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    candidates.forEach { candidate ->
                        AssistChip(
                            onClick = { onPick(candidate) },
                            label = {
                                Text(
                                    text = candidate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    }
}
