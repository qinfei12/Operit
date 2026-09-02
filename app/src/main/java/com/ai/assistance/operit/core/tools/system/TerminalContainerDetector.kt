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
    /**
     * 从 rootDir 静态反推得到的"Droidspaces 容器名"候选：
     *   - 当 rootDir 形如 `{DROIDSPACES_PARENT}/<name>` 且 `<name>` 像发行版时 → `<name>`
     *   - 当 rootDir 内检测到 rootfs.img（Sparse Image / ext4 镜像）时也启用
     * ContainerEntry 会优先用这个名字调用 `droidspaces --name=... run`。
     * 空字符串表示未能识别。
     */
    val droidspacesContainerName: String = "",
    /** `/data/local/Droidspaces/bin/droidspaces` 是否可用（宿主有这个二进制且可读）。 */
    val droidspacesCliAvailable: Boolean = false,
    /** true = 配置的目录里不含有 etc/usr/var/bin 等 rootfs marker，但有
     * `rootfs.img` 或容器目录结构与 Droidspaces Sparse Image 布局一致。
     * 这种情况下必须通过 Droidspaces CLI 进入，unshare/chroot 直接用会失败。 */
    val droidspacesImageModeOnly: Boolean = false,
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

    /**
     * 默认 /mnt/Droidspaces 下常见子目录（Droidspaces 的发行版命名）。
     *
     * 注意两点：
     *   1) Droidspaces 允许用户在安装向导里给容器"名字"自由发挥，
     *      所以扫描策略是"先列全部子目录，再按 rootfs marker 判断"，
     *      这里的 KNOWN_* 只做"名字像但 marker 不全时也放行"的兜底。
     *   2) Ubuntu 新版本命名通常不带空格，但用户可能用 Ubuntu26 / Ubuntu_26.04 这类写法，
     *      因此除了精确列表，也做一个"前缀小写匹配 + 后缀可带版本号"的模糊判断。
     */
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
    private val KNOWN_DROIDSPACES_PREFIXES = listOf(
        "ubuntu",
        "debian",
        "arch",
        "fedora",
        "alpine",
        "kali",
        "manjaro",
        "opensuse",
        "suse",
        "void",
    )

    /** Droidspaces 宿主端二进制位置（Android 安装向导里 Atomic Installation 会装到这里）。 */
    const val DROIDSPACES_HOST_BIN = "/data/local/Droidspaces/bin/droidspaces"
    /** Droidspaces 默认把所有容器（目录或镜像）都放在这一级目录下面。 */
    const val DROIDSPACES_PARENT_DIR = "/mnt/Droidspaces"
    /** Sparse Image 模式下目录内常见的文件名（app 自动命名，也可能用户自定义）。 */
    private val DROIDSPACES_IMG_FILE_NAMES = setOf(
        "rootfs.img",
        "rootfs.sparse.img",
        "rootfs.ext4.img",
        "ubuntu.img",
        "debian.img",
        "arch.img",
        "alpine.img",
        "fedora.img",
        "kali.img",
        "manjaro.img",
        "opensuse.img",
        "void.img",
    )

    private fun looksLikeKnownDistroName(dirName: String): Boolean {
        val lower = dirName.lowercase()
        if (lower in KNOWN_DROIDSPACES_DISTRO_DIRS) return true
        return KNOWN_DROIDSPACES_PREFIXES.any { prefix ->
            lower.startsWith(prefix) &&
                lower.removePrefix(prefix).all { ch -> ch.isDigit() || ch == '.' || ch == '-' || ch == '_' }
        }
    }

    /**
     * 从一个 rootDir 路径推导"Droidspaces 容器名"候选。
     * 规则：当 rootDir 父目录是 /mnt/Droidspaces 时，basename 就是容器名（用户在向导里配置的名字）。
     * 这对目录型和稀疏镜像型都成立。
     */
    internal fun inferDroidspacesContainerName(rootDir: String): String {
        val trimmed = rootDir.trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val parent = trimmed.substringBeforeLast('/')
        val base = trimmed.substringAfterLast('/')
        if (parent != DROIDSPACES_PARENT_DIR.trimEnd('/') && parent != DROIDSPACES_PARENT_DIR) {
            // 不是直接挂在 Droidspaces 父目录下——也允许容器名本身看起来像发行版时
            // 返回 basename（例如用户自己改成 /data/mnt/Ubuntu26，依然可用）。
            if (looksLikeKnownDistroName(base)) return base
            return ""
        }
        return base
    }

    /** 是否检测到宿主端有 droidspaces 二进制（不校验可执行位，避免非 root 误判）。 */
    fun isDroidspacesCliAvailable(): Boolean {
        val f = File(DROIDSPACES_HOST_BIN)
        return runCatching { f.isFile && f.length() > 100_000L }.getOrDefault(false) ||
            runCatching { f.exists() }.getOrDefault(false)
    }

    /** 判断一个目录是不是"Droidspaces 稀疏镜像模式"：不含 rootfs 标志目录，但含有 .img 文件。 */
    private fun isDroidspacesImageModeDir(dir: File): Boolean {
        val subs = runCatching { dir.listFiles()?.asList().orEmpty() }.getOrDefault(emptyList())
        val noRootfsMarkers = listOf("etc", "usr", "var", "bin").none {
            runCatching { File(dir, it).exists() }.getOrDefault(false)
        }
        val hasImage = subs.any { sub ->
            val name = sub.name
            if (!runCatching { sub.isFile }.getOrDefault(false)) return@any false
            (name in DROIDSPACES_IMG_FILE_NAMES) ||
                (name.endsWith(".img") && looksLikeKnownDistroName(
                    name.removeSuffix(".img").removeSuffix(".sparse").removeSuffix(".ext4")
                ))
        }
        return noRootfsMarkers && hasImage
    }

    /**
     * Droidspaces 容器目录结构是"只要是 Droidspaces 管理的就允许选"。
     * 这里返回 true 的条件：
     *   ① 在 Droidspaces 父目录下 basename 看起来是发行版名 OR
     *   ② 目录里能看到 Sparse Image 的 .img 文件 OR
     *   ③ basename 本身是合法容器名（向导里用户自定义的）。
     *
     * detectFor 里会根据结果把 `looksLikeRootfs=false` 但
     * `droidspacesImageModeOnly=true` 的情况从 MISSING 放宽到 OK/READ_ONLY。
     */
    private fun isPlausibleDroidspacesContainerDir(dir: File): Boolean {
        val name = dir.name
        val parent = dir.parentFile?.absolutePath?.trimEnd('/')
        if (parent == DROIDSPACES_PARENT_DIR.trimEnd('/') && looksLikeKnownDistroName(name)) {
            return true
        }
        if (looksLikeKnownDistroName(name) &&
            runCatching { dir.canRead() }.getOrDefault(false)
        ) {
            return true
        }
        return isDroidspacesImageModeDir(dir)
    }


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

        // 基础 Linux rootfs 标志：
        // 新版系统（usrmerge：Ubuntu 23.10+、Debian 12+、Fedora 36+）里 /bin /sbin /lib
        // 都是指向 /usr 下对应目录的 symlink；而 /etc、/usr、/var 在所有发行版里都直接存在。
        // 因此 marker 集用 usrmerge 友好的集合，并要求命中 2/4 就算通过。
        val expectedMarkers = listOf("etc", "usr", "var", "bin")
        val presentMarkers = expectedMarkers.filter { marker ->
            // File#exists() 默认会跟随 symlink；这对 usrmerge 的 /bin -> /usr/bin 正好够用。
            runCatching { File(dir, marker).exists() }.getOrDefault(false)
        }
        val looksLikeRootfs = run {
            // 常规发行版：命中 2+ 个 marker
            val markerOk = presentMarkers.size >= 2
            // Droidspaces 的稀疏镜像/目录型 rootfs 会有一个显式的 init 入口
            // （官方 CLI `--rootfs=PATH` 要求 rootfs 含 `/sbin/init`）。
            val hasInit = runCatching {
                File(dir, "sbin/init").exists() ||
                    File(dir, "init").exists()
            }.getOrDefault(false)
            // 容器名字本身就是发行版名（如 Ubuntu26），且至少有一个关键 marker，也放行
            val nameLooksLikeDistro =
                looksLikeKnownDistroName(dir.name) && presentMarkers.isNotEmpty()
            markerOk || hasInit || nameLooksLikeDistro
        }

        // Droidspaces 模式识别：即使 looksLikeRootfs=false，如果目录看起来像
        // 「Droidspaces Sparse Image 目录」或「Droidspaces 管理的容器目录」，
        // 仍然放行，因为 ContainerEntry 会改走 droidspaces CLI。
        val imageModeOnly = !looksLikeRootfs && isDroidspacesImageModeDir(dir)
        val plausibleDsDir = !looksLikeRootfs && !imageModeOnly && isPlausibleDroidspacesContainerDir(dir)
        val acceptedAsRootfs = looksLikeRootfs || imageModeOnly || plausibleDsDir

        // 写权限探测：不做写入兜底，仅在无法写入时标记 READ_ONLY 并提示。
        // 注意：Sparse Image 目录写权限其实不重要——写入发生在 droidspaces
        // 管理的镜像内，Operit 在这里写 probe 只会因为目录权限缺而报 READ_ONLY。
        // 所以对 imageModeOnly，我们把 canWrite 当作 true（因为 Operit 不需要在
        // 这个目录里真的创建文件），避免给用户多余的「不可写」惊吓。
        val canWrite = when {
            imageModeOnly -> true
            else -> runCatching {
                val probe = File(dir, ".operit_write_probe_${System.nanoTime()}")
                val created = try {
                    probe.createNewFile()
                } catch (_: Throwable) {
                    false
                }
                if (created) runCatching { probe.delete() }
                created
            }.getOrDefault(false)
        }

        // 冲突检测：旧的内置 Ubuntu 路径是否仍然残留。
        val legacyPaths = collectLegacyRootfsPaths(context)
        val conflict = legacyPaths.isNotEmpty()

        // 入口能力静态检测：只读 rootfs 里有没有 unshare/chroot/sh 用于真正进入容器。
        val entryCapability = if (imageModeOnly) {
            // Sparse Image 目录里没有 /bin/sh /usr/bin/sh，走 unshare/chroot 永远
            // 会失败，所以跳过 entryCapability，强制给一个「至少不是 NO_SHELL」的
            // 中性值，这样状态机不会因为 NO_SHELL 的附加提示误导用户。
            // ContainerEntry 会自己走 DROIDSPACES_CLI 分支。
            TerminalContainerStatus.EntryCapability.CHROOT_ONLY
        } else {
            detectEntryCapability(dir)
        }
        val cliAvailable = isDroidspacesCliAvailable()
        val dsName = inferDroidspacesContainerName(dirPath)
        // 同时给出"运行期会实际选择哪条模板"的可读名称（和 ContainerEntry.probeEntryTemplate 的
        // 选择逻辑保持一致），用于设置页直接展示。
        val entryTemplateLabel = computeEntryTemplateLabel(
            rootDirFile = dir,
            entryCapability = entryCapability,
            droidspacesImageModeOnly = imageModeOnly,
            droidspacesCliAvailable = cliAvailable,
            droidspacesContainerName = dsName,
        )

        val state = when {
            conflict && acceptedAsRootfs && canWrite -> TerminalContainerStatus.State.CONFLICT
            conflict && acceptedAsRootfs -> TerminalContainerStatus.State.CONFLICT
            !acceptedAsRootfs -> TerminalContainerStatus.State.MISSING
            canWrite -> TerminalContainerStatus.State.OK
            else -> TerminalContainerStatus.State.READ_ONLY
        }

        val userMessage = buildString {
            when (state) {
                TerminalContainerStatus.State.OK -> {
                    if (imageModeOnly) {
                        append("检测到 Droidspaces Sparse Image 容器：$dirPath")
                        append("（运行期会使用 droidspaces --name=$dsName run 来执行命令）。")
                        if (!cliAvailable) {
                            append("｜但宿主未检测到 droidspaces 二进制，请先打开 Droidspaces APP 完成首次引导安装。")
                        }
                    } else {
                        append("容器目录校验通过：$dirPath")
                        if (entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL) {
                            append("｜但入口缺少 /bin/sh 或 /usr/bin/sh，")
                            append("实际执行命令时会提示『无法进入容器』。请在 Droidspaces 里把 rootfs 完整安装好。")
                        }
                    }
                }
                TerminalContainerStatus.State.READ_ONLY -> {
                    append("容器目录可读但无法写入：$dirPath。文件工具可读取，但写入/解压/安装命令会失败。")
                    if (entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL) {
                        append("｜且入口缺少 /bin/sh。")
                    }
                }
                TerminalContainerStatus.State.CONFLICT -> {
                    append("检测到旧内置容器路径残留（${legacyPaths.size} 个），")
                    append("可能导致终端会话仍进入内置环境。请手动删除旧 rootfs 或确认不再混用。")
                    if (entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL && !imageModeOnly) {
                        append("｜另外当前 Droidspaces 容器还缺少 /bin/sh。")
                    }
                    if (imageModeOnly) append("｜当前为 Droidspaces Sparse Image 模式。")
                }
                TerminalContainerStatus.State.MISSING -> {
                    if (presentMarkers.isEmpty() &&
                        entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL &&
                        !looksLikeKnownDistroName(dir.name)
                    ) {
                        // 目录存在但既没有 marker 又找不到 shell 且 basename 不像发行版 ——
                        // 大概率用户填的是 /mnt/Droidspaces/ 这个父目录。
                        append("目录里没有 bin / etc / usr / var / sbin/init 或 .img 这些 rootfs/Droidspaces 标志：$dirPath。")
                        append("请在它下面选择具体的发行版子目录（例如 /mnt/Droidspaces/Ubuntu26），")
                        append("并注意「Sparse Image 模式」只需选到容器名所在目录即可。")
                    } else {
                        append("目录存在但仍无法识别：$dirPath")
                        val missing = (expectedMarkers - presentMarkers.toSet())
                        if (missing.isNotEmpty()) append("（缺少 ${missing.joinToString()}）")
                        append("。如果这是 Droidspaces Sparse Image 模式的容器名目录，请确认里面有 rootfs.img，")
                        append("或在 Droidspaces APP 里先把该容器启动一次。")
                    }
                }
                else -> append("$state: $dirPath")
            }
            // 入口能力对 OK/CONFLICT/READ_ONLY 也给一段提示。
            val entryHint = when {
                imageModeOnly -> {
                    if (dsName.isBlank()) "镜像模式：尚未反推出 Droidspaces 容器名，运行期会用 basename 作为 fallback。"
                    else "镜像模式：执行命令时会调用 `droidspaces --name=$dsName run ...`。"
                }
                entryCapability == TerminalContainerStatus.EntryCapability.NO_SHELL ->
                    "无法进入容器：缺少 /bin/sh。"
                entryCapability == TerminalContainerStatus.EntryCapability.CHROOT_ONLY ->
                    "入口方式：chroot（需要 ROOT/Shizuku debugger 权限）。"
                entryCapability == TerminalContainerStatus.EntryCapability.UNSHARE_AVAILABLE ->
                    "入口方式：unshare（需要 ROOT/Shizuku debugger 权限）。"
                else -> ""
            }
            if (entryHint.isNotEmpty()) append("｜").append(entryHint)
        }

        val details = buildString {
            append("rootDir=").append(dirPath)
            append(" markers=").append(presentMarkers.joinToString(","))
            append(" canRead=").append(canRead)
            append(" canWrite=").append(canWrite)
            append(" entryCapability=").append(entryCapability.name)
            append(" droidspacesCli=").append(cliAvailable)
            append(" droidspacesName=").append(dsName)
            append(" droidspacesImageMode=").append(imageModeOnly)
            append(" plausibleDsDir=").append(plausibleDsDir)
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
            droidspacesContainerName = dsName,
            droidspacesCliAvailable = cliAvailable,
            droidspacesImageModeOnly = imageModeOnly,
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
        val markers = listOf("etc", "usr", "var", "bin")
        val subMarkers = { sub: File ->
            markers.count { marker ->
                runCatching { File(sub, marker).exists() }.getOrDefault(false)
            }
        }
        val hasInit = { sub: File ->
            runCatching { File(sub, "sbin/init").exists() || File(sub, "init").exists() }
                .getOrDefault(false)
        }
        return subs
            .asSequence()
            .filter { it.isDirectory }
            .filter { sub ->
                val readable = runCatching { sub.canRead() }.getOrDefault(false)
                if (!readable) return@filter false
                val hit = subMarkers(sub)
                // ① 像正常 rootfs（2+ markers）
                // ② 或者目录名像发行版（Ubuntu26 / debian12…）且至少有 1 个 marker
                // ③ 或者 Droidspaces 要求的 /sbin/init 存在（目录型 rootfs 官方判据）
                hit >= 2 ||
                    (looksLikeKnownDistroName(sub.name) && hit >= 1) ||
                    hasInit(sub)
            }
            .map { it.absolutePath }
            .sorted()
            .toList()
    }

    private fun collectLegacyRootfsPaths(context: Context): List<String> {
        val candidates = listOfNotNull(
            runCatching {
                File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
            }.getOrNull(),
            runCatching {
                File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs")
            }.getOrNull(),
        )
        // 只把"真正有 rootfs 内容"的旧路径报为冲突——空目录/自动生成的占位目录不算。
        // 因为 Operit 启动时总会在 filesDir 下创建 proot-distro 的空目录骨架，
        // 这些目录本身不是可用 rootfs，只是占位，不应该和用户选的 Droidspaces 容器冲突。
        return candidates.filter { runCatching { it.exists() }.getOrDefault(false) }
            .filter { legacy ->
                // 空目录或只有占位文件（busybox / .placeholder / .operit_installed_ok）
                // 视为"不存在"，不报冲突
                val children = runCatching { legacy.listFiles()?.size ?: 0 }.getOrDefault(0)
                if (children == 0) return@filter false
                // 有 bin/etc/usr 这些 rootfs 标志才算真正的 rootfs
                listOf("bin", "etc", "usr", "sbin").any { marker ->
                    runCatching { File(legacy, marker).exists() }.getOrDefault(false)
                }
            }
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
            // File.exists() 默认跟随 symlink，这对 usrmerge（/bin -> /usr/bin）、
            // sh -> dash/bash busybox link 等情况都够用。
            runCatching { f.exists() }.getOrDefault(false)
        }
        // usrmerge 环境下 /bin/sh 和 /usr/bin/sh 等价；Droidspaces 的 Ubuntu/Debian 模板
        // 通常都会放一个 /bin/sh（或指向 /bin/bash /bin/dash），但用户裁剪镜像时可能只放
        // /usr/bin/sh，所以都查一下。
        val hasShell = has("/bin/sh") || has("/usr/bin/sh")
        if (!hasShell) return TerminalContainerStatus.EntryCapability.NO_SHELL
        val hasUnshare = has("/bin/unshare") || has("/usr/bin/unshare")
        if (hasUnshare) return TerminalContainerStatus.EntryCapability.UNSHARE_AVAILABLE
        if (has("/bin/chroot") || has("/usr/sbin/chroot") || has("/usr/bin/chroot")) {
            return TerminalContainerStatus.EntryCapability.CHROOT_ONLY
        }
        // 有 sh 但找不到 unshare/chroot。执行阶段会走到 NO_ENTRY_AVAILABLE 错误模板；
        // 这里保留 CHROOT_ONLY 语义，让 UI 把"缺入口工具"作为子项提示，而不是
        // 一竿子打成 MISSING。
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
        droidspacesImageModeOnly: Boolean = false,
        droidspacesCliAvailable: Boolean = false,
        droidspacesContainerName: String = "",
    ): String {
        // Droidspaces Sparse Image 模式 → 入口一定是 droidspaces CLI。
        if (droidspacesImageModeOnly) {
            if (droidspacesContainerName.isBlank()) {
                return "Droidspaces CLI（镜像模式，basename fallback 作为 --name）"
            }
            val note = if (droidspacesCliAvailable) "已检测到 droidspaces 二进制" else "宿主未检测到 droidspaces，先运行 Droidspaces APP 完成首次安装"
            return "Droidspaces CLI：droidspaces --name=$droidspacesContainerName run sh -lc \"...\"（$note）"
        }
        if (droidspacesContainerName.isNotBlank() && droidspacesCliAvailable) {
            // 即使是目录型 rootfs，若已知 droidspaces 容器名，也优先提示 CLI（比
            // unshare/chroot 更稳，Droidspaces 会处理自己的 loop mount、namespace）。
            return "Droidspaces CLI（优先）：--name=$droidspacesContainerName；备选：unshare/chroot 直入目录"
        }
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
        val containerHasSh = containerHas("/bin/sh") || containerHas("/usr/bin/sh")
        val containerHasUnshare = containerHas("/bin/unshare") || containerHas("/usr/bin/unshare")
        if (containerHasUnshare || hostHas("unshare")) {
            val bin = if (hostHas("unshare")) "宿主 unshare" else "容器 unshare"
            return "unshare namespace 根切换（$bin）"
        }
        val chrootBin = when {
            hostHas("chroot") -> "宿主 chroot"
            containerHas("/usr/sbin/chroot") -> "容器 /usr/sbin/chroot"
            containerHas("/bin/chroot") -> "容器 /bin/chroot"
            containerHas("/usr/bin/chroot") -> "容器 /usr/bin/chroot"
            else -> null
        }
        if (chrootBin != null && containerHasSh) {
            return "chroot（$chrootBin）"
        }
        // 容器有 sh 但缺少 unshare/chroot（极少出现，比如只装了 busybox sh）。
        if (containerHasSh) {
            return "有 /bin/sh，但容器与宿主均缺少 unshare/chroot：无法真的切根"
        }
        return "入口未就绪（将以错误提示形式返回）"
    }
}
