package com.ai.assistance.operit.util

import android.content.Context
import com.ai.assistance.operit.data.preferences.TerminalContainerPreferences
import com.ai.assistance.operit.data.preferences.terminalContainerPreferences
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 路径映射工具类
 * 用于在 Android 宿主文件系统和外部 Linux 容器（默认 Droidspaces rootfs）之间转换文件路径。
 *
 * 改造说明（不内置终端容器，自选 rootfs）：
 * - 旧实现把 Linux 路径硬映射到 {filesDir}/usr/var/lib/proot-distro/installed-rootfs/ubuntu
 * - 新实现从 [TerminalContainerPreferences] 读取用户配置的容器根目录。
 * - **禁止兜底**：未配置 / 读配置失败时抛出 [TerminalContainerNotReadyException]，
 *   让调用方通过 TerminalContainerDetector/设置页给用户明确提示，而不是静默落到旧路径。
 */
object PathMapper {

    private const val TAG = "PathMapper"

    /**
     * 在映射前获取/检查容器根目录。
     *
     * 非 suspend 入口（给 zip/unzip 等同步路径消费者）通过 runBlocking 读取首值。
     * suspend 入口直接使用 [resolveContainerRootDirSuspend] 更合适。
     *
     * 未配置/失败抛 [TerminalContainerNotReadyException]。
     */
    internal fun resolveContainerRootDir(context: Context): String {
        val prefs = runCatching { terminalContainerPreferences }.getOrElse { error ->
            throw TerminalContainerNotReadyException(
                reason = "TerminalContainerPreferences 尚未初始化",
                cause = error,
            )
        }
        val rootDir = runBlocking {
            runCatching { prefs.getContainerRootDir() }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    throw TerminalContainerNotReadyException(
                        reason = "读取容器根目录配置失败",
                        cause = error,
                    )
                }
            )
        }
        return validateAndReturnRootDir(rootDir)
    }

    internal suspend fun resolveContainerRootDirSuspend(context: Context): String {
        val prefs = runCatching { terminalContainerPreferences }.getOrElse { error ->
            throw TerminalContainerNotReadyException(
                reason = "TerminalContainerPreferences 尚未初始化",
                cause = error,
            )
        }
        val rootDir = runCatching { prefs.getContainerRootDir() }.fold(
            onSuccess = { it },
            onFailure = { error ->
                throw TerminalContainerNotReadyException(
                    reason = "读取容器根目录配置失败",
                    cause = error,
                )
            }
        )
        return validateAndReturnRootDir(rootDir)
    }

    private fun validateAndReturnRootDir(rootDir: String): String {
        val normalized = rootDir.trim()
        if (normalized.isEmpty()) {
            throw TerminalContainerNotReadyException(
                reason = "尚未配置终端容器目录，请前往「设置 → 终端容器目录」进行配置（默认推荐 /mnt/Droidspaces/ 下的发行版目录）",
            )
        }
        return normalized
    }

    /**
     * 将 Linux 路径（例如 "/home/user/test.txt"、"/etc/hosts"、"/"）转换为
     * Android 宿主上实际可访问的绝对路径（拼接在容器根目录下）。
     *
     * 容器目录未配置时，会抛出 [TerminalContainerNotReadyException]。
     * 调用方（文件工具、压缩、下载等）应当捕获并以用户可见的错误提示返回，而不是崩溃。
     */
    fun mapLinuxPath(context: Context, linuxPath: String): String {
        val containerRoot = resolveContainerRootDir(context)
        return joinLinuxPath(containerRoot, linuxPath)
    }

    /**
     * suspend 版本，避免额外的 runBlocking。
     */
    suspend fun mapLinuxPathSuspend(context: Context, linuxPath: String): String {
        val containerRoot = resolveContainerRootDirSuspend(context)
        return joinLinuxPath(containerRoot, linuxPath)
    }

    /**
     * 判断是否为 Linux 容器环境。
     */
    fun isLinuxEnvironment(environment: String?): Boolean {
        return environment?.lowercase() == "linux"
    }

    /**
     * 根据 environment 参数转换路径。
     *
     * 说明：历史上该函数在 zip/unzip 工具的 suspend 函数里以普通方式调用，
     * 因此保持非 suspend 签名；内部若为 Linux 环境会通过 runBlocking 读取一次配置。
     * 如需更"干净"的 suspend 版本可改为显式调用 [mapLinuxPathSuspend]。
     */
    fun resolvePath(context: Context, path: String, environment: String?): String {
        return if (isLinuxEnvironment(environment)) {
            mapLinuxPath(context, path)
        } else {
            path
        }
    }

    /**
     * 拼接容器根目录 + Linux 路径。对空路径 / 纯 / 的情况返回根目录本身。
     * 不做路径穿越处理（由上游 PathValidator.validateLinuxPath 负责规范化和禁绝 ../）。
     */
    private fun joinLinuxPath(containerRoot: String, linuxPath: String): String {
        val relative = linuxPath.trimStart('/')
        return if (relative.isEmpty()) {
            File(containerRoot).absolutePath
        } else {
            File(containerRoot, relative).absolutePath
        }
    }
}

/**
 * 容器目录未就绪时的明确异常。调用方应当通过 UI/工具结果提示用户，而不是崩溃。
 */
class TerminalContainerNotReadyException(
    val reason: String,
    cause: Throwable? = null,
) : IllegalStateException(reason, cause)

