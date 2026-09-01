package com.ai.assistance.operit.core.tools.system

import android.content.Context
import com.ai.assistance.operit.data.preferences.TerminalContainerPreferences
import com.ai.assistance.operit.data.preferences.terminalContainerPreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * 容器入口策略：把一条"Linux 容器中执行的命令"透明包装为宿主 shell 可执行的命令。
 *
 * 设计要点（用户要求：尽可能减少冲突，别的命令仍可正常调用终端执行）：
 *
 * 1. **不碰 TerminalManager / PTY / Provider 内部**。这些都属于 :terminal 子模块外壳，
 *    继续保留；agent 插件、MCP、终端 UI 全部零改动。
 * 2. **仅在 Terminal.kt 的几个对外 entry（createSession / executeCommand /
 *    executeHiddenCommand / executeCommandFlow）之前决定是否需要用 unshare/chroot 真进入
 *    Droidspaces rootfs。如果权限不够进入不了，就给清晰的文本错误，不崩溃。**
 * 3. **每次命令独立包一层**，避免依赖"会话内部 cd"又被新的 unshare/chroot 打回原形。
 *    我们维护一个每会话的逻辑当前目录（logicalCwd），把它作为容器内的工作目录一起带进去。
 *    这样 `cd /home && pwd` 能在后续命令继续表现一致（只要后续命令也走包装器）。
 *
 * 优先级（chroot/unshare 优先，真进入；但拿不到 root 就老老实实告诉用户缺什么）：
 *
 *   A. ROOT 权限可用 → `unshare --mount --pid --fork --root ROOT --wd CWD /bin/sh -lc CMD`
 *      如果当前 rootfs 里没有 unshare（裁剪系统），退化为：
 *      `chroot ROOT /bin/sh -lc "cd CWD && CMD"`
 *
 *   B. 拿不到 ROOT → 判定为"无法真进入"，返回中文说明，告诉用户需要 root/Shizuku(debugger)
 *      级别的权限。**不兜底退回 proot/内置路径**，避免把命令又送回错误环境。
 *
 * 说明：TerminalManager 本身怎么起 PTY、怎么收集输出，不变；我们只把"sendCommand"这一步发送的
 * 字符串从原始命令改成"进入容器后再执行命令"。
 */
object ContainerEntry {

    private const val TAG = "ContainerEntry"

    /** 每会话的逻辑容器内工作目录（相对容器根的 Linux 路径，总是以 "/" 开头）。 */
    private val sessionCwd = mutableMapOf<String, String>()

    /** 每会话上次探测到的"可用入口命令模板"缓存，避免每次都跑 shell 探测。 */
    private val sessionEntryTemplate = mutableMapOf<String, EntryTemplate?>()

    /**
     * 进入容器所需的入口判定结果，由 [prepareForSession] 生成，后续命令直接套用。
     *
     * 这里故意不做成 sealed 枚举（避免引入太多新类型），直接是"字符串模板 + 是否需要 ROOT"。
     */
    internal class EntryTemplate(
        /**
         * 模板占位：
         *   {ROOT}  -> 容器根目录绝对路径
         *   {CWD}   -> 容器内当前工作目录（Linux 路径，例如 /home）
         *   {CMD}   -> 原始命令字符串
         */
        val template: String,
        /** 该模板执行时是否需要 ROOT 权限（unshare/chroot 都需要） */
        val needRoot: Boolean,
        /** 给日志/调试用的模板来源说明 */
        val source: String,
    )

    /** 会话级入口准备结果：给 Terminal.createSession/executeCommand 使用。 */
    data class Prepared(
        val containerRootDir: String,
        val template: EntryTemplate,
        /** 容器内逻辑工作目录（Linux 路径，一定以 "/" 开头）。 */
        val logicalCwd: String,
    )

    /**
     * 会话新建/复用前的准备动作：
     * - 取当前配置的 rootDir；若未就绪直接抛 [TerminalContainerNotReadyRuntimeException]
     * - 探测 unshare/chroot 是否在容器/宿主里可用
     * - 初始化会话逻辑 CWD 为 "/"
     *
     * 探测失败（缺权限或缺可执行文件）不会崩，而是会返回一个"错误模板"：执行时会先 echo
     * 一段中文错误，然后 exit 非 0，这样上层 executeCommand 能把错误当输出返回给 AI/用户。
     *
     * [overrideCwd]：仅在调用方（例如 ChatViewModel 的工作区启动）已知命令接下来要
     * 进入一个具体目录时传，用来避免"先 `cd 宿主路径` 再执行"这条命令被我们剥前缀时
     * 出现根路径不匹配的情况。不传或空时沿用会话当前缓存 cwd。
     */
    suspend fun prepareForSession(
        context: Context,
        sessionId: String,
        overrideCwd: String? = null,
    ): Prepared {
        val rootDir = resolveRootDirOrThrow(context)
        if (!overrideCwd.isNullOrBlank()) {
            sessionCwd[sessionId] = normalizeCwdForContainer(rootDir, overrideCwd)
        }
        val existingTemplate = sessionEntryTemplate[sessionId]
        val template = existingTemplate ?: probeEntryTemplate(context, rootDir)
        if (existingTemplate == null) sessionEntryTemplate[sessionId] = template
        val cwd = sessionCwd.getOrPut(sessionId) { "/" }
        return Prepared(
            containerRootDir = rootDir,
            template = template,
            logicalCwd = cwd,
        )
    }

    /**
     * 当会话关闭时，清理对应缓存（避免泄漏、也避免 rootDir 变更后用旧模板）。
     * 调用方：Terminal.closeSession。
     */
    fun onSessionClosed(sessionId: String) {
        sessionCwd.remove(sessionId)
        sessionEntryTemplate.remove(sessionId)
    }

    /** 用户在设置页改了 rootDir 后，应该整体让旧会话失效。 */
    fun invalidateAllCachedTemplates() {
        sessionEntryTemplate.clear()
        sessionCwd.clear()
    }

    /**
     * 把一条即将发送给终端会话的命令包装成"进入容器后再执行"。
     *
     * 返回包装后的命令字符串（永远非 null）。
     *
     * 特殊规则（兼容 agent 常用的工作区目录切换写法，**不改变上层调用**）：
     *
     * - 如果执行方显式传了一个 **宿主路径** 给 `cd "<绝对路径>"`，并且该路径以容器根目录为前缀，
     *   我们会把它剥离前缀，还原成容器内的 Linux 路径再进入。
     *   例如：容器根=/mnt/Droidspaces/ubuntu，执行 `cd "/mnt/Droidspaces/ubuntu/home/user"`
     *   会被解释为容器内的 `cd /home/user`，同时更新会话级逻辑 cwd。
     * - 否则按容器内语义处理：`cd /home` / `cd subdir` / `cd ..` 都会推进 logicalCwd。
     * - 对 `cd ~` / `cd -` 这种需要环境/历史的，不解析 logicalCwd，只透传。
     *
     * 其它命令（pwd / whoami / ls / command -v / mkdir / pnpm / pip / apt 等）一律视为
     * 容器内命令，不做路径剥离。如果 agent 真的需要访问宿主路径，应该显式切换到
     * environment=android 的文件工具。
     */
    fun wrapCommand(
        prepared: Prepared,
        sessionId: String,
        rawCommand: String,
    ): String {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return rawCommand

        val cdMatch = CD_PATTERN.matchEntire(cmd)
        if (cdMatch != null) {
            val rawTarget = cdMatch.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            val containerRoot = prepared.containerRootDir.trimEnd('/')
            val rewritten = when {
                // 宿主路径 → 剥 rootDir 前缀转容器内路径
                rawTarget.startsWith("$containerRoot/") || rawTarget == containerRoot -> {
                    val striped = rawTarget.removePrefix(containerRoot).ifEmpty { "/" }
                    striped.takeIf { it.startsWith("/") } ?: "/$striped"
                }
                // 容器内绝对路径
                rawTarget.startsWith("/") -> rawTarget
                // ~ / - 等不解析；留空给下面 when 处理
                else -> null
            }
            val normalizedTarget = when {
                rewritten != null -> rewritten
                rawTarget == "~" || rawTarget.isEmpty() -> "/root"
                rawTarget == "-" -> null // 保持逻辑 cwd 不变，交给容器内 sh 解释
                else -> joinLogicalPath(sessionCwd[sessionId] ?: prepared.logicalCwd, rawTarget)
            }
            if (normalizedTarget != null) {
                sessionCwd[sessionId] = normalizedTarget
            }
        }

        val cwd = sessionCwd[sessionId] ?: prepared.logicalCwd
        val root = shellQuote(prepared.containerRootDir)
        val cwdQuoted = shellQuote(cwd)
        val cmdQuoted = shellQuote(cmd)
        return fillTemplate(prepared.template, root = root, cwd = cwdQuoted, innerCmd = cmdQuoted)
    }

    // ================================================================
    // 内部实现
    // ================================================================

    private val CD_PATTERN = Regex("""^cd\s+(.+?)\s*${'$'}""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 在宿主 PATH + 常见工具目录中找一条命令。
     * 注意：不使用 shell，只做静态文件存在判断（避免阻塞 prepareForSession）。
     */
    private fun hostHasCommand(name: String): Boolean {
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
        for (dir in candidates) {
            val f = File(dir, name)
            if (runCatching { f.isFile && f.canExecute() }.getOrDefault(false)) return true
        }
        return false
    }

    private suspend fun resolveRootDirOrThrow(context: Context): String {
        val prefs = runCatching { terminalContainerPreferences }.getOrElse { err ->
            AppLogger.w(TAG, "resolveRootDir: preferences not initialized", err)
            throw TerminalContainerNotReadyRuntimeException(
                "容器配置尚未初始化：${err.message ?: err::class.java.simpleName}。请稍候或重启应用。"
            )
        }
        val dir = runCatching { prefs.getContainerRootDir() }.fold(
            onSuccess = { it },
            onFailure = { err ->
                throw TerminalContainerNotReadyRuntimeException(
                    "读取容器根目录失败：${err.message ?: err::class.java.simpleName}"
                )
            }
        )
        val trimmed = dir.trim()
        if (trimmed.isEmpty()) {
            throw TerminalContainerNotReadyRuntimeException(
                "尚未配置终端容器目录。请在「设置 → 终端容器目录」指定 Droidspaces 构建的 rootfs（默认 /mnt/Droidspaces/<发行版>）。"
            )
        }
        if (!File(trimmed).isDirectory) {
            throw TerminalContainerNotReadyRuntimeException(
                "容器根目录不是合法目录：$trimmed。请在设置页重新选择。"
            )
        }
        return File(trimmed).absolutePath
    }

    /**
     * 探测入口模板。
     *
     * 注意：这里为了**不新增对"宿主 shell 执行链路"的耦合**（例如再次调用 RootAuthorizer 可能
     * 引发请求权限对话框，对 prepareForSession 太重），我们只做静态检查：
     * - 容器内 `/bin/unshare` + `/bin/sh` 都存在 → 优先 unshare 模板
     * - 否则容器内 `/bin/sh` + `/usr/sbin/chroot` 或 `/bin/chroot` 存在 → chroot 模板
     * - 否则构造"错误模板"，命令执行时直接把原因 echo 给上层，不会崩溃。
     *
     * 是否真的能进入（有没有 ROOT），让 TerminalManager 实际执行之后用 exitCode + stderr
     * 再反馈；我们这里只保证**不会把命令送到错误的环境**。
     */
    private fun probeEntryTemplate(context: Context, rootDir: String): EntryTemplate {
        val rootFile = File(rootDir)
        val containerHas = { relative: String ->
            val f = File(rootFile, relative.removePrefix("/"))
            runCatching { f.exists() && f.canExecute() || (f.isFile && f.canRead()) }.getOrDefault(false)
        }

        // 1) unshare 优先（真 namespace 隔离，再 chroot 进 rootfs）。
        // unshare 可以来自宿主（android toolbox/toybox/第三方 /system/xbin）或容器内。
        // 注意：实际是否能跑通取决于 ShellExecutor 的 ROOT/Shizuku debugger 权限，
        // 这里不做任何兜底，执行失败时让 unshare/chroot 把 stderr 作为命令输出返回。
        val hostHasUnshare = hostHasCommand("unshare")
        val hostHasChroot = hostHasCommand("chroot")
        if ((containerHas("/bin/unshare") || hostHasUnshare) && containerHas("/bin/sh")) {
            AppLogger.d(
                TAG,
                "probeEntryTemplate: pick UNSHARE template (hostHasUnshare=$hostHasUnshare)"
            )
            val unshareBin = if (hostHasUnshare) "unshare" else "/bin/unshare"
            // unshare --mount --fork：获得新 mount 命名空间；chroot 到 rootfs；
            // 在容器内挂 proc/sys/dev（若失败忽略，很多最小环境会缺 /proc 挂载）；
            // cd 到逻辑 cwd；执行原命令。
            // 说明：如果 unshare 是容器内的，意味着当前 sh 已经在宿主里看到了容器目录，
            // 这种写法仍能通过 namespace + chroot 把命令重新切进去。
            return EntryTemplate(
                template = buildString {
                    append(unshareBin)
                    append(" --mount --fork --pid ")
                    append("--root={ROOT} --wd={CWD} ")
                    append("/bin/sh -lc ")
                    append("'")
                    append(
                        "mount -t proc proc /proc 2>/dev/null || true;" +
                            " mount -t sysfs sys /sys 2>/dev/null || true;" +
                            " mount -t devtmpfs dev /dev 2>/dev/null || true;" +
                            " {CMD}"
                    )
                    append("'")
                },
                needRoot = true,
                source = "UNSHARE_NATIVE($unshareBin)",
            )
        }

        // 2) 退化为 chroot /bin/sh -lc "cd ... && ..."
        // chroot 同样优先用宿主的（更通用），没有才尝试容器内的
        val chrootBin = when {
            hostHasChroot -> "chroot"
            containerHas("/usr/sbin/chroot") -> "/usr/sbin/chroot"
            containerHas("/bin/chroot") -> "/bin/chroot"
            else -> null
        }
        if (chrootBin != null && containerHas("/bin/sh")) {
            AppLogger.d(TAG, "probeEntryTemplate: pick CHROOT_SH template via $chrootBin")
            return EntryTemplate(
                template = buildString {
                    append("{ROOT_CHROOT_PREFIX}")
                    append("$chrootBin {ROOT} /bin/sh -lc ")
                    append("'cd {CWD} || true; {CMD}'")
                },
                needRoot = true,
                source = "CHROOT_SH($chrootBin)",
            )
        }

        // 3) 容器内连 /bin/sh 都没：显然不是合法 rootfs，直接给错误模板。
        val reason = buildString {
            append("容器内缺少可执行的外壳或入口工具，无法进入 rootfs。")
            append(" 容器目录=$rootDir;")
            append(" 已检查=host:unshare($hostHasUnshare),host:chroot($hostHasChroot);")
            append(" container=/bin/unshare(${containerHas("/bin/unshare")}),")
            append("/bin/sh(${containerHas("/bin/sh")}),")
            append("/usr/sbin/chroot(${containerHas("/usr/sbin/chroot")}),")
            append("/bin/chroot(${containerHas("/bin/chroot")}).")
            append(" 请确认该目录为 Droidspaces 构建的完整 Linux rootfs，并安装基础 busybox/coreutils。")
        }
        AppLogger.w(TAG, "probeEntryTemplate: NO_ENTRY_AVAILABLE $reason")
        // 注意：错误模板不能再引用容器内 /bin/sh（否则如果那路径本身就不存在，整条命令会失败成
        // "sh: /bin/sh: No such file or directory"，用户看不到我们辛苦收集的原因）。
        // 直接用宿主 sh（一定存在）echo 原因到 stderr，并以非 0 退出。
        return EntryTemplate(
            template = "sh -lc 'echo ${shellQuote(reason)} 1>&2; exit 23'",
            needRoot = false,
            source = "NO_ENTRY_AVAILABLE",
        )
    }

    private fun fillTemplate(
        template: EntryTemplate,
        root: String,
        cwd: String,
        innerCmd: String,
    ): String {
        // ROOT_CHROOT_PREFIX：模板里用它占位需要 root 权限加持的"前缀命令"。
        // 因为我们不直接控制 TerminalManager 用哪个 ShellExecutor，这里不硬写 su/shizuku 前缀。
        // 说明：是否能获得 ROOT，由 ShellExecutor/TerminalProvider 配置的权限级别决定；
        // 我们这里只保证命令语义是对的。若模板 needRoot=true 但当前权限不够，chroot/unshare
        // 会返回 "Operation not permitted"，上层会把错误作为命令输出返回给 AI/用户。
        val prefix = ""
        return template.template
            .replace("{ROOT_CHROOT_PREFIX}", prefix)
            .replace("{ROOT}", root)
            .replace("{CWD}", cwd)
            .replace("{CMD}", innerCmd)
    }

    /** 极简 POSIX shell 单引号转义，够用即可。 */
    private fun shellQuote(raw: String): String {
        return buildString {
            append('\'')
            for (ch in raw) {
                if (ch == '\'') {
                    append("'\\''")
                } else {
                    append(ch)
                }
            }
            append('\'')
        }
    }

    /** 把一个相对路径拼到容器内逻辑 cwd 后面（不解析 symlink/真实挂载）。 */
    private fun joinLogicalPath(currentCwd: String, rel: String): String? {
        if (rel.isEmpty()) return currentCwd
        val parts = ArrayDeque<String>()
        val base = if (currentCwd.startsWith("/")) currentCwd else "/$currentCwd"
        base.split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * 规范化传入的 overrideCwd：
     *
     * - 如果是容器内路径（以 "/" 开头，且不是容器根前缀的宿主路径） → 直接用。
     * - 如果刚好落在 `{containerRootDir}/...` 下面 → 剥前缀转容器内路径。
     * - 如果是其它宿主路径（例如 /sdcard/work），说明 agent 想切到容器外的目录
     *   执行命令。这种情况下我们不会把命令偷运到容器里执行，而是把 cwd 固定在容器
     *   根 "/"，并依赖 shell 包装层里的 `cd ...` 自然失败（提示"No such file"）。
     *   这样对 AI 来说就是"目录不存在"，是一条明确且不崩溃的反馈。
     */
    private fun normalizeCwdForContainer(containerRootDir: String, raw: String): String {
        val trimmed = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isEmpty()) return "/"
        val root = containerRootDir.trimEnd('/')
        return when {
            trimmed.startsWith("/") && (trimmed == root || trimmed.startsWith("$root/")) -> {
                val striped = trimmed.removePrefix(root).ifEmpty { "/" }
                if (striped.startsWith("/")) striped else "/$striped"
            }
            trimmed.startsWith("/") -> "/"
            else -> "/"
        }
    }
}
