package com.poskds.app.service

/**
 * Parses only explicit KDS cooking-count signals.
 *
 * A bare tab label is not evidence that the tab contains zero orders: callers
 * must preserve the last known count until an explicit count or empty-state
 * message is present.
 */
object CookingCountParser {
    private val cookingCountPattern = Regex("조리중\\s*(\\d+)")
    private const val EMPTY_STATE_MESSAGE = "조리할 주문이 없습니다"

    fun explicitCount(text: CharSequence?, contentDescription: CharSequence?): Int? {
        return sequenceOf(text, contentDescription)
            .mapNotNull { label -> cookingCountPattern.find(label ?: "")?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull()
    }

    fun isExplicitEmptyState(text: CharSequence?, contentDescription: CharSequence?): Boolean {
        return sequenceOf(text, contentDescription).any { it?.contains(EMPTY_STATE_MESSAGE) == true }
    }
}
