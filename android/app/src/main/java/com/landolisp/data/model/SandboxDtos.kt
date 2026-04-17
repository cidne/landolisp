package com.landolisp.data.model

import kotlinx.serialization.Serializable

// ---------- Sessions ----------

@Serializable
data class SessionResponse(
    val sessionId: String,
    val expiresAt: String,
)

/** Spec-facing alias used in the Architecture doc and prompt. */
typealias SessionCreated = SessionResponse

// ---------- Eval ----------

@Serializable
data class EvalRequest(
    val code: String,
)

@Serializable
data class EvalResponse(
    val stdout: String = "",
    val stderr: String = "",
    val value: String? = null,
    val elapsedMs: Long = 0L,
    val condition: EvalCondition? = null,
)

@Serializable
data class EvalCondition(
    val type: String,
    val message: String,
)

// ---------- Quickload ----------

@Serializable
data class QuickloadRequest(
    val system: String,
)

@Serializable
data class QuickloadResponse(
    val loaded: Boolean,
    val log: String = "",
)

// ---------- Files ----------

@Serializable
data class SandboxFile(
    val path: String,
    val size: Long,
)

// ---------- Health ----------

@Serializable
data class HealthResponse(
    val status: String,
    val sbclVersion: String,
    val qlDist: String,
)

// ---------- Errors ----------

@Serializable
data class SandboxError(
    val error: String,
    val message: String,
)
