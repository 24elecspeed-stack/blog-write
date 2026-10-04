package com.keyword.research

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class NaverPasteAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var waitPromptShown = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoPasteState.active || event?.packageName?.toString() != NAVER_PACKAGE || scheduled) return
        // 제목과 본문을 단계별로 구분하므로 사용자가 입력칸을 직접 누를 필요가 없다.
        // 네이버 글쓰기 화면의 접근성 노드가 준비되는 즉시 제목부터 자동 입력한다.
        // 사진 다음 단계(MoveBelowImage)에서는 사용자가 직접 누른 탭 이벤트 자체가 신호가 된다.
        val editor = editorForCurrentStep(rootInActiveWindow, event.source) ?: return
        scheduled = true
        handler.postDelayed({ pasteNext(editor) }, 500)
    }

    private fun pasteNext(previousEditor: AccessibilityNodeInfo?) {
        if (!AutoPasteState.active || AutoPasteState.index >= AutoPasteState.steps.size) {
            finishPaste()
            return
        }
        val root = rootInActiveWindow
        val step = AutoPasteState.steps[AutoPasteState.index]
        if (step is AutoPasteState.Step.MoveBelowImage) {
            // 실기기 접근성 트리를 직접 덤프해서 확인한 사실: 사진을 붙인 직후에는 사진 아래에
            // 편집 가능한 칸이 아예 존재하지 않는다 - 네이버 에디터는 사용자가 그 빈 자리를
            // 실제로 탭해야만 새 문단 칸을 만들어 포커스를 준다. 좌표를 계산한 탭 흉내,
            // 스크롤, 스와이프 제스처를 전부 실기기에서 시도했지만 모두 실패로 확인됐다.
            // 그래서 탭을 자동으로 대신하려 하지 않고 사용자에게 한 번만 눌러달라고 안내한 뒤,
            // 그 실제 탭이 만든 새 칸을(이미 있는 이벤트 감지 로직으로) 감지해서 자동으로 이어쓴다.
            val editor = editorForCurrentStep(root, null)
                ?: previousEditor?.takeIf { it.refresh() && isBodyEditor(it) }
            if (editor == null) {
                scheduled = false
                if (!waitPromptShown) {
                    waitPromptShown = true
                    Toast.makeText(this, "사진 아래 빈 줄을 한 번 눌러주세요 - 누르면 자동으로 이어집니다", Toast.LENGTH_LONG).show()
                }
                return
            }
            waitPromptShown = false
            AutoPasteState.index++
            handler.postDelayed({ pasteNext(editor) }, 300)
            return
        }
        waitPromptShown = false
        val editor = editorForCurrentStep(root, null)
            ?: previousEditor?.takeIf { it.refresh() && matchesStep(it, step) }
        if (editor == null) {
            scheduled = false
            Toast.makeText(this, "네이버 글쓰기 화면을 찾는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (step) {
            is AutoPasteState.Step.Title -> clipboard.setPrimaryClip(ClipData.newPlainText("제목", step.value))
            is AutoPasteState.Step.Heading -> clipboard.setPrimaryClip(ClipData.newPlainText("소제목", step.value))
            is AutoPasteState.Step.Text -> clipboard.setPrimaryClip(ClipData.newPlainText("문단", step.value))
            is AutoPasteState.Step.Image -> {
                grantUriPermission(NAVER_PACKAGE, step.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "사진", step.uri))
            }
            AutoPasteState.Step.MoveBelowImage -> return
        }
        val accepted = editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!accepted) {
            scheduled = false
            Toast.makeText(this, "네이버 본문을 한 번 누른 뒤 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        AutoPasteState.index++
        val delay = if (step is AutoPasteState.Step.Image) 4500L else 1200L
        handler.postDelayed({ pasteNext(null) }, delay)
    }

    private fun editorForCurrentStep(root: AccessibilityNodeInfo?, source: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val step = AutoPasteState.steps.getOrNull(AutoPasteState.index) ?: return null
        return when (step) {
            is AutoPasteState.Step.Title -> findTitleEditor(root)
            is AutoPasteState.Step.Heading, is AutoPasteState.Step.Text, is AutoPasteState.Step.Image, AutoPasteState.Step.MoveBelowImage ->
                editableFrom(source)?.takeIf(::isBodyEditor)
                    ?: findFocusedBodyEditor(root)
                    ?: findFirstBodyEditor(root)
        }
    }

    private fun matchesStep(node: AccessibilityNodeInfo, step: AutoPasteState.Step): Boolean = when (step) {
        is AutoPasteState.Step.Title -> isTitleEditor(node)
        is AutoPasteState.Step.Heading, is AutoPasteState.Step.Text, is AutoPasteState.Step.Image, AutoPasteState.Step.MoveBelowImage -> isBodyEditor(node)
    }

    private fun editableFrom(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isEditable) return current
            current = current.parent
        }
        return null
    }

    private fun findFocusedBodyEditor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isBodyEditor(node) && (node.isFocused || node.isAccessibilityFocused)) return node
        for (index in 0 until node.childCount) {
            findFocusedBodyEditor(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun isBodyEditor(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) return false
        return !isTitleEditor(node)
    }

    private fun isTitleEditor(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) return false
        val marker = buildString {
            append(node.viewIdResourceName.orEmpty()).append(' ')
            append(node.hintText?.toString().orEmpty()).append(' ')
            append(node.contentDescription?.toString().orEmpty())
        }.lowercase()
        return listOf("title", "subject", "제목").any(marker::contains)
    }

    private fun findTitleEditor(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val editors = mutableListOf<AccessibilityNodeInfo>()
        collectEditors(root, editors)
        editors.firstOrNull(::isTitleEditor)?.let { return it }
        return editors.minByOrNull { node -> android.graphics.Rect().also(node::getBoundsInScreen).top }
    }

    private fun findFirstBodyEditor(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        // 본문 칸이 화면에 딱 1개만 있을 때(스크롤로 제목이 안 보이는 경우 등) "맨 위 칸 = 제목"으로
        // 추측해서 제외하면 그 유일한 본문 칸까지 같이 사라져 버린다(실기기 덤프로 확인된 실제 버그).
        // 제목 여부는 오직 명시적 마커(isTitleEditor)로만 판단하고, 위치 추측으로 제외하지 않는다.
        // 여러 본문 칸이 있으면 가장 아래(가장 나중에 생긴) 칸을 고른다 - 이어쓰기 대상은 항상 그것.
        val editors = mutableListOf<AccessibilityNodeInfo>()
        collectEditors(root, editors)
        return editors.filterNot(::isTitleEditor)
            .maxByOrNull { node -> android.graphics.Rect().also(node::getBoundsInScreen).top }
    }

    private fun collectEditors(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable) output += node
        for (index in 0 until node.childCount) collectEditors(node.getChild(index), output)
    }

    // 소제목 굵게 자동화는 실기기에서 여러 방식(붙여넣기 직후 서식, 다 쓴 뒤 재검색 서식)을
    // 시도했지만 매번 다른 문제(엉뚱한 곳에 적용, 본문과 들러붙음, 시스템 선택 팝업에 걸려 멈춤)가
    // 나와 포기했다 - 대신 소제목 목록을 안내만 하고, 각 소제목은 글을 직접 길게 눌러 선택한 뒤
    // 편집기 "B" 버튼으로 사용자가 직접 굵게 처리한다.
    private fun finishPaste() {
        val headings = AutoPasteState.steps.filterIsInstance<AutoPasteState.Step.Heading>()
            .map { it.value.trimEnd('\n') }
            .filter { it.isNotBlank() }
        AutoPasteState.clear()
        scheduled = false
        if (headings.isNotEmpty()) {
            val list = headings.joinToString("\n") { "· $it" }
            Toast.makeText(
                this,
                "문단·사진 배치가 끝났습니다. 아래 소제목은 직접 선택해서 굵게(B) 처리해주세요:\n$list",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "문단과 사진 반자동 배치가 끝났습니다. 내용을 확인하고 직접 저장하세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInterrupt() {
        scheduled = false
    }

    companion object {
        private const val NAVER_PACKAGE = "com.nhn.android.blog"
    }
}
