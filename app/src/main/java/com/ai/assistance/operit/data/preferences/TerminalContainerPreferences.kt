package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 终端容器（Linux rootfs）配置。
 *
 * 终端外壳（session/pty/provider）继续由 :terminal 子模块提供，但容器根目录不再内置到
 * app/files/usr/... 下，而是由用户选择，默认指向 Droidspaces 在 /mnt 下创建的目录。
 *
 * 设计原则（严格遵守）：
 * - 不做兜底；未配置或校验失败必须明确暴露，不要 fallback 到旧的内置 Ubuntu 路径。
 * - 未发布版本直接生效；调用方读到空值就当作"尚未准备好"。
 * - 所有路径由 PathMapper / ContainerDetector 统一校验，避免每处分散判断。
 */

private val Context.terminalContainerDataStore: DataStore<Preferences> by
preferencesDataStore(name = "terminal_container_preferences")

lateinit var terminalContainerPreferences: TerminalContainerPreferences
    private set

private val initLock = Any()

@Volatile
private var initialized = false

fun initTerminalContainerPreferences(context: Context) {
    if (initialized) return
    synchronized(initLock) {
        if (initialized) return
        terminalContainerPreferences = TerminalContainerPreferences(context)
        initialized = true
    }
}

class TerminalContainerPreferences(private val context: Context) {
    companion object {
        /**
         * 默认容器根目录：Droidspaces APP 会把发行版 rootfs 挂载/放置到
         * /mnt/Droidspaces/<distro>/ 下。这里默认指向父目录，用户可在设置页选取具体
         * 的子发行版目录（例如 /mnt/Droidspaces/ubuntu/）。
         */
        const val DEFAULT_CONTAINER_ROOT_DIR = "/mnt/Droidspaces/"

        private val KEY_LINUX_CONTAINER_ROOT_DIR =
            stringPreferencesKey("linux_container_root_dir")
    }

    /**
     * 返回用户配置的容器根目录（去掉空白、追加单斜杠归一化）。
     * 未配置 -> 空字符串；调用方必须识别空字符串并给出"未就绪"提示。
     */
    val containerRootDirFlow: Flow<String> =
        context.terminalContainerDataStore.data.map { prefs ->
            normalizeContainerRootDir(prefs[KEY_LINUX_CONTAINER_ROOT_DIR])
        }

    suspend fun getContainerRootDir(): String {
        val raw = context.terminalContainerDataStore.data.first()[KEY_LINUX_CONTAINER_ROOT_DIR]
        return normalizeContainerRootDir(raw)
    }

    suspend fun setContainerRootDir(rawPath: String) {
        val normalized = normalizeContainerRootDir(rawPath)
        context.terminalContainerDataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(KEY_LINUX_CONTAINER_ROOT_DIR)
            } else {
                prefs[KEY_LINUX_CONTAINER_ROOT_DIR] = normalized
            }
        }
    }

    /**
     * 规范化：去空格；空字符串视为未配置；其他路径保留原样。
     * 这里不强制追加尾斜杠，PathMapper 在拼接 linuxPath 时自己会处理分隔符。
     */
    internal fun normalizeContainerRootDir(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return trimmed
    }
}
