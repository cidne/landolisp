package com.landolisp.data.model

import kotlinx.serialization.Serializable

/**
 * Lightweight projection of a [Lesson] used by the lesson-list screen and the
 * `index.json` manifest at `/curriculum/index.json`.
 *
 * Kept separate from the full [Lesson] schema so the list view can paginate /
 * group lessons without paying the cost of decoding their bodies.
 */
@Serializable
data class LessonSummary(
    val id: String,
    val title: String,
    val track: String,
    val order: Int,
    val estimatedMinutes: Int? = null,
)
