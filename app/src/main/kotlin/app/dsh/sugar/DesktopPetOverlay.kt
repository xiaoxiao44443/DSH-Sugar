package app.dsh.sugar

import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import app.dsh.sugar.runtime.MobileUseTasks
import kotlin.math.roundToInt
import kotlin.random.Random

/** Character and caption each have a tightly sized input window. */
internal class DesktopPetOverlay(private val context: Context, private val onDisabled: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val preferences = DesktopPets.prefs(context)
    private val ops = context.getSystemService(AppOpsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val refreshTask = Runnable { refresh() }
    private val changed: () -> Unit = {
        main.removeCallbacks(refreshTask)
        main.post(refreshTask)
        Unit
    }
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        window?.speechChanged(key)
        if (key != "overlayX" && key != "overlayY") changed()
    }
    private val permissionChanged = AppOpsManager.OnOpChangedListener { _, _ -> changed() }
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    main.removeCallbacks(wakeRetry)
                    removeWindow()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> recoverAfterWake()
            }
        }
    }
    private var running = false
    private var screenOff = !power.isInteractive
    private var window: PetWindow? = null
    private val wakeRetry = Runnable { tryRestoreWindow() }
    private var wakeAttempts = 0

    val enabled: Boolean get() = DesktopPets.enabled(context) && Settings.canDrawOverlays(context)

    fun start() {
        running = true
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged)
        val screenEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenChanged, screenEvents, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(screenChanged, screenEvents)
        ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionChanged)
        DesktopPetActivity.addListener(changed)
        MobileUseTasks.addUiListener(changed)
        if (power.isInteractive) recoverAfterWake() else refresh()
    }

    // Delivery of SCREEN_ON/USER_PRESENT and availability of the unlocked window token can race.
    // Retry only during this wake transition; sleep, disabling the pet, or service teardown cancels it.
    fun recoverAfterWake() {
        if (!running) return
        screenOff = false
        wakeAttempts = 0
        main.removeCallbacks(wakeRetry)
        tryRestoreWindow()
    }

    private fun tryRestoreWindow() {
        if (!running || screenOff) return
        refresh()
        if (enabled && window?.attached != true && wakeAttempts < 5) {
            main.postDelayed(wakeRetry, 200L shl wakeAttempts)
            wakeAttempts++
        }
    }

    fun refresh() {
        if (!running) return
        if (!enabled) {
            main.removeCallbacks(wakeRetry)
            removeWindow()
            onDisabled()
            return
        }
        if (screenOff || !power.isInteractive || keyguard.isKeyguardLocked) {
            removeWindow()
            return
        }
        val displayState = DesktopPetActivity.status().display(MobileUseTasks.activeTasks())
        try {
            val overlay = window ?: run {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
                PetWindow(display).also { window = it }
            }
            overlay.show(DesktopPets.selected(context), displayState, DesktopPets.size(context))
        } catch (_: WindowManager.BadTokenException) {
            removeWindow()
        } catch (_: SecurityException) {
            // Permission can change between canDrawOverlays and the window transaction.
            removeWindow()
        } catch (_: WindowManager.InvalidDisplayException) {
            removeWindow()
        }
    }

    private fun removeWindow() {
        window?.remove()
        window = null
    }

    fun close() {
        running = false
        preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        context.unregisterReceiver(screenChanged)
        ops.stopWatchingMode(permissionChanged)
        DesktopPetActivity.removeListener(changed)
        MobileUseTasks.removeUiListener(changed)
        main.removeCallbacks(refreshTask)
        main.removeCallbacks(wakeRetry)
        removeWindow()
    }

    private inner class PetWindow(display: Display) {
        private val ui = if (Build.VERSION.SDK_INT >= 30) {
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else context
        private val wm = ui.getSystemService(WindowManager::class.java)
        private val character = object : ImageView(ui) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                queueLayoutRefresh()
            }

            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                queueLayoutRefresh()
                return super.onApplyWindowInsets(insets)
            }
        }.apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val attached: Boolean get() = character.parent != null
        private val bubble = DesktopPetBubble(ui)
        private val characterParams = params("Sugar desktop pet")
        private val bubbleParams = params("Sugar desktop pet status")
        private val replies = PetReplySheet(ui, changed)
        private var dockTarget: Boolean? = null
        private var dockReveal = 1f
        private var noteDrawnLeft = false
        private var dockAnimator: ValueAnimator? = null
        private var dragFramePending = false
        private val dragFrame = Runnable {
            dragFramePending = false
            if (attached) position()
        }
        private var attachedNote: DesktopPetAnswerNote? = null
        private val noteParams = params("Sugar desktop pet reply")
        private val displayedPhrase: String? get() =
            if (spokenResult?.kind == "reply") phrase else replies.prompt.takeIf { pause == null } ?: phrase
        private var pack: DesktopPets.Pack? = null
        private var aboveInsets = bubble.paddingTop + bubble.paddingBottom
        private val tailInset = (24 * ui.resources.displayMetrics.density).roundToInt()
        private var bubbleBelow = false
        private var bubbleFrame = R.drawable.sugar_pet_bubble
        private var key: Pair<String, String>? = null
        private var speechLanguage: String? = null
        private var spokenState: String? = null
        private var spokenSession: String? = null
        private var spokenResult: DesktopPetActivity.Result? = null
        private var readingReply: DesktopPetActivity.Result? = null
        private val finishReply = Runnable {
            val finished = readingReply
            readingReply = null
            if (finished != null) DesktopPetActivity.dismiss(finished)
            if (window === this) refresh()
        }
        private var spokenPhase: String? = null
        private var spokenCaption: String? = null
        private val phoneProgress = PhoneCaptionProgress()
        private val updatePhoneDetail = Runnable { if (window === this) refresh() }
        private var speechCategory: String? = null
        private var pause: PetSpeechPause? = null
        private val resumeSpeech = Runnable { if (window === this) refresh() }
        private val lastIdleLines = mutableMapOf<String, String>()
        private var showingPoke = false
        private var phrase: String? = null
        private val idleChatter = Runnable {
            if (spokenState == "idle" && spokenResult == null) {
                setPhrase(if (phrase == null) nextIdlePhrase("idle") else null)
                refresh()
                if (window === this && spokenState == "idle" && spokenResult == null) scheduleIdle()
            }
        }
        private var xFraction = preferences.getFloat("overlayX", 0.97f)
        private var yFraction = preferences.getFloat("overlayY", 0.7f)
        private var travel = Point()
        private var area = Point()
        private var petSizeDp = DesktopPets.size(context)
        private val refreshLayout = Runnable { if (attached) layoutPet() }
        private var startX = 0f
        private var startY = 0f
        private val gestures = PetTapGesture(ui,
            singleTap = { character.performClick() },
            doubleTap = replies::toggle,
            canDoubleTap = { replies.hasTarget },
            onDown = {
                layoutPet()
                startX = xFraction * travel.x
                startY = yFraction * travel.y
            },
            onDrag = { dx, dy ->
                if (travel.x > 0) xFraction = ((startX + dx) / travel.x).coerceIn(0f, 1f)
                if (travel.y > 0) yFraction = ((startY + dy) / travel.y).coerceIn(0f, 1f)
                if (!dragFramePending) {
                    dragFramePending = true
                    character.postOnAnimation(dragFrame)
                }
            },
            onDragEnd = ::savePosition)

        private fun queueLayoutRefresh() {
            main.removeCallbacks(refreshLayout)
            main.post(refreshLayout)
        }

        init {
            bubble.setOnClickListener {
                val result = spokenResult
                if (result?.kind == "reply") {
                    main.removeCallbacks(finishReply)
                    readingReply = null
                }
                val session = result?.sessionId ?: spokenSession.takeUnless { spokenState == "idle" }
                if (session != null && DesktopPets.openConversationOnBubbleTap(context)) {
                    try { context.startActivity(AgentNotifications.sessionIntent(context, session)) }
                    catch (e: SecurityException) { Log.w("DesktopPets", "Could not open conversation", e) }
                }
                val state = spokenState
                if (state != null && state != "idle") {
                    pause = PetSpeechPause(state, spokenSession, spokenPhase, SystemClock.uptimeMillis())
                    main.removeCallbacks(resumeSpeech)
                    main.postDelayed(resumeSpeech, PetSpeechPause.DURATION_MS)
                }
                setPhrase(null)
                if (result != null) DesktopPetActivity.dismiss(result)
                refresh()
                if (window === this && spokenState == "idle" && spokenResult == null) scheduleIdle()
            }
            character.setOnClickListener {
                if (spokenState == "idle" && spokenResult == null) {
                    val next = nextIdlePhrase("poke") ?: return@setOnClickListener
                    if (next == phrase) bubble.showText(null)
                    setPhrase(next, fromPoke = true)
                    refresh()
                    if (window === this) scheduleIdle()
                }
            }
            character.setOnTouchListener(gestures)
        }

        private fun savePosition() {
            preferences.edit().putFloat("overlayX", xFraction).putFloat("overlayY", yFraction).apply()
        }

        private fun setPhrase(text: String?, fromPoke: Boolean = false) {
            showingPoke = fromPoke
            phrase = text
            bubble.showText(displayedPhrase)
        }

        private fun nextIdlePhrase(category: String): String? {
            val choices = DesktopPetSpeech.lines(context, category)
            val last = lastIdleLines[category]
            val next = choices.filter { it != last }.randomOrNull() ?: choices.firstOrNull() ?: return null
            lastIdleLines[category] = next
            return next
        }

        private fun params(name: String) = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = name
            if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        }

        @Suppress("DEPRECATION")
        private fun available(): Point {
            if (Build.VERSION.SDK_INT < 30) return Point().also { wm.defaultDisplay.getSize(it) }
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(characterParams.fitInsetsTypes)
            return Point(metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }

        fun speechChanged(preference: String?) {
            val category = if (showingPoke) "poke" else speechCategory
            if (preference == null || preference == "speech_$category") spokenState = null
        }

        private fun scheduleIdle() {
            main.removeCallbacks(idleChatter)
            if (phrase == null && DesktopPetSpeech.lines(context, "idle").isEmpty()) return
            val delay = if (phrase == null) Random.nextLong(20_000L, 45_001L)
                else (bubble.readingDurationMs() + 3_000L).coerceAtLeast(9_000L)
            main.postDelayed(idleChatter, delay)
        }

        fun show(pet: String, status: DesktopPetActivity.Display, sizeDp: Int) {
            val (state, session, phase, incoming, detail, category) = status
            // A reply at a companion input prompt stays readable until dismissed or superseded.
            // Ordinary tool updates can retain the same reply. A newer reply takes its place immediately.
            val waitingForInput = status.phoneTask?.awaitingCompanion == true
            if (readingReply != incoming || waitingForInput) {
                main.removeCallbacks(finishReply)
                readingReply = null
            }
            val result = incoming
            replies.bind(pet, status)
            val caption = when (status.phoneStep) {
                "awaitingCompanion" -> context.sugarString(R.string.pet_companion_ready)
                "error" -> context.sugarString(R.string.pet_phone_step_error)
                "screen" -> context.sugarString(R.string.pet_phone_step_screen)
                else -> detail
            }
            val newKey = pet to state
            if (pack?.id != pet) {
                pack = DesktopPets.packs(context).find { it.id == pet }
                val padding = Rect()
                ui.getDrawable(pack?.bubbleRes ?: R.drawable.sugar_pet_bubble)?.getPadding(padding)
                aboveInsets = padding.top + padding.bottom
            }
            if (key != newKey) {
                val animation = DesktopPets.drawable(context, pet, state) ?: run { remove(); return }
                (character.drawable as? AnimatedImageDrawable)?.stop()
                character.setImageDrawable(animation)
                key = newKey
                spokenState = null
            }
            val label = context.sugarString(when (state) {
                "working" -> R.string.pet_working
                "asking" -> R.string.pet_asking
                "thinking" -> R.string.pet_thinking
                "phone" -> R.string.pet_phone
                "coding" -> R.string.pet_coding
                else -> R.string.pet_idle
            })
            character.contentDescription = label
            val language = SugarLanguage.read(context)
            val paused = pause?.holds(state, session, phase, result, SystemClock.uptimeMillis()) == true
            if (pause != null && !paused) {
                pause = null
                main.removeCallbacks(resumeSpeech)
                spokenState = null
            }
            if (state != spokenState || session != spokenSession || phase != spokenPhase ||
                result != spokenResult || caption != spokenCaption || category != speechCategory || speechLanguage != language) {
                spokenState = state
                spokenSession = session
                spokenPhase = phase
                spokenResult = result
                spokenCaption = caption
                speechCategory = category
                speechLanguage = language
                main.removeCallbacks(idleChatter)
                setPhrase(when {
                    result != null -> result.text ?: context.sugarString(when (result.kind) {
                        "operation" -> R.string.pet_operation_complete
                        "error" -> R.string.pet_operation_error
                        else -> R.string.agent_turn_title
                    })
                    state == "idle" || paused -> null
                    else -> caption ?: DesktopPetSpeech.choose(context, category)
                })
                if (state == "idle" && result == null) scheduleIdle()
            }
            petSizeDp = sizeDp
            bubble.showText(displayedPhrase)
            main.removeCallbacks(updatePhoneDetail)
            val showProgress = result == null && pause == null && replies.prompt == null
            val tick = phoneProgress.update(status.progressTask.takeIf { showProgress }, displayedPhrase,
                !detail.isNullOrBlank(), category == "thinking", SystemClock.elapsedRealtime(), status.toolCaptionAt)
            bubble.showDetail(tick.hint?.let { hint ->
                val seconds = hint.seconds
                val elapsed = "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
                val label = when (hint.activity) {
                    PhoneCaptionProgress.Activity.WAITING -> R.string.pet_detail_waiting
                    PhoneCaptionProgress.Activity.THINKING -> R.string.pet_detail_thinking
                    PhoneCaptionProgress.Activity.OBSERVED -> R.string.pet_detail_observed
                    PhoneCaptionProgress.Activity.DISPATCHED -> R.string.pet_detail_dispatched
                    PhoneCaptionProgress.Activity.STEP -> R.string.pet_detail_step
                }
                context.sugarString(label, elapsed)
            })
            tick.nextIn?.let { main.postDelayed(updatePhoneDetail, it) }
            if (displayedPhrase == null && bubble.parent != null) wm.removeViewImmediate(bubble)
            if (!layoutPet(force = true)) return
            if (character.parent == null) wm.addView(character, characterParams)
            if (displayedPhrase != null && bubble.parent == null) wm.addView(bubble, bubbleParams)
            if (result?.kind == "reply" && !waitingForInput && readingReply == null) {
                readingReply = result
                main.postDelayed(finishReply, bubble.firstPassDurationMs().coerceAtLeast(4_000L))
            }
            val animation = character.drawable as? AnimatedImageDrawable
            if (animation != null && !animation.isRunning) animation.start()
        }

        private fun layoutPet(force: Boolean = false): Boolean {
            val bounds = available()
            if (bounds.x <= 0 || bounds.y <= 0) return false
            val size = (petSizeDp * ui.resources.displayMetrics.density).roundToInt()
                .coerceIn(1, minOf(bounds.x, bounds.y))
            val resized = bounds != area || size != characterParams.width
            if (!resized && !force) return true
            if (resized) {
                // A gesture that started before rotation uses a different raw coordinate system.
                gestures.cancel()
                area = bounds
                characterParams.width = size
                characterParams.height = size
                travel.set(area.x - size, area.y - size)
            }
            if (displayedPhrase != null) measureBubble()
            position()
            return true
        }

        private fun measureBubble() {
            val frame = if (bubbleBelow) pack?.bubbleBelowRes ?: R.drawable.sugar_pet_bubble_below
                else pack?.bubbleRes ?: R.drawable.sugar_pet_bubble
            if (frame != bubbleFrame) {
                bubble.setBackgroundResource(frame)
                bubbleFrame = frame
            }
            bubble.measure(View.MeasureSpec.makeMeasureSpec(area.x, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(area.y, View.MeasureSpec.AT_MOST))
            bubbleParams.width = bubble.measuredWidth
            bubbleParams.height = bubble.measuredHeight
        }

        private fun position() {
            val x = (xFraction * travel.x).roundToInt()
            val y = (yFraction * travel.y).roundToInt()
            characterParams.x = x
            characterParams.y = y
            if (displayedPhrase != null) {
                val roomBelow = area.y - y - characterParams.height
                // Use the upper frame's height even if a legacy pack falls back to a different lower frame.
                val needsBelow = y < bubble.heightWithFrameInsets(aboveInsets) && roomBelow > y
                if (needsBelow != bubbleBelow) {
                    bubbleBelow = needsBelow
                    measureBubble()
                }
                // The drawn tail is in the fixed left cap, even when a long caption stretches the frame.
                bubbleParams.x = (x + characterParams.width / 2 - minOf(tailInset, bubbleParams.width / 2))
                    .coerceIn(0, (area.x - bubbleParams.width).coerceAtLeast(0))
                val bubbleY = if (bubbleBelow) y + characterParams.height else y - bubbleParams.height
                bubbleParams.y = bubbleY.coerceIn(0, (area.y - bubbleParams.height).coerceAtLeast(0))
            }
            if (character.parent != null) wm.updateViewLayout(character, characterParams)
            if (bubble.parent != null) wm.updateViewLayout(bubble, bubbleParams)
            positionNote(x, y)
        }

        private fun positionNote(x: Int, y: Int) {
            val note = replies.view
            if (attachedNote !== note) {
                dockAnimator?.cancel()
                dockAnimator = null
                dockTarget = null
                attachedNote?.let { if (it.parent != null) wm.removeViewImmediate(it) }
                attachedNote = null
            }
            if (note == null) return
            val pet = characterParams.width
            val density = ui.resources.displayMetrics.density
            // The sprite has a little transparent padding around the character.
            val inset = minOf((pet * .035f).roundToInt(), (4f * density).roundToInt())
            val left = x + inset
            val right = area.x - x - pet + inset
            val preferred = note.preferredWidth(pet)
            val target = dockNoteOnLeft(dockTarget, left, right, preferred, (10f * density).roundToInt())
            if (target != dockTarget) {
                dockAnimator?.cancel()
                val end = if (target) -1f else 1f
                val animate = dockTarget != null && ValueAnimator.areAnimatorsEnabled()
                dockTarget = target
                if (animate) {
                    dockAnimator = ValueAnimator.ofFloat(dockReveal, end).apply {
                        duration = (90 * kotlin.math.abs(end - dockReveal)).toLong().coerceAtLeast(1)
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        addUpdateListener {
                            dockReveal = it.animatedValue as Float
                            // The pivot follows the live pet position even if the drag reverses mid-turn.
                            positionNote(characterParams.x, characterParams.y)
                        }
                        start()
                    }
                } else dockReveal = end
            }
            val onLeft = dockReveal < 0f
            val width = minOf(preferred, if (onLeft) left else right).coerceAtLeast(1)
            val height = note.preferredHeight(pet)
            val reveal = kotlin.math.abs(dockReveal)
            // Fold against the pet-facing edge. Move the window across only when the paper is edge-on.
            if (attachedNote != null && noteDrawnLeft != onLeft && Build.VERSION.SDK_INT < 34) {
                wm.removeViewImmediate(note)
                attachedNote = null
            }
            noteDrawnLeft = onLeft
            note.setPlacement(onLeft)
            note.pivotX = if (onLeft) width.toFloat() else 0f
            note.scaleX = reveal
            note.alpha = (reveal * 2.5f).coerceAtMost(1f)
            noteParams.x = (if (onLeft) x + inset - width else x + pet - inset)
                .coerceIn(0, (area.x - width).coerceAtLeast(0))
            noteParams.y = y + (pet - height) / 2
            noteParams.width = width
            noteParams.height = height
            if (attachedNote == null) {
                wm.addView(note, noteParams)
                attachedNote = note
            } else wm.updateViewLayout(note, noteParams)
        }

        fun remove() {
            character.removeCallbacks(dragFrame)
            dragFramePending = false
            dockAnimator?.cancel()
            dockAnimator = null
            gestures.cancel()
            replies.close()
            attachedNote?.let { if (it.parent != null) wm.removeViewImmediate(it) }
            attachedNote = null
            main.removeCallbacks(refreshLayout)
            main.removeCallbacks(idleChatter)
            main.removeCallbacks(resumeSpeech)
            main.removeCallbacks(finishReply)
            main.removeCallbacks(updatePhoneDetail)
            readingReply = null
            bubble.showText(null)
            phrase = null
            (character.drawable as? AnimatedImageDrawable)?.stop()
            character.setImageDrawable(null)
            key = null
            if (character.parent != null) wm.removeViewImmediate(character)
            if (bubble.parent != null) wm.removeViewImmediate(bubble)
        }
    }
}
