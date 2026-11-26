package com.loopers.support

import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The evidence directory of one payment incident run (spec 274, "증거"): `build/payment-incident/<run id>/` under the module, the
 * test's working directory. The run ID is the start time in UTC and a random suffix, and the directory is created new, so two runs
 * write two directories and a run never writes into another's.
 *
 * Every file is created new: writing a name twice fails instead of overwriting the first record. Nothing here deletes a file.
 */
class IncidentEvidence private constructor(
    val directory: Path,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        private val ROOT: Path = Path.of("build", "payment-incident")

        private val RUN_ID_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** Creates the run's directory. Fails if it already exists. */
        fun start(objectMapper: ObjectMapper, startedAt: Instant): IncidentEvidence {
            Files.createDirectories(ROOT)
            val runId = "${RUN_ID_TIME.format(startedAt)}-${UUID.randomUUID().toString().take(8)}"
            val directory = Files.createDirectory(ROOT.resolve(runId))

            return IncidentEvidence(directory, objectMapper)
        }
    }

    val runId: String
        get() = directory.fileName.toString()

    /** Writes [text] as it is to a new file [name]. Fails if the file exists. */
    fun writeText(name: String, text: String): Path {
        return Files.writeString(directory.resolve(name), text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /** Writes [value] as indented JSON to a new file [name]. Fails if the file exists. */
    fun writeJson(name: String, value: Any): Path {
        return writeText(name, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
    }
}
