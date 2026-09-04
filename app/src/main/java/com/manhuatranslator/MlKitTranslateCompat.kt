package com.google.mlkit.translate

/** Compatibilidade para o namespace usado pelo código do projeto. */
typealias Translator = com.google.mlkit.nl.translate.Translator

class TranslatorOptions internal constructor(
    internal val delegate: com.google.mlkit.nl.translate.TranslatorOptions
) {
    class Builder {
        private val delegate = com.google.mlkit.nl.translate.TranslatorOptions.Builder()

        fun setSourceLanguage(language: String): Builder {
            delegate.setSourceLanguage(language)
            return this
        }

        fun setTargetLanguage(language: String): Builder {
            delegate.setTargetLanguage(language)
            return this
        }

        fun build(): TranslatorOptions = TranslatorOptions(delegate.build())
    }
}

object Translation {
    fun getClient(options: TranslatorOptions): Translator =
        com.google.mlkit.nl.translate.Translation.getClient(options.delegate)
}
