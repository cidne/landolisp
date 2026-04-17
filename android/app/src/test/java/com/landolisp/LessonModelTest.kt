package com.landolisp

import com.landolisp.data.model.LandolispJson
import com.landolisp.data.model.Lesson
import com.landolisp.data.model.Section
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonModelTest {

    @Test
    fun decodesAllSectionKinds() {
        val json = """
            {
              "id": "001-atoms",
              "title": "Atoms and S-expressions",
              "track": "fundamentals",
              "order": 1,
              "estimatedMinutes": 8,
              "completionSymbols": ["defun", "let"],
              "sections": [
                { "kind": "prose",   "markdown": "**Atoms** are the building blocks." },
                { "kind": "example", "code": "(+ 1 2)", "expected": "3", "explain": "Addition." },
                {
                  "kind": "exercise",
                  "prompt": "Define greet.",
                  "starter": "(defun greet (x) ...)",
                  "tests": [ { "call": "(greet \"x\")", "equals": "\"hello x\"" } ]
                }
              ]
            }
        """.trimIndent()

        val lesson = LandolispJson.decodeFromString<Lesson>(json)
        assertEquals("001-atoms", lesson.id)
        assertEquals("fundamentals", lesson.track)
        assertEquals(3, lesson.sections.size)

        val prose = lesson.sections[0] as Section.Prose
        assertTrue(prose.markdown.contains("Atoms"))

        val example = lesson.sections[1] as Section.Example
        assertEquals("(+ 1 2)", example.code)
        assertEquals("3", example.expected)

        val exercise = lesson.sections[2] as Section.Exercise
        assertEquals("Define greet.", exercise.prompt)
        assertEquals(1, exercise.tests.size)
        assertNotNull(exercise.tests.first().equals)
    }

    @Test
    fun ignoresUnknownKeys() {
        val json = """
            {
              "id": "002-fwd-compat",
              "title": "Forward compat",
              "track": "fundamentals",
              "order": 2,
              "futureField": "ignored",
              "sections": [
                { "kind": "prose", "markdown": "ok", "futureSectionField": 7 }
              ]
            }
        """.trimIndent()

        val lesson = LandolispJson.decodeFromString<Lesson>(json)
        assertEquals(1, lesson.sections.size)
        assertTrue(lesson.sections.first() is Section.Prose)
    }
}
