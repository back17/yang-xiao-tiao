package com.skip.app

internal object RuleSelector {
    data class Query(
        val value: String,
        val exact: Boolean,
    )

    data class Selector(
        val whole: Query?,
        val path: List<Query>,
    )

    fun triggerSelectors(expression: String): List<Selector> {
        return expression.split('&')
            .mapNotNull(::parseSelector)
    }

    fun actionSelector(expression: String): Selector? {
        return parseSelector(expression)
    }

    fun matches(query: Query, candidate: String): Boolean {
        val normalizedCandidate = normalize(candidate)
        if (normalizedCandidate.isEmpty()) return false
        return if (query.exact) {
            normalizedCandidate.equals(query.value, ignoreCase = true)
        } else {
            normalizedCandidate.contains(query.value, ignoreCase = true)
        }
    }

    private fun parseSelector(raw: String): Selector? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val exact = trimmed.startsWith('=')
        val value = normalize(trimmed.removePrefix("=").removePrefix("+"))
        if (value.isEmpty()) return null

        val path = value.split('|')
            .mapNotNull { part -> query(part, exact) }
        if (path.isEmpty()) return null

        return Selector(
            whole = query(value, exact).takeUnless { value.startsWith('|') },
            path = path,
        )
    }

    private fun query(raw: String, exact: Boolean): Query? {
        val value = normalize(raw)
        return value.takeIf(String::isNotEmpty)?.let { Query(it, exact) }
    }

    private fun normalize(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }
}
