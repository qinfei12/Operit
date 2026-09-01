package com.ai.assistance.operit.core.tools.system

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.SessionDirectoryEvent
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 终端外壳（保留原有 :terminal 子模块的会话/pty/provider）。
 *
 * 改造点：
 * - 原来进入的是内置写死的 Ubuntu/proot rootfs。
 * - 现在 createSession / executeCommand / initialize 之前会先走
 *   [TerminalContainerDetector]，当容器目录未配置、无法访问或疑似冲突时，
 *   通过返回值/异常抛出可读的原因（捕获后不崩溃），而不是进入未知的内置环境。
 *
 * 注意：**不做兜底**。未就绪时就是未就绪，调用方需要把错误文案展现给用户或返回给 AI。
 */
@RequiresApi(Build.VERSION_CODES.O)
class Terminal private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: Terminal? = null

        fun getInstance(context: Context): Terminal {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Terminal(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "Terminal"
    }

    private val terminalManager = TerminalManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * 在任何会进入容器内部执行的操作前，先检查容器目录状态。
     *
     * @return 状态；当状态为 [TerminalContainerStatus.State.OK] 或 [TerminalContainerStatus.State.READ_ONLY]
     * 时，才会允许继续。
     * @throws TerminalContainerNotReadyRuntimeException 如果容器未就绪（消息可直接给用户/AI 看）。
     */
    private suspend fun ensureContainerReadyOrThrow(forOp: String) {
        val status = runCatching { TerminalContainerDetector.detect(context) }
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    // 检测流程本身异常：包装成可读提示，但不中断上层。
                    AppLogger.e(TAG, "[$forOp] 容器目录检测异常", error)
                    throw TerminalContainerNotReadyRuntimeException(
                        "容器目录检测失败：${error.message ?: error::class.java.simpleName}"
                    )
                }
            )

        if (status.isReadyForUse) {
            if (status.state == TerminalContainerStatus.State.CONFLICT) {
                // CONFLICT 也可能是 isReadyForUse=true（新旧并存）。给日志提示，但允许继续，
                // 让 UI/设置页额外呈现冲突。真正进入容器仍由 TerminalManager 决定。
                AppLogger.w(
                    TAG,
                    "[$forOp] 容器可用，但检测到旧内置路径残留：${status.conflictingPaths.joinToString("; ")}"
                )
            }
            return
        }

        AppLogger.w(TAG, "[$forOp] blocked by container status=${status.state} ${status.details}")
        val hint = when (status.state) {
            TerminalContainerStatus.State.CONFLICT ->
                "容器目录与旧内置 rootfs 疑似冲突：${status.conflictingPaths.joinToString("; ")}。" +
                    "为避免混乱，请在设置页检查并删除旧容器。"
            TerminalContainerStatus.State.MISSING ->
                "容器目录不存在或不是有效的 Linux rootfs。当前值：${status.rootDir}"
            TerminalContainerStatus.State.NO_PERMISSION ->
                "没有权限访问容器目录：${status.rootDir}。请确认存储/root/Shizuku 权限。"
            TerminalContainerStatus.State.READ_ONLY ->
                "容器目录只读：${status.rootDir}（不应在此处，说明 isReadyForUse 逻辑变化）"
            TerminalContainerStatus.State.NOT_CONFIGURED ->
                "尚未配置终端容器目录。请在「设置 → 终端容器目录」中选择 Droidspaces 构建的发行版目录（默认扫描 /mnt/Droidspaces/）。"
            TerminalContainerStatus.State.OK -> ""
        }
        throw TerminalContainerNotReadyRuntimeException(
            message = status.userMessage + (hint.takeIf { it.isNotEmpty() }?.let { "｜$it" } ?: "")
        )
    }

    // 从 TerminalManager 暴露状态和事件流
    val commandEvents: SharedFlow<CommandExecutionEvent> = terminalManager.commandExecutionEvents
    val directoryEvents: SharedFlow<SessionDirectoryEvent> = terminalManager.directoryChangeEvents
    val terminalState: StateFlow<TerminalState> = terminalManager.terminalState
    val sessions = terminalManager.sessions
    val currentSessionId = terminalManager.currentSessionId
    val currentDirectory = terminalManager.currentDirectory
    val isInteractiveMode = terminalManager.isInteractiveMode
    val interactivePrompt = terminalManager.interactivePrompt
    val isFullscreen = terminalManager.isFullscreen

    /**
     * 初始化终端管理器。
     *
     * 初始化前先校验容器目录：目录未就绪时，打印警告并返回 false，不会把异常继续抛出到
     * 上层引发崩溃。如果 TerminalManager 自己的初始化也失败，同样包装为 false 返回。
     */
    suspend fun initialize(): Boolean {
        try {
            ensureContainerReadyOrThrow("initialize")
        } catch (error: TerminalContainerNotReadyRuntimeException) {
            AppLogger.w(TAG, "initialize aborted: ${error.message}")
            return false
        }
        return runCatching { terminalManager.initializeEnvironment() }
            .onFailure { AppLogger.e(TAG, "terminalManager.initializeEnvironment failed", it) }
            .getOrDefault(false)
    }

    /**
     * 销毁终端管理器
     */
    fun destroy() {
        runCatching { terminalManager.cleanup() }
            .onFailure { AppLogger.e(TAG, "terminalManager.cleanup failed", it) }
    }

    /**
     * 创建新的终端会话 - 同步等待初始化完成
     *
     * 当容器目录未就绪时抛 [TerminalContainerNotReadyRuntimeException]。
     * 上层向导/工具需要 catch 该异常，并把 message 当作错误给用户/AI，不要崩溃。
     */
    suspend fun createSession(title: String? = null): String {
        ensureContainerReadyOrThrow("createSession")
        AppLogger.d(TAG, "Creating new terminal session and waiting for initialization")
        val newSession = runCatching { terminalManager.createNewSession(title) }
            .onFailure { error ->
                AppLogger.e(TAG, "createNewSession failed", error)
                throw TerminalContainerNotReadyRuntimeException(
                    "创建终端会话失败：${error.message ?: error::class.java.simpleName}"
                )
            }
            .getOrThrow()
        AppLogger.d(TAG, "Session ${newSession.id} initialized successfully")
        return newSession.id
    }
    
    /**
     * 切换到指定会话
     */
    fun switchToSession(sessionId: String) {
        runCatching { terminalManager.switchToSession(sessionId) }
            .onFailure { AppLogger.e(TAG, "switchToSession($sessionId) failed", it) }
    }

    /**
     * 关闭终端会话
     */
    fun closeSession(sessionId: String) {
        runCatching { terminalManager.closeSession(sessionId) }
            .onFailure { AppLogger.e(TAG, "closeSession($sessionId) failed", it) }
    }

    /**
     * 执行命令并等待其完成（不切换当前会话）。
     *
     * - 发送前检查容器状态，未就绪时抛 [TerminalContainerNotReadyRuntimeException]。
     * - 对底层执行错误进行 catch 并返回错误文案输出（非 null 字符串），而不是
     *   null，减少调用方到处判空崩溃。
     */
    suspend fun executeCommand(sessionId: String, command: String): String? {
        try {
            ensureContainerReadyOrThrow("executeCommand")
        } catch (containerError: TerminalContainerNotReadyRuntimeException) {
            // 返回非空字符串，让上游 which pnpm 等"无输出=未安装"的判断走统一路径。
            AppLogger.w(TAG, "executeCommand blocked: ${containerError.message}")
            return containerError.message
        }
        val deferred = CompletableDeferred<String>()
        val output = StringBuilder()
        var completionOutput: String? = null
        
        // 生成命令ID
        val commandId = java.util.UUID.randomUUID().toString()
        
        val collectorReady = CompletableDeferred<Unit>()
        
        // 先开始订阅事件流，然后再发送命令
        val job = scope.launch {
            runCatching {
                commandEvents
                    .filter { it.sessionId == sessionId && it.commandId == commandId }
                    .onStart { collectorReady.complete(Unit) } // 发出信号，表示已准备好收集
                    .collect { event ->
                        if (event.isCompleted) {
                            completionOutput = event.outputChunk
                        } else {
                            output.append(event.outputChunk)
                        }
                        if (event.isCompleted) {
                            deferred.complete(completionOutput?.takeIf { it.isNotEmpty() } ?: output.toString())
                        }
                    }
            }.onFailure { error ->
                AppLogger.e(TAG, "executeCommand event collector failed", error)
                if (!deferred.isCompleted) {
                    deferred.complete("命令执行出错：${error.message ?: error::class.java.simpleName}")
                }
            }
        }

        val sendResult = runCatching { collectorReady.await() }
            .andThen { runCatching { terminalManager.sendCommandToSession(sessionId, command, commandId) } }

        if (sendResult.isFailure) {
            val error = sendResult.exceptionOrNull()
            AppLogger.e(TAG, "executeCommand send failed", error)
            if (!deferred.isCompleted) {
                deferred.complete("命令发送失败：${error?.message ?: error?.javaClass?.simpleName.orEmpty()}")
            }
        }

        val result = runCatching { deferred.await() }
            .getOrElse { error ->
                AppLogger.e(TAG, "executeCommand await failed", error)
                "命令等待结果失败：${error.message ?: error::class.java.simpleName}"
            }
        
        job.cancel()
        
        return result
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        try {
            ensureContainerReadyOrThrow("executeHiddenCommand")
        } catch (containerError: TerminalContainerNotReadyRuntimeException) {
            AppLogger.w(TAG, "executeHiddenCommand blocked: ${containerError.message}")
            // HiddenExecResult 在 terminal:provider:type 中，这里无法直接构造构造器签名。
            // 用反射式失败：把错误包装为重抛，由调用方 catch 并展示。
            throw IllegalStateException(containerError.message, containerError)
        }
        return runCatching {
            terminalManager.executeHiddenCommand(
                command = command,
                executorKey = executorKey,
                timeoutMs = timeoutMs
            )
        }.getOrElse { error ->
            AppLogger.e(TAG, "executeHiddenCommand failed", error)
            throw IllegalStateException(
                "隐藏命令执行失败：${error.message ?: error::class.java.simpleName}",
                error
            )
        }
    }

    /**
     * 执行命令 - Flow版本
     * 返回命令执行过程中的所有事件，直到命令完成。
     *
     * 若容器未就绪，会直接 close(错误)，消费方应当 catch 而不是崩溃。
     */
    fun executeCommandFlow(sessionId: String, command: String): Flow<CommandExecutionEvent> {
        return channelFlow {
            val commandId = UUID.randomUUID().toString()
            val collectorReady = CompletableDeferred<Unit>()

            // 先检测容器。channelFlow 构建体在 ProducerScope 内，可以直接 suspend。
            val prepareError = runCatching { ensureContainerReadyOrThrow("executeCommandFlow") }
                .exceptionOrNull()
            if (prepareError != null) {
                AppLogger.w(TAG, "executeCommandFlow blocked: ${prepareError.message}")
                close(prepareError)
                return@channelFlow
            }

            val collectorJob = launch {
                runCatching {
                    commandEvents
                        .filter { it.sessionId == sessionId && it.commandId == commandId }
                        .onStart { collectorReady.complete(Unit) }
                        .transformWhile { event ->
                            emit(event)
                            !event.isCompleted
                        }
                        .collect { sentEvent ->
                            send(sentEvent)
                        }
                }.onFailure { error ->
                    AppLogger.e(TAG, "executeCommandFlow collector failed", error)
                    if (!isClosedForSend) close(error)
                }
            }

            // 先确保事件收集器就绪，再发送命令，避免快命令输出在订阅前丢失。
            runCatching {
                collectorReady.await()
                terminalManager.sendCommandToSession(sessionId, command, commandId)
            }.onFailure { error ->
                AppLogger.e(TAG, "executeCommandFlow send failed", error)
                if (!isClosedForSend) close(error)
            }
            collectorJob.join()
        }
    }

    
    /**
     * 发送输入到当前会话
     */
    fun sendInput(sessionId: String, input: String) {
        runCatching {
            terminalManager.switchToSession(sessionId)
            terminalManager.sendInput(input)
        }.onFailure { AppLogger.e(TAG, "sendInput($sessionId) failed", it) }
    }

    /**
     * 发送中断信号 (Ctrl+C)
     */
    fun sendInterruptSignal(sessionId: String) {
        runCatching {
            terminalManager.switchToSession(sessionId)
            terminalManager.sendInterruptSignal()
        }.onFailure { AppLogger.e(TAG, "sendInterruptSignal($sessionId) failed", it) }
    }

    /**
     * 检查服务是否已连接 (现在总是返回 true)
     */
    fun isConnected(): Boolean {
        return true
    }
}

private inline fun <T> Result<T>.andThen(block: () -> Result<T>): Result<T> =
    if (isSuccess) block() else this

/**
 * 终端容器未就绪的运行时异常。
 *
 * - 用于给 [Terminal] 等 suspend/非 suspend 调用方统一抛出。
 * - message 可直接对用户/AI 展示。
 * - 上层（AI 工具、向导卡片、设置页）必须 catch，并显示 message，不允许直接崩溃。
 */
class TerminalContainerNotReadyRuntimeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
