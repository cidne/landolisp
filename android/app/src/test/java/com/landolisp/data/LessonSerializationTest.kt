package com.landolisp.data

import com.landolisp.data.model.LandolispJson
import com.landolisp.data.model.Lesson
import com.landolisp.data.model.Section
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips a representative lesson through [LandolispJson] and asserts the
 * full schema (every section kind, optional/default fields, and the polymorphic
 * `kind` discriminator) survives unchanged.
 *
 * The JSON literal below is intentionally inline so this test does not depend
 * on the real curriculum files in `/curriculum/`.
 */
class LessonSerializationTest {

    private val sampleJson = """
        {
          "id": "042-sample",
          "title": "Round-trip sample lesson",
          "track": "fundamentals",
          "order": 42,
          "estimatedMinutes": 7,
          "prerequisites": ["001-atoms"],
          "completionSymbols": ["defun", "let", "lambda"],
          "sections": [
            { "kind": "prose", "markdown": "# Heading\nA paragraph with **bold** text." },
            {
              "kind": "example",
              "code": "(+ 1 2)",
              "expected": "3",
              "explain": "Trivial addition."
            },
            {
              "kind": "exercise",
              "prompt": "Define greet so that it returns 'hello, NAME'.",
              "starter": "(defun greet (name) ...)",
              "hint": "Use FORMAT NIL.",
              "tests": [
                { "call": "(greet \"Lisp\")", "equals": "\"hello, Lisp\"" },
                { "call": "(greet \"world\")", "equals": "\"hello, world\"" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesAllTopLevelFields() {
        val lesson = LandolispJson.decodeFromString<Lesson>(sampleJson)

        assertEquals("042-sample", lesson.id)
        assertEquals("Round-trip sample lesson", lesson.title)
        assertEquals("fundamentals", lesson.track)
        assertEquals(42, lesson.order)
        assertEquals(7, lesson.estimatedMinutes)
        assertEquals(listOf("001-atoms"), lesson.prerequisites)
        assertEquals(listOf("defun", "let", "lambda"), lesson.completionSymbols)
        assertEquals(3, lesson.sections.size)
    }

    @Test
    fun decodesEachSectionKind() {
        val lesson = LandolispJson.decodeFromString<Lesson>(sampleJson)

        val prose = lesson.sections[0] as Section.Prose
        assertTrue(prose.markdown.startsWith("# Heading"))

        val example = lesson.sections[1] as Section.Example
        assertEquals("(+ 1 2)", example.code)
        assertEquals("3", example.expected)
        assertEquals("Trivial addition.", example.explain)

        val exercise = lesson.sections[2] as Section.Exercise
        assertEquals("Define greet so that it returns 'hello, NAME'.", exercise.prompt)
        assertEquals("(defun greet (name) ...)", exercise.starter)
        assertEquals("Use FORMAT NIL.", exercise.hint)
        assertEquals(2, exercise.tests.size)
        assertEquals("(greet \"Lisp\")", exercise.tests[0].call)
        assertEquals("\"hello, Lisp\"", exercise.tests[0].equals)
    }

    @Test
    fun roundTripPreservesData() {
        val parsed = LandolispJson.decodeFromString<Lesson>(sampleJson)
        val reSerialized = LandolispJson.encodeToString(parsed)
        val reParsed = LandolispJson.decodeFromString<Lesson>(reSerialized)

        assertEquals(parsed, reParsed)
        // Sanity check: the discriminator must be present on each section.
        assertTrue(reSerialized.contains("\"kind\":\"prose\""))
        assertTrue(reSerialized.contains("\"kind\":\"example\""))
        assertTrue(reSerialized.contains("\"kind\":\"exercise\""))
    }

    @Test
    fun optionalFieldsDefaultGracefully() {
        val minimal = """
            {
              "id": "099-minimal",
              "title": "Minimal lesson",
              "track": "fundamentals",
              "order": 99,
              "sections": [
                { "kind": "prose", "markdown": "Hi." }
              ]
            }
        """.trimIndent()

        val lesson = LandolispJson.decodeFromString<Lesson>(minimal)
        assertEquals("099-minimal", lesson.id)
        assertNull(lesson.estimatedMinutes)
        assertTrue(lesson.prerequisites.isEmpty())
        assertTrue(lesson.completionSymbols.isEmpty())
        assertNotNull(lesson.sections.first())
    }
}
