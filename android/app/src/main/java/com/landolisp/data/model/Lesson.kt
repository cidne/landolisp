package com.landolisp.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Tracks defined in `/curriculum/schema.json`. Mirrored as a sealed list of strings rather than
 * an enum so an unknown future track does not crash older clients.
 */
object LessonTracks {
    const val FUNDAMENTALS = "fundamentals"
    const val FUNCTIONS = "functions"
    const val DATA = "data"
    const val CONTROLFLOW = "controlflow"
    const val MACROS = "macros"
    const val CLOS = "clos"
    const val CONDITIONS = "conditions"
    const val PACKAGES = "packages"
    const val SYSTEM = "system"
    const val LIBRARIES = "libraries"
}

/**
 * One lesson, deserialized from a curriculum JSON file.
 *
 * Matches `/curriculum/schema.json`. Fields not present in the schema are deserialized
 * leniently because the `Json` instance below sets `ignoreUnknownKeys = true`.
 */
@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val track: String,
    val order: Int,
    val estimatedMinutes: Int? = null,
    val prerequisites: List<String> = emptyList(),
    val completionSymbols: List<String> = emptyList(),
    val sections: List<Section>,
)

/**
 * Polymorphic section. `kind` is the discriminator on disk; kotlinx-serialization will pick
 * the matching subclass based on the [Json] instance configured below.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Section {

    @Serializable
    @SerialName("prose")
    data class Prose(val markdown: String) : Section

    @Serializable
    @SerialName("example")
    data class Example(
        val code: String,
        val expected: String? = null,
        val explain: String? = null,
    ) : Section

    @Serializable
    @SerialName("exercise")
    data class Exercise(
        val prompt: String,
        val starter: String = "",
        val hint: String? = null,
        val tests: List<ExerciseTest>,
    ) : Section
}

@Serializable
data class ExerciseTest(
    val call: String,
    val equals: String,
)

/**
 * Lightweight summary used by the lesson list. Backed by `/curriculum/index.json`.
 */
@Serializable
data class LessonIndex(
    val lessons: List<LessonSummary>,
)

@Serializable
data class LessonSummary(
    val id: String,
    val title: String,
    val track: String,
    val order: Int,
    val estimatedMinutes: Int? = null,
)

/**
 * Project-wide [Json] instance. Keep one — every consumer should reuse it so the
 * `classDiscriminator` setting is uniform.
 */
@OptIn(ExperimentalSerializationApi::class)
val LandolispJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}
