package com.manhuatranslator

/** Kotlin compatibility helpers used by the V0.8.6 renderer source. */
private fun max(a: Int, b: Float): Float = kotlin.math.max(a.toFloat(), b)
private fun max(a: Float, b: Int): Float = kotlin.math.max(a, b.toFloat())

private fun String.trim(chars: String): String = this.trim(*chars.toCharArray())
