package com.babenko.rescueservice.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.babenko.rescueservice.R
import com.babenko.rescueservice.core.Logger
import com.babenko.rescueservice.data.SettingsManager
import com.babenko.rescueservice.voice.TtsManager
import com.babenko.rescueservice.voice.VoiceSessionService
import android.content.ComponentName
import android.view.MotionEvent
import com.babenko.rescueservice.core.AssistantLifecycleManager
import com.babenko.rescueservice.core.ClickElementEvent
import com.babenko.rescueservice.core.EventBus
import com.babenko.rescueservice.core.GlobalActionEvent
import com.babenko.rescueservice.core.HighlightElementEvent
import com.babenko.rescueservice.core.ScrollEvent
import com.babenko.rescueservice.core.TtsPlaybackFinished
import com.babenko.rescueservice.voice.CommandReceiver
import com.babenko.rescueservice.voice.ConversationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class RedHelperAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private var isClickLocked = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlayHighlighter: OverlayHighlighter
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastScreenHash: String? = null
    private var followUpSentInThisWindow: Boolean = false
    private var wasWindowActive: Boolean = false

    // --- Debounce Runnable ---
    private var debounceRunnable: Runnable? = null

    private lateinit var localizedContext: Context

    // --- Variables for Drag-and-Drop ---
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var overlayParams: WindowManager.LayoutParams? = null

    companion object {
        private const val CLICK_LOCK_MS = 2000L
        private const val DEBOUNCE_DELAY_MS = 1000L
    }

    private val localeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConversationManager.ACTION_LOCALE_CHANGED) {
                Logger.d("RedHelperAccessibilityService: Received locale change broadcast. Updating context.")
                updateLocalizedContext()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.d("RedHelperAccessibilityService connected")
        updateLocalizedContext()
        windowManager = getSystemService<WindowManager>()
        overlayHighlighter = OverlayHighlighter(this)
        removeFloatingButtonSafely()
        showFloatingButton()
        serviceInfo = serviceInfo?.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        subscribeToEvents()
        val filter = IntentFilter(ConversationManager.ACTION_LOCALE_CHANGED)
        ContextCompat.registerReceiver(this, localeChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeFloatingButtonSafely()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlayHighlighter.hide()
        removeFloatingButtonSafely()
        unregisterReceiver(localeChangeReceiver)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Logger.d("Configuration changed. Reloading settings.")
        updateLocalizedContext()
    }

    private fun updateLocalizedContext() {
        val settings = SettingsManager.getInstance(this)
        settings.loadSettings() // Ensure we get the latest language
        val lang = settings.getLanguage()
        val locale = Locale.forLanguageTag(lang)
        val config = android.content.res.Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        localizedContext = createConfigurationContext(config)
        SettingsManager.updateContext(localizedContext)
        Logger.d("Localized context updated to $lang for Service and SettingsManager")
    }

    private fun showFloatingButton() {
        // 1. Protection against duplication
        if (floatingButton != null) return

        val wm = windowManager ?: return
        val themedContext = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar)
        val inflater = LayoutInflater.from(themedContext)

        try {
            val view = inflater.inflate(R.layout.floating_action_button, null)

            // 2. ROBUST WINDOW PARAMETERS
            val metrics = resources.displayMetrics
            overlayParams = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT

                // TYPE_ACCESSIBILITY_OVERLAY is the most reliable for accessibility services
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                format = PixelFormat.TRANSLUCENT

                // Use Top|Start for reliable absolute positioning
                gravity = Gravity.TOP or Gravity.START

                // Initial position: Right Center
                // Right side: width - offset (200px)
                x = metrics.widthPixels - 200
                // Vertical center: height / 2
                y = metrics.heightPixels / 2
            }

            // 3. DRAG LOGIC (Touch Listener)
            view.setOnTouchListener { v, event ->
                val lp = overlayParams ?: return@setOnTouchListener false
                val wmRef = windowManager ?: return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        // Threshold to detect drag (10px)
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                            lp.x = initialX + dx
                            lp.y = initialY + dy
                            try {
                                wmRef.updateViewLayout(view, lp)
                            } catch (e: Exception) {
                                Logger.d("Error updating view layout: ${e.message}")
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            v.performClick()
                            handleFloatingButtonClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            wm.addView(view, overlayParams)
            floatingButton = view
        } catch (e: Throwable) {
            Logger.e(e, "Failed to inflate/add floating button overlay")
        }
    }

    private fun handleFloatingButtonClick() {
        if (isClickLocked) return
        isClickLocked = true
        floatingButton?.isClickable = false
        floatingButton?.alpha = 0.6f

        val screenContext = getScreenContext(rootInActiveWindow)

        try {
            val settings = SettingsManager.getInstance(this)
            settings.loadSettings() // Force a reload of preferences

            var userName = settings.getUserName()
            val lang = settings.getLanguage()

            // Hard logic fix: If the stored name is the English default but the language is Russian, override it.
            if (!settings.isUserNameSet() || (userName == "My Lord" && lang.startsWith("ru"))) {
                userName = localizedContext.getString(R.string.default_user_name)
            }

            val phrase = localizedContext.getString(R.string.im_listening, userName)
            TtsManager.speak(context = this, text = phrase, queueMode = TextToSpeech.QUEUE_FLUSH, onDone = {
                try {
                    VoiceSessionService.startSession(this, screenContext = screenContext)
                } catch (e: Exception) {
                    Logger.e(e, "Failed to start VoiceSessionService after TTS prelude")
                }
            })
        } catch (e: Exception) {
            Logger.e(e, "Red button prelude failed; starting SR directly as fallback")
            try {
                VoiceSessionService.startSession(this, screenContext = screenContext)
            } catch (e2: Exception) {
                Logger.e(e2, "Failed to start VoiceSessionService from red button (fallback)")
            }
        } finally {
            handler.postDelayed({
                isClickLocked = false
                floatingButton?.isClickable = true
                floatingButton?.alpha = 1f
            }, CLICK_LOCK_MS)
        }
    }

    private fun removeFloatingButtonSafely() {
        windowManager?.let { wm ->
            floatingButton?.let { view ->
                try {
                    wm.removeView(view)
                } catch (e: Throwable) {
                    Logger.e(e, "Failed to remove floating button overlay")
                } finally {
                    floatingButton = null
                }
            }
        }
    }

    private fun getScreenContext(rootNode: AccessibilityNodeInfo?): String {
        if (rootNode == null) return getString(R.string.screen_context_unavailable)
        val contextBuilder = StringBuilder()
        contextBuilder.append(getString(R.string.screen_context_app)).append(rootNode.packageName ?: getString(R.string.screen_context_unknown)).append("\n")
        val traversedNodes = HashSet<AccessibilityNodeInfo>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || !node.isVisibleToUser || !traversedNodes.add(node)) return
            val text = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()
            val hasText = !text.isNullOrBlank()
            val hasContentDesc = !contentDesc.isNullOrBlank()
            if (hasText || hasContentDesc) {
                contextBuilder.append("  ".repeat(depth))
                if (hasText) contextBuilder.append(getString(R.string.screen_context_text)).append("\"").append(text).append("\"")
                if (hasContentDesc) {
                    if (hasText) contextBuilder.append(" ")
                    contextBuilder.append(getString(R.string.screen_context_description)).append("\"").append(contentDesc).append("\"")
                }
                contextBuilder.append("\n")
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }
        traverse(rootNode, 0)
        return contextBuilder.toString()
    }

    private fun computeScreenHash(contextStr: String): String {
        val slice = if (contextStr.length > 2000) contextStr.substring(0, 2000) else contextStr
        return slice.hashCode().toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 1. Manage window active state instantly
        val windowActive = AssistantLifecycleManager.isFollowUpWindowActive()
        if (!windowActive) {
            if (wasWindowActive) {
                followUpSentInThisWindow = false
                lastScreenHash = null
            }
            wasWindowActive = false
            return
        }
        wasWindowActive = true

        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // 2. DEBOUNCE LOGIC
        // Cancel previous pending check
        debounceRunnable?.let { handler.removeCallbacks(it) }

        // Schedule new check
        debounceRunnable = Runnable {
            val root = rootInActiveWindow ?: return@Runnable
            val contextStr = getScreenContext(root)
            val hash = computeScreenHash(contextStr)

            if (hash == lastScreenHash) return@Runnable
            lastScreenHash = hash

            if (followUpSentInThisWindow) return@Runnable

            try {
                val intent = Intent(CommandReceiver.ACTION_PROCESS_COMMAND).apply {
                    component = ComponentName(this@RedHelperAccessibilityService, CommandReceiver::class.java)
                    `package` = packageName
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(CommandReceiver.EXTRA_RECOGNIZED_TEXT, "FOLLOW_UP")
                    putExtra(CommandReceiver.EXTRA_SCREEN_CONTEXT, contextStr)
                }
                sendBroadcast(intent)
                AssistantLifecycleManager.onScreenChangedForFollowUp(this@RedHelperAccessibilityService, contextStr)
                followUpSentInThisWindow = true
                AssistantLifecycleManager.cancelFollowUpWindow()
            } catch (e: Exception) {
                Logger.e(e, "Failed to send follow-up broadcast from AccessibilityService")
            }
        }

        // Wait for screen to stabilize (1 second)
        handler.postDelayed(debounceRunnable!!, DEBOUNCE_DELAY_MS)
    }

    private fun subscribeToEvents() {
        serviceScope.launch {
            EventBus.events.collectLatest { event ->
                withContext(Dispatchers.Main) { // Actions on UI must be on the main thread
                    when (event) {
                        is HighlightElementEvent -> handleHighlightEvent(event)
                        is ClickElementEvent -> {
                            // Reset flag to allow follow-up after screen change
                            followUpSentInThisWindow = false
                            handleClickEvent(event)
                        }
                        is GlobalActionEvent -> {
                            followUpSentInThisWindow = false
                            Logger.d("Performing global action: ${event.actionId}")
                            performGlobalAction(event.actionId)
                        }
                        is TtsPlaybackFinished -> {
                            // Not handled in this service
                        }
                        is ScrollEvent -> {
                            // Reset flag to allow follow-up after screen change
                            followUpSentInThisWindow = false
                            Logger.d("Received ScrollEvent: ${event.direction}")
                            performScroll(event.direction)
                        }
                    }
                }
            }
        }
    }

    private fun handleClickEvent(event: ClickElementEvent) {
        val root = rootInActiveWindow ?: return
        val selector = event.selector
        val by = selector["by"]
        val value = selector["value"]

        if (by == null || value == null) {
            Logger.d("Click event failed: selector is null")
            return
        }

        var targetNode: AccessibilityNodeInfo? = null

        when (by) {
            "text" -> {
                // Try standard search first
                val nodes = root.findAccessibilityNodeInfosByText(value)
                targetNode = nodes.firstOrNull { it.isVisibleToUser }
                if (targetNode == null) {
                    Logger.d("Standard text search failed for '$value'. Trying recursive FUZZY fallback.")
                    // Use new fuzzy match logic
                    targetNode = findNodeByTextRecursively(root, value)
                }
            }
            "id" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId(value)
                targetNode = nodes.firstOrNull { it.isVisibleToUser }
            }
            "content_desc" -> {
                targetNode = findNodeByTextRecursively(root, value)
            }
        }

        if (targetNode != null) {
            Logger.d("Performing click on element with selector: $selector")
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.recycle()
        } else {
            Logger.d("Could not find element to click with selector: $selector")
            // Inform user via TTS if element is not found
            TtsManager.speak(this, "Я не вижу элемент $value", TextToSpeech.QUEUE_ADD)
        }
    }

    // --- NEW SCROLL LOGIC (Helpers) ---
    private fun performScroll(direction: String) {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollableNode(root)
        if (scrollable != null) {
            val action = if (direction == "down")
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD // Content moves up, view moves down
            else
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            Logger.d("Scrolling $direction on node: ${scrollable.viewIdResourceName}")
            scrollable.performAction(action)
            scrollable.recycle()
        } else {
            Logger.d("No scrollable node found")
            TtsManager.speak(this, "Здесь нельзя прокрутить", TextToSpeech.QUEUE_ADD)
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            // We need to recycle children if they are NOT the result, but keep the result open
            val found = findScrollableNode(child)
            if (found != null) {
                // If child itself wasn't the result but one of its descendants was,
                // we recycle the child container as we go back up the stack
                if (child != found) {
                    child.recycle()
                }
                return found
            }
            child.recycle()
        }
        return null
    }

    // --- SOFT MATCH LOGIC ---
    private fun softMatch(nodeText: String?, targetText: String): Boolean {
        if (nodeText.isNullOrBlank()) return false

        val nText = nodeText.trim().lowercase()
        val tText = targetText.trim().lowercase()

        // 1. Exact or Substring match (Bidirectional)
        if (nText.contains(tText) || tText.contains(nText)) return true

        // 2. Token-based match (Word intersection)
        // Split by non-letter characters to handle punctuation
        val targetWords = tText.split(Regex("[^\\p{L}0-9]+")).filter { it.length > 2 }
        val nodeWords = nText.split(Regex("[^\\p{L}0-9]+")).filter { it.length > 2 }

        // If ANY significant word from target exists in node, consider it a candidate.
        for (tw in targetWords) {
            if (nodeWords.contains(tw)) return true
            // Check for "root" match for Russian morphology (simplistic)
            for (nw in nodeWords) {
                if (tw.startsWith(nw.take(3)) && nw.startsWith(tw.take(3))) return true
            }
        }

        return false
    }

    // --- IMPROVED SEARCH AND HIGHLIGHTING ---
    private fun findNodeByTextRecursively(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (!node.isVisibleToUser || !visitedNodes.add(node)) {
                continue
            }

            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()

            // USE SOFT MATCH
            if (softMatch(nodeText, text) || softMatch(nodeDesc, text)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    private fun handleHighlightEvent(event: HighlightElementEvent) {
        val root = rootInActiveWindow ?: return
        val selector = event.selector
        val by = selector["by"]
        val value = selector["value"]

        if (by == null || value == null) return

        var targetNode: AccessibilityNodeInfo? = null

        when (by) {
            "text" -> {
                val nodes = root.findAccessibilityNodeInfosByText(value)
                targetNode = nodes.firstOrNull { it.isVisibleToUser }
                // If standard search fails, try recursive fallback
                if (targetNode == null) {
                    Logger.d("Standard text search failed for '$value'. Trying recursive fallback.")
                    targetNode = findNodeByTextRecursively(root, value)
                }
            }
            "id" -> {
                val nodes = root.findAccessibilityNodeInfosByViewId(value)
                targetNode = nodes.firstOrNull { it.isVisibleToUser }
            }
            "content_desc" -> {
                targetNode = findNodeByTextRecursively(root, value)
            }
        }

        if (targetNode != null) {
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            Logger.d("Highlighting element at bounds: $rect")
            // Increase highlight duration to 5 seconds
            overlayHighlighter.show(rect, 5000L)
            targetNode.recycle()
        } else {
            Logger.d("Could not find element to highlight with selector: $selector")
        }
    }
}