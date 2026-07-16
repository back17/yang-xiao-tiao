package com.skip.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private const val PREFS_NAME = "skip_stats"
        private const val KEY_SKIP_COUNT = "skip_count"
        private const val MIN_BUTTON_SIZE_DP = 12
        private const val MAX_BUTTON_SIZE_DP = 250
        private const val MAX_CORNER_BUTTON_SIZE_DP = 120
        private const val PARENT_CLICK_DEPTH = 8
        private const val DEBOUNCE_MS = 1500L
        private const val WINDOW_CHANGE_DELAY_MS = 120L
        private const val CONTENT_CHANGE_DELAY_MS = 80L
        private const val RETRY_INTERVAL_MS = 350L
        private const val MAX_RETRIES = 36
        private const val CLICK_RECHECK_MS = 100L
        private const val GESTURE_TIMEOUT_MS = 1000L
        private const val MAX_RULE_DELAY_MS = 8000L
        private const val MAX_RULE_TREE_NODES = 2500
        private const val MAX_RULE_EXECUTIONS = 10
        private const val GLOBAL_BACK_ACTION = "GLOBAL_ACTION_BACK"

        var instance: AdSkipService? = null
            private set
        val skipCount: AtomicInteger = AtomicInteger(0)
        var onSkipCountChanged: ((Int) -> Unit)? = null

        fun getSavedSkipCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SKIP_COUNT, 0)
        }

        // 高置信度：明确的跳过/关闭文案（直接点击）
        private val HIGH_CONFIDENCE_PATTERNS = listOf(
            "跳过", "跳过广告", "Skip Ad", "SKIP", "Skip Ad >>",
            "关闭广告", "关闭.*广告",
            "跳过\\s*\\d+", "\\d+s?后跳过", "\\d+s\\s*后跳过",
            "跳过 \\d+", "跳过\\s*\\(\\s*\\d+\\s*\\)",
        )

        // 中置信度：需要结合上下文的文案
        private val MEDIUM_CONFIDENCE_PATTERNS = listOf(
            "跳过\\(?.*\\)?", "Skip", "skip",
            "关闭", "×", "✕", "X",
            "\\d+\\s*[Ss]", "\\d+\\s*秒",
            "残忍拒绝", "残忍离开", "狠心拒绝",
            "暂不安装", "稍后再说", "以后再说",
            "不再显示", "不再提醒", "不再弹出", "不再提示",
            "不,谢谢", "不了谢谢", "不了，谢谢",
        )

        // 低置信度：仅在节点 ID 匹配时使用
        private val LOW_CONFIDENCE_PATTERNS = listOf(
            "知道了", "我知道了", "确定",
            "暂不", "稍后", "以后",
            "不感兴趣", "下次再说", "下次再看",
            "先不了", "不用了", "暂不需要", "还是不了",
        )

        // 预编译正则
        private val HIGH_CONFIDENCE_REGEX = compilePatterns(HIGH_CONFIDENCE_PATTERNS)
        private val MEDIUM_CONFIDENCE_REGEX = compilePatterns(MEDIUM_CONFIDENCE_PATTERNS)
        private val LOW_CONFIDENCE_REGEX = compilePatterns(LOW_CONFIDENCE_PATTERNS)

        private fun compilePatterns(patterns: List<String>): List<PatternEntry> {
            return patterns.map { pattern ->
                val isRegex = pattern.contains("\\") || pattern.contains(".*")
                PatternEntry(
                    pattern = pattern,
                    isRegex = isRegex,
                    compiledRegex = if (isRegex) {
                        try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { null }
                    } else null
                )
            }
        }

        private data class PatternEntry(
            val pattern: String,
            val isRegex: Boolean,
            val compiledRegex: Regex?
        )

    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var lastSkipTime = 0L
    private val skipLock = Any()
    private var clickInProgress = false
    private var windowScanJob: Job? = null
    private var contentScanJob: Job? = null
    private var density = 1f
    private var ruleSessionPackage: String? = null
    private val ruleExecutionCounts = mutableMapOf<String, Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        density = resources.displayMetrics.density
        loadSkipCount()
        Log.d(TAG, "AdSkipService connected, density=$density, skipCount=${skipCount.get()}")

        NotificationHelper.createChannels(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID_SERVICE,
            NotificationHelper.buildServiceNotification(this, skipCount.get())
        )
        serviceScope.launch(Dispatchers.IO) {
            PopupRuleRepository.preload(applicationContext)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        resolveActivePackage(event)?.let(::updateRuleSession)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowStateChanged(event)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> handleWindowContentChanged(event)

            else -> Unit
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = resolveActivePackage(event) ?: return
        if (AdSkipPolicy.isIgnoredPackage(packageName)) return
        if (packageName == this.packageName) return

        Log.d(TAG, "Window changed: $packageName")

        // 启动重试逻辑：多次尝试查找跳过按钮
        windowScanJob?.cancel()
        windowScanJob = serviceScope.launch {
            for (retry in 0 until MAX_RETRIES) {
                delay(if (retry == 0) WINDOW_CHANGE_DELAY_MS else RETRY_INTERVAL_MS)
                awaitClickAllowed()

                val root = rootInActiveWindow ?: continue
                if (!isExpectedWindow(root, packageName)) continue
                val found = findAndClickSkipButton(root, packageName)
                if (found) {
                    Log.d(TAG, "Skip button found on retry $retry")
                    return@launch
                }
            }
            Log.d(TAG, "No skip button found after $MAX_RETRIES retries for $packageName")
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = resolveActivePackage(event) ?: return
        if (AdSkipPolicy.isIgnoredPackage(packageName)) return
        if (packageName == this.packageName) return

        // Keep the first pending scan. Repeated content events must not postpone it forever.
        if (contentScanJob?.isActive == true) return
        contentScanJob = serviceScope.launch {
            delay(CONTENT_CHANGE_DELAY_MS)
            awaitClickAllowed()
            val root = rootInActiveWindow ?: return@launch
            if (!isExpectedWindow(root, packageName)) return@launch
            findAndClickSkipButton(root, packageName)
        }
    }

    private suspend fun findAndClickSkipButton(root: AccessibilityNodeInfo, packageName: String): Boolean {
        try {
            if (tryImportedRules(root, packageName)) return true

            val skipNode = findSkipNode(root) ?: return false
            val nodeText = skipNode.text?.toString() ?: skipNode.contentDescription?.toString() ?: ""
            val nodeRect = Rect()
            skipNode.getBoundsInScreen(nodeRect)
            Log.d(TAG, "Found skip button: '$nodeText' at $nodeRect in $packageName")

            if (!beginClickAttempt()) return false
            try {
                if (performClick(skipNode)) {
                    recordSkipSuccess()
                    return true
                }

                Log.w(TAG, "Click failed for node: '$nodeText'")
                val gestureCompleted = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                    tryGestureClick(nodeRect)
                } ?: false
                if (gestureCompleted) {
                    recordSkipSuccess()
                    return true
                }
                return false
            } finally {
                endClickAttempt()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error finding skip button", e)
            return false
        }
    }

    private suspend fun tryImportedRules(
        root: AccessibilityNodeInfo,
        packageName: String,
    ): Boolean {
        val ruleSet = PopupRuleRepository.rulesFor(this, packageName) ?: return false
        if (ruleSet.serviceRule || ruleSet.rules.isEmpty()) return false

        var nodes = collectNodes(root)
        for ((index, rule) in ruleSet.rules.withIndex()) {
            val executionLimit = (rule.times ?: ruleSet.times ?: 1)
                .coerceIn(0, MAX_RULE_EXECUTIONS)
            if (executionLimit == 0) continue

            val ruleKey = "$packageName#$index#${rule.id}#${rule.action}"
            if (!triggerMatches(nodes, rule.id)) {
                ruleExecutionCounts.remove(ruleKey)
                continue
            }
            if ((ruleExecutionCounts[ruleKey] ?: 0) >= executionLimit) continue

            if (AdSkipPolicy.isUnsafeImportedAction(rule.action)) {
                Log.w(TAG, "Blocked unsafe imported action '${rule.action}' in $packageName")
                continue
            }

            if (!beginClickAttempt()) return false
            try {
                val ruleDelay = rule.delayMs.coerceIn(0L, MAX_RULE_DELAY_MS)
                if (ruleDelay > 0L) {
                    delay(ruleDelay)
                    val refreshedRoot = rootInActiveWindow ?: return false
                    if (!isExpectedWindow(refreshedRoot, packageName)) return false
                    nodes = collectNodes(refreshedRoot)
                    if (!triggerMatches(nodes, rule.id)) continue
                }

                if (executeImportedAction(ruleSet, rule, nodes, packageName)) {
                    ruleExecutionCounts[ruleKey] = (ruleExecutionCounts[ruleKey] ?: 0) + 1
                    Log.d(TAG, "Imported rule matched: '${rule.id}' -> '${rule.action}' in $packageName")
                    recordSkipSuccess()
                    return true
                }
            } finally {
                endClickAttempt()
            }
        }
        return false
    }

    private suspend fun executeImportedAction(
        ruleSet: AppPopupRules,
        rule: PopupRule,
        nodes: List<AccessibilityNodeInfo>,
        packageName: String,
    ): Boolean {
        if (rule.action.trim().equals(GLOBAL_BACK_ACTION, ignoreCase = true)) {
            return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        val selector = RuleSelector.actionSelector(rule.action) ?: return false
        val actionNode = findNodeForSelector(nodes, selector, packageName) ?: return false
        val rect = Rect().also(actionNode::getBoundsInScreen)

        return if (ruleSet.clickWay == 1) {
            gestureClick(rect) || performClick(actionNode)
        } else {
            performClick(actionNode) || gestureClick(rect)
        }
    }

    private suspend fun gestureClick(rect: Rect): Boolean {
        if (rect.isEmpty) return false
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            tryGestureClick(rect)
        } ?: false
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.size < MAX_RULE_TREE_NODES) {
            val node = queue.removeFirst()
            nodes.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return nodes
    }

    private fun triggerMatches(
        nodes: List<AccessibilityNodeInfo>,
        expression: String,
    ): Boolean {
        val selectors = RuleSelector.triggerSelectors(expression)
        return selectors.isNotEmpty() && selectors.all { selector ->
            nodes.any { node -> matchesNodeSelector(node, selector) }
        }
    }

    private fun findNodeForSelector(
        nodes: List<AccessibilityNodeInfo>,
        selector: RuleSelector.Selector,
        packageName: String,
    ): AccessibilityNodeInfo? {
        val matches = nodes.filter { node ->
            val nodePackage = node.packageName?.toString()
            (nodePackage == null || nodePackage == packageName) &&
                    isRuleActionCandidate(node) &&
                    matchesNodeSelector(node, selector)
        }
        return matches.firstOrNull { it.isClickable || isParentClickable(it) }
            ?: matches.firstOrNull()
    }

    private fun matchesNodeSelector(
        node: AccessibilityNodeInfo,
        selector: RuleSelector.Selector,
    ): Boolean {
        if (!node.isVisibleToUser) return false
        selector.whole?.let { whole ->
            if (nodeMatches(node, whole)) return true
        }

        val path = selector.path
        if (path.isEmpty() || !nodeMatches(node, path.last())) return false
        if (path.size == 1) return true

        var cursor: AccessibilityNodeInfo? = node.parent
        for (index in path.lastIndex - 1 downTo 0) {
            var matchedAncestor: AccessibilityNodeInfo? = null
            while (cursor != null) {
                val current = cursor
                if (nodeMatches(current, path[index])) {
                    matchedAncestor = current
                    break
                }
                cursor = current.parent
            }
            val matched = matchedAncestor ?: return false
            cursor = matched.parent
        }
        return true
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, query: RuleSelector.Query): Boolean {
        return nodeValues(node).any { value -> RuleSelector.matches(query, value) }
    }

    private fun nodeValues(node: AccessibilityNodeInfo): List<String> {
        return buildList {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(::add)
            node.viewIdResourceName?.takeIf(String::isNotBlank)?.let { viewId ->
                add(viewId)
                viewId.substringAfterLast('/').takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun isRuleActionCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val rect = Rect().also(node::getBoundsInScreen)
        return !rect.isEmpty
    }

    private fun updateRuleSession(packageName: String) {
        if (ruleSessionPackage == packageName) return
        ruleSessionPackage = packageName
        ruleExecutionCounts.clear()
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // 方式1：直接点击节点
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) return true
        }

        // 方式2：向上查找可点击的父节点
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < PARENT_CLICK_DEPTH) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            parent = parent.parent
            depth++
        }

        // 方式3：尝试 ACTION_CLICK 即使节点不可点击（某些自定义 View）
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return result
    }

    private suspend fun tryGestureClick(rect: Rect): Boolean {
        return try {
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            suspendCancellableCoroutine { continuation ->
                val dispatched = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && continuation.isActive) continuation.resume(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gesture click failed", e)
            false
        }
    }

    private fun beginClickAttempt(): Boolean = synchronized(skipLock) {
        val now = System.currentTimeMillis()
        if (clickInProgress || now - lastSkipTime < DEBOUNCE_MS) {
            false
        } else {
            clickInProgress = true
            true
        }
    }

    private fun endClickAttempt() {
        synchronized(skipLock) {
            clickInProgress = false
        }
    }

    private suspend fun awaitClickAllowed() {
        while (currentCoroutineContext().isActive) {
            val waitMs = synchronized(skipLock) {
                if (clickInProgress) {
                    CLICK_RECHECK_MS
                } else {
                    (DEBOUNCE_MS - (System.currentTimeMillis() - lastSkipTime))
                        .coerceAtLeast(0L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs.coerceAtMost(CLICK_RECHECK_MS))
        }
    }

    private fun resolveActivePackage(event: AccessibilityEvent): String? {
        return rootInActiveWindow?.packageName?.toString()
            ?: event.packageName?.toString()
    }

    private fun isExpectedWindow(root: AccessibilityNodeInfo, expectedPackage: String): Boolean {
        return AdSkipPolicy.isExpectedWindow(expectedPackage, root.packageName?.toString())
    }

    private fun recordSkipSuccess() {
        synchronized(skipLock) {
            lastSkipTime = System.currentTimeMillis()
        }
        val newCount = skipCount.incrementAndGet()
        saveSkipCount(newCount)
        onSkipCountChanged?.invoke(newCount)
        NotificationHelper.updateServiceNotification(this, newCount)
        vibrate()
        Log.d(TAG, "Ad skipped! Total: $newCount")
    }

    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rootRect = Rect().also(root::getBoundsInScreen)
        var hasAdContext = false
        var cornerCandidate: AccessibilityNodeInfo? = null
        var cornerCandidateArea = Long.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val matchText = "$text $contentDesc".trim()

            if (matchText.isNotEmpty() && AdSkipPolicy.containsAdContext(matchText)) {
                hasAdContext = true
            }

            // 高置信度匹配：文本匹配即可
            if (matchText.isNotEmpty() && matchesPatterns(matchText, HIGH_CONFIDENCE_REGEX)) {
                if (isValidSkipNode(node)) return node
            }

            // 中置信度匹配：需要节点看起来像按钮
            if (matchText.isNotEmpty() && matchesPatterns(matchText, MEDIUM_CONFIDENCE_REGEX)) {
                if (isValidSkipNode(node) && looksLikeButton(node)) return node
            }

            // 低置信度匹配：需要 viewId 也匹配
            if (matchText.isNotEmpty() && matchesPatterns(matchText, LOW_CONFIDENCE_REGEX)) {
                if (isValidSkipNode(node) && isSkipViewId(viewId)) return node
            }

            // viewId 匹配
            if (viewId.isNotEmpty() && isSkipViewId(viewId) && isValidSkipNode(node)) {
                return node
            }

            if (isTopRightCloseCandidate(node, rootRect)) {
                val rect = Rect().also(node::getBoundsInScreen)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area < cornerCandidateArea) {
                    cornerCandidate = node
                    cornerCandidateArea = area
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return cornerCandidate.takeIf { hasAdContext }
    }

    private fun isTopRightCloseCandidate(node: AccessibilityNodeInfo, rootRect: Rect): Boolean {
        if (!node.isVisibleToUser || node.childCount > 0 || rootRect.isEmpty) return false
        if (!node.isClickable && !isParentClickable(node)) return false

        val className = node.className?.toString() ?: return false
        val isIconLike = className.contains("Image", ignoreCase = true) ||
                className.contains("Button", ignoreCase = true) ||
                className.endsWith("View", ignoreCase = true)
        if (!isIconLike) return false

        val rect = Rect().also(node::getBoundsInScreen)
        if (rect.isEmpty) return false
        val widthDp = rect.width() / density
        val heightDp = rect.height() / density
        if (widthDp !in MIN_BUTTON_SIZE_DP.toFloat()..MAX_CORNER_BUTTON_SIZE_DP.toFloat()) return false
        if (heightDp !in MIN_BUTTON_SIZE_DP.toFloat()..MAX_CORNER_BUTTON_SIZE_DP.toFloat()) return false

        val minCenterX = rootRect.left + rootRect.width() * 0.68f
        val maxCenterY = rootRect.top + rootRect.height() * 0.30f
        return rect.centerX() >= minCenterX && rect.centerY() <= maxCenterY
    }

    private fun isValidSkipNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        return hasValidSize(node)
    }

    private fun hasValidSize(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() == 0 || rect.height() == 0) return false
        val widthDp = (rect.width() / density)
        val heightDp = (rect.height() / density)
        return widthDp in MIN_BUTTON_SIZE_DP.toFloat()..MAX_BUTTON_SIZE_DP.toFloat() &&
                heightDp in MIN_BUTTON_SIZE_DP.toFloat()..MAX_BUTTON_SIZE_DP.toFloat()
    }

    private fun looksLikeButton(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        val isButtonLike = className.contains("Button") ||
                className.contains("TextView") ||
                className.contains("ImageView") ||
                className.contains("Image") ||
                className.contains("View") ||
                className.contains("Layout") ||
                className.contains("FrameLayout") ||
                className.contains("LinearLayout") ||
                className.contains("RelativeLayout")
        val isClickable = node.isClickable || isParentClickable(node)
        return isButtonLike && isClickable
    }

    private fun matchesPatterns(text: String, patterns: List<PatternEntry>): Boolean {
        return patterns.any { entry ->
            if (entry.isRegex && entry.compiledRegex != null) {
                entry.compiledRegex.containsMatchIn(text)
            } else {
                text.contains(entry.pattern, ignoreCase = true)
            }
        }
    }

    private fun isSkipViewId(viewId: String): Boolean {
        val skipIds = listOf(
            "skip", "close", "dismiss", "jump", "ad_skip",
            "btn_skip", "iv_close", "btn_close", "skip_btn",
            "close_btn", "splash_skip", "ad_close", "skip_button",
            "close_button", "ad_skip_button", "splash_close",
            "btn_jump", "jump_btn", "iv_skip", "skip_ad",
            "close_ad", "ad_close_btn", "skip_view",
            "count_down", "countdown", "timer",
        )
        val lowerId = viewId.lowercase()
        return skipIds.any { lowerId.contains(it) }
    }

    private fun isParentClickable(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < PARENT_CLICK_DEPTH) {
            if (parent.isClickable) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate failed", e)
        }
    }

    private fun loadSkipCount() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        skipCount.set(prefs.getInt(KEY_SKIP_COUNT, 0))
    }

    private fun saveSkipCount(count: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putInt(KEY_SKIP_COUNT, count)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AdSkipService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        onSkipCountChanged = null
        serviceScope.cancel()
        Log.d(TAG, "AdSkipService destroyed")
    }
}
