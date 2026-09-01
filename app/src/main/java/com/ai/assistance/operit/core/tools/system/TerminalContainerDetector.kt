package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
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
    /** 入口能力：该 rootfs 内是否检测到可用于进入容器的可执行文件。 */
    val entryCapability: EntryCapability = EntryCapability.UNKNOWN,
    /** 静态推导出的"实际会选用的入口模板"标签，UI 可直接展示。 */
    val entryTemplateLabel: String = "",
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

    /** rootfs 内入口文件的可用性（只是静态检测，不代表当前权限足够真的执行）。 */
    enum class EntryCapability {
        UNKNOWN,
        /** 连 /bin/sh 都没有，肯定无法进入。 */
        NO_SHELL,
        /** 只有 chroot 可用（需要 ROOT）。 */
        CHROOT_ONLY,
        /** unshare + /bin/sh 都在（需要 ROOT，优先用）。 */
        UNSHARE_AVAILABLE,
    }

    val isReadyForUse: Boolean
        get() = state == State.OK || state == State.READ_ONLY
}

/**
 * 运行期进入容器所需的权限环境检测结果。
 *
 * 用于设置页展示"当前首选的 Shell 执行器 + 是否已授予"，
 * 让用户能一眼区分"目录选对了但权限不够"和"目录就不对"两种情况。
 */
data class RuntimePermissionStatus(
    /** 当前首选的权限级别。 */
    val level: AndroidPermissionLevel,
    /** 给 UI 直接展示的级别标签。 */
    val levelLabel: String,
    /** 该级别下，执行器是否真正获得了授权。 */
    val granted: Boolean,
    /** 未授权时的原因（例如 Shizuku 未启动、Root 未授予等），可能为空。 */
    val reason: String?,
) {
    /** 中文 UI 友好的级别名称。 */
    val levelLabelChinese: String
        get() = when (level) {
            AndroidPermissionLevel.ROOT -> "ROOT（su）"
            AndroidPermissionLevel.DEBUGGER -> "DEBUGGER（Shizuku）"
            AndroidPermissionLevel.ADMIN -> "ADMIN（设备所有者）"
            AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY（无障碍）"
            AndroidPermissionLevel.STANDARD -> "STANDARD（普通应用）"
        }
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

    /**
     * 检测运行期进入容器的权限环境：当前首选 ShellExecutor 的权限级别，以及该级别下
     * 是"已授予"还是"拒绝/未启动"。仅用于 UI 给用户更直观的提示，不参与容器目录本身
     * 的就绪判定（避免执行期权限检测与静态目录检测耦合过重）。
     */
    fun detectRuntimePermission(context: Context): RuntimePermissionStatus {
        val executor = runCatching {
            ShellExecutorFactory.getUserPreferredExecutor(context)
        }.getOrElse { error ->
            AppLogger.w(TAG, "getUserPreferredExecutor failed", error)
            return RuntimePermissionStatus(
                level = AndroidPermissionLevel.STANDARD,
                levelLabel = AndroidPermissionLevel.STANDARD.name,
                granted = false,
                reason = "获取首选执行器失败：${error.message}",
            )
        }
        val permStatus = runCatching { executor.hasPermission() }.getOrElse { error ->
            ShellExecutor.PermissionStatus.denied("hasPermission 异常：${error.message}")
        }
        val level = executor.getPermissionLevel()
        return RuntimePermissionStatus(
            level = level,
            levelLabel = level.name,
            granted = permStatus.granted,
            reason = permStatus.reason,
        )
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

        // 入口能力静态检测：只读 rootfs 里有没有 unshare/chroot/sh 用于真正进入容器。
        val entryCapability = detectEntryCapability(dir)
        // 同时给出"运行期会实际选择哪条模板"的可读名称（和 ContainerEntry.probeEntryTemplate 的
        // 选择逻辑保持一致），用于设置页直接展示。
        val entryTemplateLabel = computeEntryTemplateLabel(
            rootDirFile = dir,
            entryCapability = entryCapability,
        )

        val state = when {
            entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL ->
                TerminalContainerStatus.State.MISSING
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
                TerminalContainerStatus.State.MISSING -> {
                    if (entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL) {
                        append("容器目录里找不到 /bin/sh：$dirPath。")
                        append("请确认这是 Droidspaces 构建的完整 Linux rootfs（不是挂载点父目录）。")
                    } else {
                        append("目录存在但看起来不像 Linux rootfs：$dirPath（缺少 ${(expectedMarkers - presentMarkers.toSet()).joinToString()}）。")
                    }
                }
                else -> append("$state: $dirPath")
            }
            // 入口能力对 OK/CONFLICT/READ_ONLY 也给一段提示，方便 UI/向导把"为什么命令跑不起来"直接说出来。
            val entryHint = when (entryCapability) {
                TerminalContainerStatus.EntryCapability.NO_SHELL ->
                    "无法进入容器：缺少 /bin/sh。"
                TerminalContainerStatus.EntryCapability.CHROOT_ONLY ->
                    "入口方式：chroot（需要 ROOT/Shizuku debugger 权限）。"
                TerminalContainerStatus.EntryCapability.UNSHARE_AVAILABLE ->
                    "入口方式：unshare（需要 ROOT/Shizuku debugger 权限）。"
                TerminalContainerStatus.EntryCapability.UNKNOWN -> ""
            }
            if (entryHint.isNotEmpty()) append("｜").append(entryHint)
        }

        val details = buildString {
            append("rootDir=").append(dirPath)
            append(" markers=").append(presentMarkers.joinToString(","))
            append(" canRead=").append(canRead)
            append(" canWrite=").append(canWrite)
            append(" entryCapability=").append(entryCapability.name)
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
            entryCapability = entryCapability,
            entryTemplateLabel = entryTemplateLabel,
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

    /**
     * 静态检测该 rootfs 目录里可用的入口工具。
     *
     * 只看文件是否存在（不看是否有权限真的执行）；权限是否够用由 Terminal 实际执行时
     * 再报错（"Operation not permitted" 等）。这样设置页就能清楚告诉用户"缺什么"。
     */
    private fun detectEntryCapability(rootDir: File): TerminalContainerStatus.EntryCapability {
        val has = { rel: String ->
            val f = File(rootDir, rel.removePrefix("/"))
            runCatching { f.exists() }.getOrDefault(false)
        }
        if (!has("/bin/sh")) return TerminalContainerStatus.EntryCapability.NO_SHELL
        if (has("/bin/unshare")) return TerminalContainerStatus.EntryCapability.UNSHARE_AVAILABLE
        if (has("/usr/sbin/chroot") || has("/bin/chroot")) {
            return TerminalContainerStatus.EntryCapability.CHROOT_ONLY
        }
        // 有 sh 但找不到 unshare/chroot。这种情况下我们无法真的"切换根"，
        // 标记为 NO_SHELL 太重了；退化为 CHROOT_ONLY 语义（命令执行阶段会给出缺工具
        // 的明确错误），避免检测器和执行器两套判定差得太多。
        return TerminalContainerStatus.EntryCapability.CHROOT_ONLY
    }

    /**
     * 按照 [ContainerEntry.probeEntryTemplate] 同样的选择逻辑，静态生成一个 UI 可读的
     * "运行期实际入口模板"标签，这样设置页不用去构造 Terminal/会话，也能准确告诉用户
     * 最终会用 unshare 还是 chroot。
     */
    private fun computeEntryTemplateLabel(
        rootDirFile: File,
        entryCapability: TerminalContainerStatus.EntryCapability,
    ): String {
        if (entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL) {
            return "错误提示（缺入口文件）"
        }
        val hostHas = { name: String ->
            val candidates = System.getenv("PATH")
                .orEmpty()
                .split(':')
                .filter { it.isNotEmpty() }
                .plus(
                    listOf(
                        "/system/bin",
                        "/system/xbin",
                        "/vendor/bin",
                        "/sbin",
                        "/product/bin",
                        "/apex/com.android.runtime/bin",
                    )
                )
                .distinct()
            candidates.any { dir ->
                runCatching {
                    val f = File(dir, name)
                    f.isFile && f.canExecute()
                }.getOrDefault(false)
            }
        }
        val containerHas = { rel: String ->
            val f = File(rootDirFile, rel.removePrefix("/"))
            runCatching { f.exists() && f.canExecute() || (f.isFile && f.canRead()) }.getOrDefault(false)
        }
        if (containerHas("/bin/unshare") || hostHas("unshare")) {
            val bin = if (hostHas("unshare")) "宿主 unshare" else "容器 /bin/unshare"
            return "unshare namespace 根切换（$bin）"
        }
        val chrootBin = when {
            hostHas("chroot") -> "宿主 chroot"
            containerHas("/usr/sbin/chroot") -> "容器 /usr/sbin/chroot"
            containerHas("/bin/chroot") -> "容器 /bin/chroot"
            else -> null
        }
        if (chrootBin != null && containerHas("/bin/sh")) {
            return "chroot（$chrootBin）"
        }
        return "入口未就绪（将以错误提示形式返回）"
    }
}
