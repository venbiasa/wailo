package com.venbiasa.wailo.shared

import kotlin.random.Random

data class ScriptRuleDef(
    override val id: String,
    val name: String = "Untitled",
    override val enabled: Boolean = true,
    val urlPattern: String = "*",
    val method: String = "",
    val source: String = DefaultSource,
    val onRequest: Boolean = true,
    val onResponse: Boolean = true,
) : LayoutRule<ScriptRuleDef> {
    override fun withEnabled(enabled: Boolean): ScriptRuleDef = copy(enabled = enabled)

    companion object {
        fun newId(): String = "script-" + Random.nextLong().toULong().toString(16).padStart(16, '0')

        const val DefaultSource = """function onRequest({ request }) {
  return request;
}

function onResponse({ request, response }) {
  return response;
}"""
    }
}

typealias ScriptNode = LayoutNode<ScriptRuleDef>

data class ScriptHookAvailability(
    val onRequest: Boolean,
    val onResponse: Boolean,
)

data class ScriptIssue(
    val ruleId: String,
    val phase: String,
    val timestampEpochMs: Long,
    val code: String,
)
