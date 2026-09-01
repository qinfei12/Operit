package com.ai.assistance.operit.ui.features.demo.wizards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.TerminalContainerStatus

@Composable
fun OperitTerminalWizardCard(
    isPnpmInstalled: Boolean,
    isPipInstalled: Boolean,
    isEnvironmentReady: Boolean,
    showWizard: Boolean,
    onToggleWizard: (Boolean) -> Unit,
    onOpenTerminalScreen: () -> Unit,
    onOpenContainerSettings: () -> Unit = {},
    containerStatus: TerminalContainerStatus? = null,
    // 保留旧参数以保持兼容性
    isInstalled: Boolean = false,
    installedVersion: String? = null,
    latestVersion: String? = null,
    releaseNotes: String? = null,
    updateNeeded: Boolean = false,
    downloadUrl: String? = null,
    onInstall: () -> Unit = {},
    onUpdate: () -> Unit = {},
    onOpen: () -> Unit = {},
    onDownloadFromUrl: (String) -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.configure_terminal_environment),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                TextButton(
                    onClick = { onToggleWizard(!showWizard) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(if (showWizard) R.string.wizard_collapse else R.string.wizard_expand),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== 容器目录状态（先显示容器本身是否 OK，再显示 pnpm/pip） =====
            val containerState = containerStatus?.state
            val containerNotReady = containerStatus != null && !containerStatus.isReadyForUse
            val containerConflict = containerState == TerminalContainerStatus.State.CONFLICT
            if (containerStatus != null) {
                val containerIcon = when (containerState) {
                    TerminalContainerStatus.State.OK -> Icons.Default.CheckCircle
                    TerminalContainerStatus.State.READ_ONLY -> Icons.Default.Info
                    TerminalContainerStatus.State.CONFLICT -> Icons.Default.Warning
                    else -> Icons.Default.Warning
                }
                val containerTint = when (containerState) {
                    TerminalContainerStatus.State.OK -> MaterialTheme.colorScheme.tertiary
                    TerminalContainerStatus.State.READ_ONLY -> MaterialTheme.colorScheme.primary
                    TerminalContainerStatus.State.CONFLICT -> androidx.compose.ui.graphics.Color(0xFFBF6F00)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val containerTitle = when (containerState) {
                    TerminalContainerStatus.State.OK -> "容器目录已就绪"
                    TerminalContainerStatus.State.READ_ONLY -> "容器目录只读"
                    TerminalContainerStatus.State.CONFLICT -> "容器冲突：仍有旧内置路径残留"
                    TerminalContainerStatus.State.NOT_CONFIGURED -> "尚未选择终端容器目录"
                    TerminalContainerStatus.State.MISSING -> "容器目录不存在或不是 Linux rootfs"
                    TerminalContainerStatus.State.NO_PERMISSION -> "无法访问容器目录（权限不足）"
                    null -> "容器检测未运行"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = containerIcon,
                        contentDescription = null,
                        tint = containerTint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = containerTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = containerTint,
                    )
                }
                if (containerNotReady || containerConflict) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = containerStatus.userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (containerStatus.conflictingPaths.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    containerStatus.conflictingPaths.take(3).forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 环境状态信息
            val statusText = when {
                isEnvironmentReady -> stringResource(R.string.nodejs_pip_environment_ready)
                isPnpmInstalled && !isPipInstalled -> stringResource(R.string.pnpm_installed_need_pip)
                !isPnpmInstalled && isPipInstalled -> stringResource(R.string.pip_installed_need_pnpm)
                containerNotReady -> "请先完成容器目录配置，再检查 pnpm/pip。"
                else -> stringResource(R.string.need_configure_nodejs_pip)
            }
            
            val statusColor = when {
                isEnvironmentReady -> MaterialTheme.colorScheme.tertiary
                isPnpmInstalled || isPipInstalled -> MaterialTheme.colorScheme.primary
                containerNotReady -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isEnvironmentReady) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
            }
            
            // 详细环境状态显示
            if (!isEnvironmentReady) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // pnpm状态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPnpmInstalled) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isPnpmInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "pnpm",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPnpmInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // pip状态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPipInstalled) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isPipInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "pip",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPipInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 详细设置内容，仅在展开时显示
            AnimatedVisibility(visible = showWizard) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    if (!isEnvironmentReady) {
                        // 容器未就绪/冲突时，优先引导到「终端容器目录」设置页，而不是跳终端。
                        if (containerNotReady || containerConflict) {
                            Text(
                                text = "请先选择 Droidspaces 构建的 Linux rootfs。默认目录位于 /mnt/Droidspaces/<发行版>/。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = onOpenContainerSettings,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("前往「终端容器目录」设置", fontSize = 14.sp)
                            }
                        } else {
                            Text(
                                stringResource(R.string.terminal_environment_setup_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = onOpenTerminalScreen,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.go_to_terminal_config), fontSize = 14.sp)
                            }
                        }
                    } else {
                        Text(
                            stringResource(R.string.nodejs_pip_environment_configured),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = onOpenTerminalScreen,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.open_terminal), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
} 