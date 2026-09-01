package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.data.preferences.TerminalContainerPreferences
import com.ai.assistance.operit.data.preferences.terminalContainerPreferences
import java.io.File

/**
 * 终端容器目录检测结果。
 *
 * 只做"提示而非崩溃"，因此所有状态都包含人类可读的原因，UI/Wizard 可以直接消费。
 * 这里的 OK 表示 "根目录看起来像一个 Linux rootfs"，不是严格意义上能
 * 成功 chroot 进去——后者留给真正执行命令时的错误处理。
 */
data class TerminalContainerStatus(
    val state: State,
    /** 根目录的实际值（规范化后，可能为空字符串） */
    val rootDir: String,
    /** 给用户看的提示文本（中文，方便 UI 直接用） */
    val userMessage: String,
    /** 供调试/log 的细节 */
    val details: String,
    /** state=CONFLICT 时可能携带的疑似内置容器路径 */
    val conflictingPaths: List<String> = emptyList(),
) {
    enum class State {
        /** 未配置：用户还没填路径 / 填了空字符串。 */
        NOT_CONFIGURED,
        /** 配置的目录不存在。 */
        MISSING,
        /** 存在但当前权限读不到内容。 */
        NO_PERMISSION,
        /** 像一个合法 Linux rootfs（存在 /bin /etc 等），但写入受限。 */
        READ_ONLY,
        /** 看起来是一个合法 Linux rootfs。 */
        OK,
        /** 疑似冲突：检测到旧的内置 rootfs 路径仍然存在，需要提醒用户。 */
        CONFLICT,
    }

    val isReadyForUse: Boolean
        get() = state == State.OK || state == State.READ_ONLY
}

/**
 * 终端容器（rootfs）检测器。
 *
 * 检测原则：
 * - 仅做静态检查（文件/目录存在性、可读性、基本可写性）。
 * - 不做兜底；NOT_CONFIGURED/MISSING/NO_PERMISSION 都是明确的状态。
 * - 检测到旧的内置容器路径（proot-distro/installed-rootfs/ubuntu）仍然存在时标记 CONFLICT，
 *   用 UI 提示用户避免混用。
 */
object TerminalContainerDetector {
    private const val TAG = "TerminalContainerDetector"

    /** 默认 /mnt/Droidspaces 下常见子目录（Droidspaces 的发行版命名）。 */
    val KNOWN_DROIDSPACES_DISTRO_DIRS = listOf(
        "ubuntu",
        "debian",
        "arch",
        "fedora",
        "alpine",
        "kali",
        "manjaro",
        "opensuse",
        "void",
    )

    suspend fun detect(context: Context): TerminalContainerStatus {
        val prefs: TerminalContainerPreferences =
            runCatching { terminalContainerPreferences }
                .getOrElse {
                    val msg = "TerminalContainerPreferences 未初始化，无法检测容器目录"
                    AppLogger.w(TAG, msg)
                    return TerminalContainerStatus(
                        state = TerminalContainerStatus.State.NOT_CONFIGURED,
                        rootDir = "",
                        userMessage = "终端容器目录尚未完成初始化，请稍候再试",
                        details = msg,
                    )
                }
        val rootDir = prefs.getContainerRootDir()
        return detectFor(context, rootDir)
    }

    fun detectFor(context: Context, rawRootDir: String?): TerminalContainerStatus {
        val rootDir = rawRootDir?.trim().orEmpty()
        if (rootDir.isEmpty()) {
            return TerminalContainerStatus(
                state = TerminalContainerStatus.State.NOT_CONFIGURED,
                rootDir = "",
                userMessage = "尚未选择终端容器目录，请在设置中配置（默认推荐 /mnt/Droidspaces/ 下的发行版目录）",
                details = "container_root_dir is empty",
            )
        }

        val dir = File(rootDir)
        val dirPath = dir.absolutePath

        if (!dir.exists()) {
            return TerminalContainerStatus(
                state = TerminalContainerStatus.State.MISSING,
                rootDir = rootDir,
                userMessage = "容器目录不存在：$dirPath，请确认路径拼写或在 Droidspaces 中先构建对应发行版",
                details = "directory does not exist",
            )
        }

        if (!dir.isDirectory) {
            return TerminalContainerStatus(
                state = TerminalContainerStatus.State.MISSING,
                rootDir = rootDir,
                userMessage = "指定路径不是目录：$dirPath",
                details = "path is not a directory",
            )
        }

        val canRead = runCatching { dir.canRead() && dir.listFiles() != null }.getOrDefault(false)
        if (!canRead) {
            return TerminalContainerStatus(
                state = TerminalContainerStatus.State.NO_PERMISSION,
                rootDir = rootDir,
                userMessage = "容器目录无法读取：$dirPath。请确认 Operit 是否对 /mnt/Droidspaces 有访问权限（root/Shizuku/存储权限）",
                details = "cannot read directory contents",
            )
        }

        // 基础 Linux rootfs 标志：/bin /etc /usr 中存在两个以上。
        val expectedMarkers = listOf("bin", "etc", "usr")
        val presentMarkers = expectedMarkers.filter { marker ->
            runCatching { File(dir, marker).exists() }.getOrDefault(false)
        }
        val looksLikeRootfs = presentMarkers.size >= 2

        // 写权限探测：不做写入兜底，仅在无法写入时标记 READ_ONLY 并提示。
        val canWrite = runCatching {
            val probe = File(dir, ".operit_write_probe_${System.nanoTime()}")
            val created = try {
                probe.createNewFile()
            } catch (_: Throwable) {
                false
            }
            if (created) runCatching { probe.delete() }
            created
        }.getOrDefault(false)

        // 冲突检测：旧的内置 Ubuntu 路径是否仍然残留。
        val legacyPaths = collectLegacyRootfsPaths(context)
        val conflict = legacyPaths.isNotEmpty()

        val state = when {
            conflict && looksLikeRootfs && canWrite -> TerminalContainerStatus.State.CONFLICT
            conflict && looksLikeRootfs -> TerminalContainerStatus.State.CONFLICT
            !looksLikeRootfs -> TerminalContainerStatus.State.MISSING
            canWrite -> TerminalContainerStatus.State.OK
            else -> TerminalContainerStatus.State.READ_ONLY
        }

        val userMessage = buildString {
            when (state) {
                TerminalContainerStatus.State.OK ->
                    append("容器目录校验通过：$dirPath")
                TerminalContainerStatus.State.READ_ONLY ->
                    append("容器目录可读但无法写入：$dirPath。文件工具可读取，但写入/解压/安装命令会失败。")
                TerminalContainerStatus.State.CONFLICT -> {
                    append("检测到旧内置容器路径残留（${legacyPaths.size} 个），")
                    append("可能导致终端会话仍进入内置环境。请手动删除旧 rootfs 或确认不再混用。")
                }
                TerminalContainerStatus.State.MISSING ->
                    append("目录存在但看起来不像 Linux rootfs：$dirPath（缺少 ${(expectedMarkers - presentMarkers.toSet()).joinToString()}）。")
                else -> append("$state: $dirPath")
            }
        }

        val details = buildString {
            append("rootDir=").append(dirPath)
            append(" markers=").append(presentMarkers.joinToString(","))
            append(" canRead=").append(canRead)
            append(" canWrite=").append(canWrite)
            if (legacyPaths.isNotEmpty()) {
                append(" legacyPaths=").append(legacyPaths.joinToString(";"))
            }
        }

        AppLogger.i(TAG, "detect: state=$state $details")
        return TerminalContainerStatus(
            state = state,
            rootDir = dirPath,
            userMessage = userMessage,
            details = details,
            conflictingPaths = legacyPaths,
        )
    }

    /**
     * 扫描 /mnt/Droidspaces/ 下的候选子目录（KNOW_DROIDSPACES_DISTRO_DIRS + 任何能进入且像 rootfs 的子目录）。
     * 返回绝对路径列表；空列表表示没有发现可用候选。
     */
    fun scanDroidspacesCandidates(
        parentDir: String = TerminalContainerPreferences.DEFAULT_CONTAINER_ROOT_DIR,
    ): List<String> {
        val parent = File(parentDir)
        if (!parent.exists() || !parent.isDirectory) return emptyList()
        val subs = runCatching { parent.listFiles()?.asList().orEmpty() }.getOrDefault(emptyList())
        return subs
            .asSequence()
            .filter { it.isDirectory }
            .filter { sub ->
                // 至少能读到
                val readable = runCatching { sub.canRead() }.getOrDefault(false)
                if (!readable) return@filter false
                val markers = listOf("bin", "etc", "usr").count { marker ->
                    runCatching { File(sub, marker).exists() }.getOrDefault(false)
                }
                markers >= 2 || sub.name in KNOWN_DROIDSPACES_DISTRO_DIRS
            }
            .map { it.absolutePath }
            .sorted()
            .toList()
    }

    private fun collectLegacyRootfsPaths(context: Context): List<String> {
        val candidates = listOfNotNull(
            // 旧 PathMapper 写死的内置路径
            runCatching {
                File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
            }.getOrNull(),
            // 常见的 proot-distro 全部 rootfs 目录
            runCatching {
                File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs")
            }.getOrNull(),
        )
        return candidates.filter { runCatching { it.exists() }.getOrDefault(false) }
            .map { it.absolutePath }
    }
}
