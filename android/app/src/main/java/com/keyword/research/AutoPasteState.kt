package com.keyword.research

import android.net.Uri

object AutoPasteState {
    sealed class Step {
        data class Title(val value: String) : Step()
        data class Heading(val value: String) : Step()
        data class Text(val value: String) : Step()
        data class Image(val uri: Uri) : Step()
        data object MoveBelowImage : Step()
    }

    var steps: List<Step> = emptyList()
    var index = 0
    var active = false

    fun start(newSteps: List<Step>) {
        steps = newSteps
        index = 0
        active = newSteps.isNotEmpty()
    }

    fun clear() {
        steps = emptyList()
        index = 0
        active = false
    }
}
