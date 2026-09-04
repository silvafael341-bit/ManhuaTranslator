package com.manhuatranslator

/** Kotlin compatibility helpers used by the V0.8.6 renderer source. */
fun max(a: Int, b: Float): Float = kotlin.math.max(a.toFloat(), b)
fun max(a: Float, b: Int): Float = kotlin.math.max(a, b.toFloat())

fun String.trim(chars: String): String = this.trim(*chars.toCharArray())
