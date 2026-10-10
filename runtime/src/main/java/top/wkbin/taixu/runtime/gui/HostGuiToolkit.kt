package top.wkbin.taixu.runtime.gui

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.shell.ShellQuote
import top.wkbin.taixu.runtime.privilege.PrivilegeManager

/**
 * Unified GUI action toolkit with failure degradation:
 * 1) With Shizuku/Root: auto-enable TaiXu accessibility service (no manual toggle)
 * 2) Accessibility global gestures
 * 3) Privileged `cmd input`
 * 4) Privileged `/system/bin/input`
 * Paste prefers clipboard + KEYCODE_PASTE / Ctrl+V (CJK-safe).
 */
class HostGuiToolkit(
    private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val accessibilityEnabler: GuiAccessibilityEnabler,
) {
    suspend fun execute(action: GuiPrimitive): GuiExecResult = withContext(Dispatchers.IO) {
        // Privileged devices: grant ourselves accessibility so gestures work without user UI.
        runCatching { accessibilityEnabler.ensureEnabled() }
        when (action) {
            is GuiPrimitive.Tap -> tap(action.x, action.y)
            is GuiPrimitive.DoubleTap -> doubleTap(action.x, action.y, action.gapMs)
            is GuiPrimitive.LongPress -> longPress(action.x, action.y, action.durationMs)
            is GuiPrimitive.Swipe -> swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
            is GuiPrimitive.Scroll -> {
                val metrics = context.resources.displayMetrics
                val mapped = action.direction.toSwipe(
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    distanceRatio = action.distanceRatio,
                    durationMs = action.durationMs,
                    anchorX = action.anchorX,
                    anchorY = action.anchorY,
                )
                swipe(mapped.x1, mapped.y1, mapped.x2, mapped.y2, mapped.durationMs)
                    .let { it.copy(message = "Scroll ${action.direction.name.lowercase()} · ${it.message}") }
            }
            is GuiPrimitive.Key -> key(action.key)
            is GuiPrimitive.PasteText -> pasteText(action.text)
        }
    }

    private suspend fun tap(x: Int, y: Int): GuiExecResult =
        runTouch("Tap ($x,$y)") { backend ->
            when (backend) {
                GuiBackendId.ACCESSIBILITY -> {
                    if (!AccessibilityGestureBridge.isAvailable()) {
                        accessibilityEnabler.ensureEnabled()
                    }
                    if (AccessibilityGestureBridge.isAvailable() && AccessibilityGestureBridge.tap(x, y))
                        ok(backend, "Tapped ($x,$y)")
                    else fail(backend, if (AccessibilityGestureBridge.isAvailable()) "Gesture cancelled" else "Accessibility service is unavailable (privileged self-authorization was attempted)")
                }
                GuiBackendId.CMD_INPUT -> shellInput(backend, "cmd input tap $x $y", "Tapped ($x,$y)")
                GuiBackendId.BIN_INPUT -> shellInput(backend, "/system/bin/input tap $x $y", "Tapped ($x,$y)")
                else -> fail(backend, "Not applicable")
            }
        }

    private suspend fun doubleTap(x: Int, y: Int, gapMs: Long): GuiExecResult =
        runTouch("Double-tap ($x,$y)") { backend ->
            when (backend) {
                GuiBackendId.ACCESSIBILITY -> {
                    if (!AccessibilityGestureBridge.isAvailable()) accessibilityEnabler.ensureEnabled()
                    if (AccessibilityGestureBridge.isAvailable() && AccessibilityGestureBridge.doubleTap(x, y, gapMs))
                        ok(backend, "Double-tapped ($x,$y)")
                    else fail(backend, if (AccessibilityGestureBridge.isAvailable()) "Gesture cancelled" else "Accessibility service is unavailable (privileged self-authorization was attempted)")
                }
                GuiBackendId.CMD_INPUT, GuiBackendId.BIN_INPUT -> {
                    val prefix = if (backend == GuiBackendId.CMD_INPUT) "cmd input" else "/system/bin/input"
                    val first = privilegeManager.executeShellCommand("$prefix tap $x $y")
                    if (!first.success) return@runTouch fail(backend, first.stderr.ifBlank { "exit=${first.exitCode}" })
                    delay(gapMs.coerceIn(40L, 400L))
                    val second = privilegeManager.executeShellCommand("$prefix tap $x $y")
                    if (second.success) ok(backend, "Double-tapped ($x,$y)")
                    else fail(backend, second.stderr.ifBlank { "Second tap failed" })
                }
                else -> fail(backend, "Not applicable")
            }
        }

    private suspend fun longPress(x: Int, y: Int, durationMs: Long): GuiExecResult {
        val hold = durationMs.coerceIn(200L, 5_000L)
        return runTouch("Long press ($x,$y) for ${hold}ms") { backend ->
            when (backend) {
                GuiBackendId.ACCESSIBILITY -> {
                    if (!AccessibilityGestureBridge.isAvailable()) accessibilityEnabler.ensureEnabled()
                    if (AccessibilityGestureBridge.isAvailable() && AccessibilityGestureBridge.longPress(x, y, hold))
                        ok(backend, "Long-pressed ($x,$y) for ${hold}ms")
                    else fail(backend, if (AccessibilityGestureBridge.isAvailable()) "Gesture cancelled" else "Accessibility service is unavailable (privileged self-authorization was attempted)")
                }
                GuiBackendId.CMD_INPUT ->
                    shellInput(backend, "cmd input swipe $x $y $x $y $hold", "Long-pressed ($x,$y) for ${hold}ms")
                GuiBackendId.BIN_INPUT ->
                    shellInput(backend, "/system/bin/input swipe $x $y $x $y $hold", "Long-pressed ($x,$y) for ${hold}ms")
                else -> fail(backend, "Not applicable")
            }
        }
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): GuiExecResult {
        val dur = durationMs.coerceIn(50L, 5_000L)
        return runTouch("Swipe ($x1,$y1)→($x2,$y2)") { backend ->
            when (backend) {
                GuiBackendId.ACCESSIBILITY -> {
                    if (!AccessibilityGestureBridge.isAvailable()) accessibilityEnabler.ensureEnabled()
                    if (AccessibilityGestureBridge.isAvailable() && AccessibilityGestureBridge.swipe(x1, y1, x2, y2, dur))
                        ok(backend, "Swiped ($x1,$y1)→($x2,$y2)")
                    else fail(backend, if (AccessibilityGestureBridge.isAvailable()) "Gesture cancelled" else "Accessibility service is unavailable (privileged self-authorization was attempted)")
                }
                GuiBackendId.CMD_INPUT ->
                    shellInput(backend, "cmd input swipe $x1 $y1 $x2 $y2 $dur", "Swiped ($x1,$y1)→($x2,$y2)")
                GuiBackendId.BIN_INPUT ->
                    shellInput(backend, "/system/bin/input swipe $x1 $y1 $x2 $y2 $dur", "Swiped ($x1,$y1)→($x2,$y2)")
                else -> fail(backend, "Not applicable")
            }
        }
    }

    private suspend fun key(key: GuiKey): GuiExecResult {
        val attempts = mutableListOf<GuiAttempt>()
        if (key == GuiKey.BACK || key == GuiKey.HOME || key == GuiKey.RECENTS) {
            if (!AccessibilityGestureBridge.isAvailable()) {
                accessibilityEnabler.ensureEnabled()
            }
            val global = when (key) {
                GuiKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GuiKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GuiKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            }
            if (TaiXuGuiAccessibilityService.performGlobal(global)) {
                return GuiExecResult(true, "Pressed key: ${key.name.lowercase()}", GuiBackendId.ACCESSIBILITY)
            }
            attempts += GuiAttempt(GuiBackendId.ACCESSIBILITY, false, "Accessibility global action is unavailable (privileged self-authorization was attempted)")
        }
        for (backend in listOf(GuiBackendId.CMD_INPUT, GuiBackendId.BIN_INPUT)) {
            val cmd = if (backend == GuiBackendId.CMD_INPUT) {
                "cmd input keyevent ${key.keyCode}"
            } else {
                "/system/bin/input keyevent ${key.keyCode}"
            }
            val res = privilegeManager.executeShellCommand(cmd)
            if (res.success) {
                return GuiExecResult(true, "Pressed key: ${key.name.lowercase()} (${key.keyCode})", backend, attempts)
            }
            attempts += GuiAttempt(backend, false, res.stderr.ifBlank { "exit=${res.exitCode}" })
        }
        return GuiExecResult(false, "Key press failed: ${key.name.lowercase()}", attempts = attempts)
    }

    private suspend fun pasteText(text: String): GuiExecResult {
        return GuiClipboardLock.use { pasteTextLocked(text) }
    }

    private suspend fun pasteTextLocked(text: String): GuiExecResult {
        val attempts = mutableListOf<GuiAttempt>()
        if (text.isEmpty()) {
            return GuiExecResult(false, "Cannot paste empty text")
        }

        // 0) 首选无障碍直接设值：支持任意 Unicode，且能读回校验，不依赖剪贴板与键盘快捷键
        trySetFocusedText(text, attempts)?.let { return it }

        // 1) 剪贴板粘贴（快且稳），失败不中断，继续 input text
        val clipOk = writeClipboard(text)
        attempts += GuiAttempt(
            GuiBackendId.CLIPBOARD,
            clipOk,
            if (clipOk) "Wrote ${text.length} characters" else "Failed to write to clipboard",
        )
        if (clipOk) {
            runCatching {
                privilegeManager.executeShellCommand(
                    "cmd clipboard set-primary-clip text/plain ${ShellQuote.of(text)} >/dev/null 2>&1 || true",
                )
            }
            delay(180)
            val pasteKey = GuiKey.PASTE
            for (backend in listOf(GuiBackendId.CMD_INPUT, GuiBackendId.BIN_INPUT)) {
                val cmd = if (backend == GuiBackendId.CMD_INPUT) {
                    "cmd input keyevent ${pasteKey.keyCode}"
                } else {
                    "/system/bin/input keyevent ${pasteKey.keyCode}"
                }
                val res = privilegeManager.executeShellCommand(cmd)
                if (res.success) {
                    return GuiExecResult(true, "Pasted via clipboard (${text.length} characters): $text", backend, attempts)
                }
                attempts += GuiAttempt(backend, false, "KEYCODE_PASTE: ${res.stderr.ifBlank { "exit=${res.exitCode}" }}")
            }
            val chord = privilegeManager.executeShellCommand(
                "/system/bin/input keycombination 113 50 || cmd input keycombination 113 50 || /system/bin/input keyevent 113 50",
            )
            if (chord.success) {
                return GuiExecResult(true, "Pasted via Ctrl+V (${text.length} characters): $text", GuiBackendId.BIN_INPUT, attempts)
            }
            attempts += GuiAttempt(GuiBackendId.BIN_INPUT, false, "Ctrl+V: ${chord.stderr.ifBlank { "exit=${chord.exitCode}" }}")
        }

        // 2) 粘贴失败（或剪贴板不可用）→ 始终尝试 input text（含中文）
        val typed = tryInputText(text, attempts)
        if (typed != null) return typed

        return GuiExecResult(false, "Both clipboard paste and input text failed", attempts = attempts)
    }

    /**
     * 首选后端：对焦点输入框执行 ACTION_SET_TEXT，并读回文本校验是否真的写入。
     * ACTION_SET_TEXT 是幂等设值（覆盖而非追加），因此校验不通过可以安全重试。
     * 降级链（剪贴板 / input text）是追加式的，只有在从未被接受（可确认没写入）时才降级，
     * 否则会造成文本重复——对发消息这类场景，重复比失败更糟。
     */
    private suspend fun trySetFocusedText(text: String, attempts: MutableList<GuiAttempt>): GuiExecResult? {
        if (!AccessibilityGestureBridge.isAvailable()) return null
        var accepted = false
        repeat(2) { round ->
            if (!TaiXuGuiAccessibilityService.setFocusedText(text)) {
                attempts += GuiAttempt(GuiBackendId.ACCESSIBILITY, false, "The current input field rejected ACTION_SET_TEXT")
                return if (accepted) {
                    GuiExecResult(true, "Set text (${text.length} characters; read-back not verified): $text", GuiBackendId.ACCESSIBILITY, attempts)
                } else {
                    null
                }
            }
            accepted = true
            delay(120)
            val actual = TaiXuGuiAccessibilityService.focusedText()
            when {
                actual == null -> {
                    attempts += GuiAttempt(
                        GuiBackendId.ACCESSIBILITY,
                        true,
                        "Attempt ${round + 1} succeeded, but the focused node did not expose text for read-back verification",
                    )
                    return GuiExecResult(true, "Set text (${text.length} characters; read-back unavailable): $text", GuiBackendId.ACCESSIBILITY, attempts)
                }
                actual.contains(text) -> {
                    attempts += GuiAttempt(GuiBackendId.ACCESSIBILITY, true, "Attempt ${round + 1} set the value and passed read-back verification")
                    return GuiExecResult(true, "Set and verified text (${text.length} characters): $text", GuiBackendId.ACCESSIBILITY, attempts)
                }
                else -> attempts += GuiAttempt(
                    GuiBackendId.ACCESSIBILITY,
                    false,
                    "Attempt ${round + 1} read-back mismatch: $actual",
                )
            }
        }
        // 曾被接受但始终读不回目标文本：不再降级，避免追加式后端写出重复内容
        return GuiExecResult(true, "Set text (${text.length} characters; read-back verification failed): $text", GuiBackendId.ACCESSIBILITY, attempts)
    }

    /**
     * `input text` 回退：整串 → 短串分块。不要用外层单引号包中文（部分 ROM 会 NPE）。
     */
    private suspend fun tryInputText(text: String, attempts: MutableList<GuiAttempt>): GuiExecResult? {
        val escaped = escapeForInputText(text)
        for (backend in listOf(GuiBackendId.CMD_INPUT, GuiBackendId.BIN_INPUT)) {
            val prefix = if (backend == GuiBackendId.CMD_INPUT) "cmd input" else "/system/bin/input"
            val res = privilegeManager.executeShellCommand("$prefix text $escaped")
            if (res.success) {
                return GuiExecResult(true, "Sent input text (${text.length} characters): $text", backend, attempts)
            }
            attempts += GuiAttempt(backend, false, "input text: ${res.stderr.ifBlank { "exit=${res.exitCode}" }}")
        }
        // 整串失败时按字符/短块再试（部分机型整串中文会挂、单字可以）
        if (text.length in 2..48) {
            var okCount = 0
            for (chunk in text.chunked(1)) {
                val piece = escapeForInputText(chunk)
                val res = privilegeManager.executeShellCommand("/system/bin/input text $piece")
                if (!res.success) {
                    attempts += GuiAttempt(
                        GuiBackendId.BIN_INPUT,
                        false,
                        "Character-by-character input text failed at $okCount: ${res.stderr.ifBlank { "exit=${res.exitCode}" }}",
                    )
                    break
                }
                okCount++
                delay(30)
            }
            if (okCount == text.length) {
                return GuiExecResult(true, "Sent input text character by character ($okCount characters): $text", GuiBackendId.BIN_INPUT, attempts)
            }
            if (okCount > 0) {
                attempts += GuiAttempt(GuiBackendId.BIN_INPUT, false, "Character-by-character input succeeded only for $okCount/${text.length} characters")
            }
        }
        return null
    }

    /** Android input text 约定：空格写成 %s；特殊 shell 元字符加反斜杠；不加外层引号。 */
    private fun escapeForInputText(text: String): String = buildString(text.length * 2) {
        for (ch in text) {
            when (ch) {
                ' ' -> append("%s")
                '\\', '"', '\'', '`', '$', '&', '<', '>', '|', ';', '(', ')', '#' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }

    private suspend fun runTouch(
        label: String,
        block: suspend (GuiBackendId) -> Pair<Boolean, GuiAttempt>,
    ): GuiExecResult {
        val attempts = mutableListOf<GuiAttempt>()
        for (backend in TOUCH_BACKENDS) {
            val (success, attempt) = block(backend)
            attempts += attempt
            if (success) {
                Log.i(TAG, "$label via ${backend.label}")
                return GuiExecResult(true, attempt.detail, backend, attempts)
            }
        }
        Log.w(TAG, "$label failed after ${attempts.size} backends")
        return GuiExecResult(false, "$label failed", attempts = attempts)
    }

    private suspend fun shellInput(backend: GuiBackendId, command: String, successMessage: String): Pair<Boolean, GuiAttempt> {
        val res = privilegeManager.executeShellCommand(command)
        return if (res.success) {
            true to GuiAttempt(backend, true, successMessage)
        } else {
            false to GuiAttempt(backend, false, res.stderr.ifBlank { "exit=${res.exitCode}" })
        }
    }

    private fun ok(backend: GuiBackendId, detail: String) = true to GuiAttempt(backend, true, detail)
    private fun fail(backend: GuiBackendId, detail: String) = false to GuiAttempt(backend, false, detail)

    private fun writeClipboard(text: String): Boolean {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("taixu-gui", text))
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) return false
        return error == null
    }

    private companion object {
        const val TAG = "TaiXu-GuiToolkit"
        val TOUCH_BACKENDS = listOf(
            GuiBackendId.ACCESSIBILITY,
            GuiBackendId.CMD_INPUT,
            GuiBackendId.BIN_INPUT,
        )
    }
}
