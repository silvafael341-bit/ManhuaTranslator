package com.google.mlkit.translate

/** Compatibilidade para o namespace usado pelo código do projeto. */
typealias Translator = com.google.mlkit.nl.translate.Translator
typealias TranslatorOptions = com.google.mlkit.nl.translate.TranslatorOptions

object Translation {
    fun getClient(options: TranslatorOptions): Translator =
        com.google.mlkit.nl.translate.Translation.getClient(options)
}
