package de.tetnavigator.data.download

import de.tetnavigator.core.offlinedata.Manifest
import de.tetnavigator.core.offlinedata.PackageFile
import java.io.File

data class PlannedFile(val country: String, val file: PackageFile, val target: File, val part: File) {
    /** Bereits vorhandene Bytes (fertig oder .part) – zählt nicht gegen den Speicherbedarf. */
    val presentBytes: Long get() = when {
        target.exists() && target.length() == file.bytes -> file.bytes
        part.exists() -> part.length().coerceAtMost(file.bytes.coerceAtLeast(part.length()))
        else -> 0
    }
}

data class DownloadPlan(val files: List<PlannedFile>) {
    val totalBytes: Long get() = files.sumOf { it.file.bytes }
    val remainingBytes: Long get() = files.sumOf { (it.file.bytes - it.presentBytes).coerceAtLeast(0) }
    val countries: Set<String> get() = files.map { it.country }.toSet()

    /** Speicher reicht, wenn frei ≥ Rest + 5 % Reserve + 50 MB. */
    fun fitsInto(freeBytes: Long): Boolean = freeBytes >= requiredFree()
    fun requiredFree(): Long = remainingBytes + remainingBytes / 20 + 50L * 1024 * 1024

    companion object {
        fun create(manifest: Manifest, countries: Collection<String>, resolve: (String, PackageFile) -> Pair<File, File>): DownloadPlan =
            DownloadPlan(
                manifest.uniqueFiles(countries).map { (cc, f) ->
                    val (target, part) = resolve(cc, f)
                    PlannedFile(cc, f, target, part)
                },
            )
    }
}
