package com.keyword.research

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Only packaged in headingTest. The ordinary app never uses this formatter. */
class HeadingTestService : NaverPasteAccessibilityService() {
    private val timer = Handler(Looper.getMainLooper())
    private var originalSize: String? = null
    private var originalBold = false
    private var currentSteps: List<AutoPasteState.Step>? = null
    private var generation = 0
    private var attempts = 0
    private var formatting = false
    private var headings = emptyList<String>()
    private var headingIndex = 0
    private var scrollAttempts = 0
    private var selectedEditor: AccessibilityNodeInfo? = null
    private var selectionStart = -1
    private var selectionEnd = -1

    // Keep the original insertion behavior, resetting only our post-format session.
    override fun prepareStyle(step: AutoPasteState.Step, ready: () -> Unit) {
        if (currentSteps !== AutoPasteState.steps) {
            generation++
            timer.removeCallbacksAndMessages(null)
            formatting = false
            selectedEditor = null
            currentSteps = AutoPasteState.steps
            originalSize = null
        }
        ready()
    }

    // Format only after all paste steps finish.
    private fun formatHeading(step: AutoPasteState.Step, ready: () -> Unit) {
        if (currentSteps !== AutoPasteState.steps) {
            currentSteps = AutoPasteState.steps
            originalSize = null
        }
        if (step is AutoPasteState.Step.Title) { ready(); return }
        attempts = 0
        val expected = ++generation
        val index = AutoPasteState.index
        val started = android.os.SystemClock.elapsedRealtime()
        fun schedule(action: () -> Unit) {
            timer.postDelayed({
                if (expected != generation || !AutoPasteState.active || index != AutoPasteState.index || currentSteps !== AutoPasteState.steps) {
                    android.util.Log.i("HeadingTest", "callback cancelled: active=${AutoPasteState.active} step=$index current=${AutoPasteState.index} generation=$generation expected=$expected")
                    return@postDelayed
                }
                if (rootInActiveWindow?.packageName?.toString() != BLOG) {
                    abort("네이버 앱을 벗어나 글·사진 입력을 중지했습니다."); return@postDelayed
                }
                if (android.os.SystemClock.elapsedRealtime() - started > 25000) {
                    abort("서식 설정 시간을 초과해 중지했습니다."); return@postDelayed
                }
                action()
            }, 800)
        }
        fun advance() {
            android.util.Log.i("HeadingTest", "format step=$index attempt=$attempts")
            if (++attempts > 18) { abort("서식 선택 상태를 확인하지 못해 입력을 중지했습니다."); return }
            if (!selectionIntact()) { abort("소제목 선택 범위를 확인하지 못해 서식 변경을 중지했습니다."); return }
            val list = nodes()
            val heading = step is AutoPasteState.Step.Heading
            val targetSize = if (heading) "19" else originalSize
            val back = list.firstOrNull { desc(it).contains("Move to the parent menu", true) || desc(it).contains("상위 메뉴") }
            if (back != null) {
                if (targetSize == null) {
                    if (!click(back)) { abort("서식 메뉴 복귀 실패"); return }
                    schedule { advance() }; return
                }
                val size = list.firstOrNull { it.text?.toString() == targetSize }
                if (size == null) { abort("크기 $targetSize 항목을 찾지 못했습니다."); return }
                val sizeRect = Rect().also(size::getBoundsInScreen)
                val isChosen = selected(size) || size.parent?.let(::selected) == true || list.any {
                    selected(it) && Rect().also(it::getBoundsInScreen).contains(sizeRect)
                }
                if (!isChosen) {
                    if (!click(size)) { abort("크기 선택 실패"); return }
                } else if (!click(back)) { abort("서식 메뉴 복귀 실패"); return }
                schedule { advance() }; return
            }
            val sizeButton = list.firstOrNull { desc(it).contains("Font size button", true) || desc(it).contains("글자 크기") }
            if (sizeButton == null) {
                val menu = list.firstOrNull { desc(it).contains("Text setting menu", true) || desc(it).contains("텍스트 설정") }
                if (menu == null || !click(menu)) { abort("텍스트 설정 버튼을 찾지 못했습니다."); return }
                schedule { advance() }; return
            }
            val bold = list.firstOrNull { desc(it).contains("Bold, Button", true) || desc(it).contains("굵게") }
            val sizeBounds = Rect().also(sizeButton::getBoundsInScreen)
            val sizeNow = textIn(sizeButton).firstOrNull { it.matches(Regex("[0-9]{1,2}")) }
                ?: list.firstOrNull {
                    it.text?.toString()?.matches(Regex("[0-9]{1,2}")) == true &&
                        sizeBounds.contains(Rect().also(it::getBoundsInScreen))
                }?.text?.toString()
            if (bold == null || sizeNow == null) { abort("현재 글자 크기·굵기를 읽지 못했습니다."); return }
            if (originalSize == null) { originalSize = sizeNow; originalBold = selected(bold) }
            val wantedSize = if (heading) "19" else originalSize!!
            val wantedBold = if (heading) true else originalBold
            if (sizeNow != wantedSize) {
                if (!click(sizeButton)) { abort("크기 메뉴 열기 실패"); return }
                schedule { advance() }; return
            }
            if (selected(bold) != wantedBold) {
                if (!click(bold)) { abort("굵게 변경 실패"); return }
                schedule { advance() }; return
            }
            android.util.Log.i("HeadingTest", "step=$index type=${step.javaClass.simpleName} size=$sizeNow bold=${selected(bold)} verified")
            ready()
        }
        schedule { advance() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            result.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return result
    }
    private fun textIn(n: AccessibilityNodeInfo): List<String> = buildList {
        n.text?.toString()?.let(::add)
        for (i in 0 until n.childCount) n.getChild(i)?.let { addAll(textIn(it)) }
    }
    private fun desc(n: AccessibilityNodeInfo) = n.contentDescription?.toString().orEmpty()
    private fun selected(n: AccessibilityNodeInfo) = n.isSelected || n.isChecked || desc(n).contains("Selected", true) || desc(n).contains("선택됨")
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = n
        repeat(3) {
            if (current?.isClickable == true && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            current = current?.parent
        }
        val bounds = Rect().also(n::getBoundsInScreen)
        if (bounds.isEmpty || !n.isVisibleToUser) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }
    private fun abort(message: String) {
        generation++
        timer.removeCallbacksAndMessages(null)
        android.util.Log.w("HeadingTest", message)
        formatting = false
        selectedEditor = null
        stopWithMessage("TEST-01: $message 입력된 원고는 그대로 남아 있습니다.")
    }
    override fun finishPaste() {
        if (formatting) return
        headings = AutoPasteState.steps.filterIsInstance<AutoPasteState.Step.Heading>()
            .map { it.value.trim() }.filter { it.isNotEmpty() }.asReversed()
        if (headings.isEmpty()) { complete(); return }
        currentSteps = AutoPasteState.steps
        originalSize = null
        formatting = true
        headingIndex = 0
        scrollAttempts = 0
        android.util.Log.i("HeadingTest", "Insertion finished; begin post-format headings=${headings.size}")
        later { locateHeading() }
    }
    private fun later(action: () -> Unit) {
        val expected = generation
        timer.postDelayed({
            if (expected != generation || !AutoPasteState.active || currentSteps !== AutoPasteState.steps) return@postDelayed
            if (rootInActiveWindow?.packageName?.toString() != BLOG) {
                abort("네이버 앱을 벗어나 서식 변경을 중지했습니다."); return@postDelayed
            }
            action()
        }, 800)
    }
    private fun locateHeading() {
        val heading = headings[headingIndex]
        val matches = nodes().filter {
            it.isEditable && !listOf(it.viewIdResourceName, it.hintText, it.contentDescription)
                .joinToString(" ").contains(Regex("(?i)title|subject|제목"))
        }.mapNotNull { editor ->
            val text = editor.text?.toString().orEmpty()
            val start = text.indexOf(heading)
            if (start < 0 || text.indexOf(heading, start + 1) >= 0 ||
                (start > 0 && text.substring(text.lastIndexOf('\n', start - 1) + 1, start)
                    .any { !it.isWhitespace() && it != '\u2060' && it != '\u200b' }) ||
                (start + heading.length < text.length && text[start + heading.length] != '\n')) null
            else Triple(editor, start, start + heading.length)
        }
        if (matches.size > 1) { abort("같은 소제목이 여러 곳에 있어 자동 선택을 중지했습니다."); return }
        if (matches.size == 1) {
            val (editor, start, end) = matches.single()
            editor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            selectedEditor = editor
            selectionStart = start
            selectionEnd = end
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                abort("소제목 범위를 선택하지 못했습니다."); return
            }
            formatHeading(AutoPasteState.Step.Heading(heading)) {
                android.util.Log.i("HeadingTest", "post-format heading=$headingIndex range=$selectionStart..$selectionEnd verified")
                clearSelection()
                headingIndex++
                scrollAttempts = 0
                if (headingIndex >= headings.size) complete() else later { locateHeading() }
            }
            return
        }
        if (++scrollAttempts > 24) { abort("소제목을 화면에서 찾지 못했습니다."); return }
        val scroller = nodes().lastOrNull { it.isScrollable && it.isVisibleToUser }
        if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true) {
            later { locateHeading() }; return
        }
        val bounds = Rect().also { rootInActiveWindow?.getBoundsInScreen(it) }
        if (bounds.isEmpty) { abort("본문 스크롤 영역을 찾지 못했습니다."); return }
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.top + bounds.height() * .30f)
            lineTo(bounds.exactCenterX(), bounds.top + bounds.height() * .70f)
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build(), null, null)) {
            abort("소제목 검색 스크롤에 실패했습니다."); return
        }
        later { locateHeading() }
    }
    private fun selectionIntact(): Boolean {
        val editor = selectedEditor ?: return false
        if (!editor.refresh()) return false
        val text = editor.text?.toString().orEmpty()
        return selectionStart >= 0 && selectionEnd <= text.length &&
            editor.textSelectionStart == selectionStart && editor.textSelectionEnd == selectionEnd &&
            text.substring(selectionStart, selectionEnd) == headings[headingIndex]
    }
    private fun clearSelection() {
        selectedEditor?.takeIf { it.refresh() }?.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionEnd)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionEnd)
            })
        selectedEditor = null
    }
    private fun complete() {
        generation++
        timer.removeCallbacksAndMessages(null)
        formatting = false
        selectedEditor = null
        originalSize = null
        stopWithMessage("TEST-01 입력 순서와 서식 처리가 끝났습니다. 사진 누락 여부를 확인하고 직접 저장하세요.")
    }
    override fun onInterrupt() { abort("접근성이 중단되었습니다."); super.onInterrupt() }
    override fun onDestroy() {
        android.util.Log.i("HeadingTest", "service destroyed active=${AutoPasteState.active} step=${AutoPasteState.index}")
        timer.removeCallbacksAndMessages(null); super.onDestroy()
    }
    companion object { private const val BLOG = "com.nhn.android.blog" }
}
