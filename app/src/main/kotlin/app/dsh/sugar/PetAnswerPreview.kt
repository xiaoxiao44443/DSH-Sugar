package app.dsh.sugar

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** A sample question in the settings window. Choices and edited text live only in this dialog. */
internal class PetAnswerPreview(
    private val activity: Activity,
    pack: DesktopPets.Pack,
    private val closed: () -> Unit,
) {
    private val options = SugarLanguage.resources(activity).getStringArray(R.array.pet_note_preview_options).toList()
    private val question = activity.sugarString(R.string.pet_note_preview_question)
    private val bubble = DesktopPetBubble(activity).apply {
        setBackgroundResource(pack.bubbleRes)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        showText(question)
    }
    private val actor = ImageView(activity).apply {
        setImageDrawable(DesktopPets.drawable(activity, pack.id, "asking"))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = pack.name(activity)
    }
    private val hint = TextView(activity).apply {
        text = activity.sugarString(R.string.pet_reply_preview_hint)
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(0xff8c8294.toInt())
        visibility = View.GONE
    }
    private val note = DesktopPetAnswerNote(activity, pack.id,
        onFold = ::toggleSheet,
        onVoice = ::editTranscript,
        onConfirm = ::confirm,
    ).apply {
        bind(options)
    }
    private val dialog = SugarDialog.builder(activity, landscapeMaxWidthDp = 440)
        .setTitle(activity.sugarString(R.string.pet_note_preview_title))
        .setView(PreviewStage(activity, actor, note, hint, bubble, DesktopPets.size(activity)))
        .setNegativeButton(activity.sugarString(R.string.mobile_manager_close), null)
        .create()
    private var editor: AlertDialog? = null

    fun show() {
        dialog.setOnDismissListener {
            editor?.dismiss()
            (actor.drawable as? AnimatedImageDrawable)?.stop()
            closed()
        }
        actor.setOnClickListener { toggleSheet() }
        actor.setOnTouchListener(PetTapGesture(activity, {}, { actor.performClick() }))
        dialog.show()
        (actor.drawable as? AnimatedImageDrawable)?.start()
    }

    fun dismiss() = dialog.dismiss()

    private fun toggleSheet() {
        val opening = note.visibility != View.VISIBLE
        note.visibility = if (opening) View.VISIBLE else View.GONE
        hint.visibility = if (opening) View.GONE else View.VISIBLE
        if (opening) bubble.showText(question)
    }

    private fun confirm(indices: List<Int>) {
        val text = indices.mapNotNull { index ->
            if (index == options.size) note.voiceText else options.getOrNull(index)
        }.joinToString()
        if (text.isEmpty()) return
        bubble.showText(activity.sugarString(R.string.pet_note_preview_result, text))
        toggleSheet()
    }

    private fun editTranscript() {
        if (editor != null) return
        val builder = SugarDialog.builder(activity, landscapeMaxWidthDp = 440)
        val entry = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(240))
            hint = activity.sugarString(R.string.pet_note_preview_input)
            setText(note.voiceText.orEmpty())
            setSelection(text.length)
        }
        val wrapper = FrameLayout(builder.context).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(entry, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val inputDialog = builder.setTitle(activity.sugarString(R.string.pet_note_preview_transcript))
            .setView(wrapper)
            .setNegativeButton(activity.sugarString(R.string.dialog_cancel), null)
            .setPositiveButton(activity.sugarString(R.string.pet_note_preview_apply)) { _, _ ->
                entry.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                    note.setVoiceText(it)
                    bubble.showText(question)
                }
            }.create()
        editor = inputDialog
        inputDialog.setOnDismissListener { editor = null }
        entry.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) false else {
                inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            }
        }
        inputDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        entry.requestFocus()
        inputDialog.show()
    }
}

private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** Keep the pet anchored while the sheet folds; measure again on rotation and narrow windows. */
private class PreviewStage(
    context: Context,
    private val actor: ImageView,
    private val note: DesktopPetAnswerNote,
    private val hint: TextView,
    private val bubble: DesktopPetBubble,
    petSizeDp: Int,
) : ViewGroup(context) {
    private val petSize = context.dp(petSizeDp)
    private var expandedWidth = 0

    init {
        setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(6))
        addView(bubble)
        addView(note)
        addView(hint)
        addView(actor)
    }

    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.AT_MOST)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = petSize + (petSize * 1.5f).roundToInt() + paddingLeft + paddingRight
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val room = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        bubble.measure(atMost(room), atMost(context.dp(72)))
        val roomBelowBubble = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) petSize
            else MeasureSpec.getSize(heightMeasureSpec) - paddingTop - bubble.measuredHeight - paddingBottom
        // Make room for both pieces if the activity is docked or in split screen.
        val size = minOf(petSize, roomBelowBubble, (room * .54f).roundToInt()).coerceAtLeast(1)
        actor.measure(exact(size), exact(size))
        val noteWidth = minOf(room - size, note.preferredWidth(size)).coerceAtLeast(1)
        expandedWidth = noteWidth
        note.measure(exact(noteWidth), exact(note.preferredHeight(size)))
        hint.measure(exact(noteWidth), exact(size))
        val height = paddingTop + bubble.measuredHeight + size + paddingBottom
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val room = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val pet = actor.measuredWidth
        val space = expandedWidth.coerceIn(0, (room - pet).coerceAtLeast(0))
        val petX = paddingLeft + (room - pet - space) / 2 + space
        val petY = paddingTop + bubble.measuredHeight
        actor.layout(petX, petY, petX + pet, petY + pet)
        val paperY = petY + (pet - note.measuredHeight) / 2
        note.layout(petX - note.measuredWidth, paperY, petX, paperY + note.measuredHeight)
        hint.layout(petX - space, petY, petX, petY + pet)
        val bubbleX = (petX + pet / 2 - context.dp(25))
            .coerceIn(paddingLeft, (width - paddingRight - bubble.measuredWidth).coerceAtLeast(paddingLeft))
        bubble.layout(bubbleX, paddingTop, bubbleX + bubble.measuredWidth, petY)
    }
}
