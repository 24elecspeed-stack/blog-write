package com.keyword.research

import android.content.Context

object PromptTemplateStore {
    private const val PREFS = "prompt_template"
    private const val KEY_BASE_RULES = "base_rules"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_RULES, null)
            ?.takeIf { it.isNotBlank() }
            ?: PromptBuilder.defaultEditableRules()

    fun save(context: Context, rules: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_RULES, rules.trim())
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BASE_RULES)
            .apply()
    }
}
