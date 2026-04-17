package com.landolisp.data

import android.content.Context
import com.landolisp.data.model.LandolispJson
import com.landolisp.data.model.Lesson
import com.landolisp.data.model.LessonSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.decodeFromStream

/**
 * Loads curriculum from `assets/curriculum/`.
 *
 * Files there are populated at build time by `scripts/sync-curriculum.sh`
 * (wired in via the `syncCurriculum` Gradle task).
 */
class LessonRepository(
    private val context: Context,
) {
    /**
     * Streams the lesson manifest. Currently emits once after a single asset read; backed by
     * a [Flow] so future hot-reload / network sync can replace the producer without changing
     * callers.
     */
    fun lessons(): Flow<List<LessonSummary>> = flow {
        emit(readIndex().sortedBy { it.order })
    }.flowOn(Dispatchers.IO)

    suspend fun loadLesson(id: String): Lesson {
        val path = "curriculum/$id.json"
        return context.assets.open(path).use { input ->
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            LandolispJson.decodeFromStream<Lesson>(input)
        }
    }

    /**
     * `index.json` is a flat JSON array of [LessonSummary] per [docs/CURRICULUM.md].
     * Returns an empty list if the file is missing or malformed (so the UI degrades
     * to "no lessons found" instead of crashing).
     */
    private fun readIndex(): List<LessonSummary> {
        return runCatching {
            context.assets.open(INDEX_PATH).use { input ->
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                LandolispJson.decodeFromStream(
                    ListSerializer(LessonSummary.serializer()),
                    input,
                )
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        const val INDEX_PATH = "curriculum/index.json"
    }
}
