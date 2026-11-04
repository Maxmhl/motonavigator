package de.tetnavigator.feature.importer

/** Erkennt Vorversionen über den „Stamm“ des Dateinamens (ohne Datum/Version). */
object FileNames {
    fun stem(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.')
            .lowercase()
            .replace('_', ' ').replace('-', ' ').replace('.', ' ')
            .replace(Regex("""\d{4}[ ]?\d{2}[ ]?\d{2}"""), "")
            .replace(Regex("""\bv?\d+(\.\d+)*\b"""), "")
            .replace(Regex("""[^\p{L}]+"""), "")
}
