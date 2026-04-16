package com.landolisp.data.api

import com.landolisp.data.model.EvalRequest
import com.landolisp.data.model.EvalResponse
import com.landolisp.data.model.HealthResponse
import com.landolisp.data.model.QuickloadRequest
import com.landolisp.data.model.QuickloadResponse
import com.landolisp.data.model.SandboxFile
import com.landolisp.data.model.SessionResponse
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit interface for the sandbox HTTP API. Mirrors `docs/API.md`.
 *
 * All methods are `suspend`. Callers should funnel through [com.landolisp.data.SandboxRepository]
 * which handles session lifecycle and 404 recovery.
 */
interface SandboxApi {

    @POST("v1/sessions")
    suspend fun createSession(): SessionResponse

    @POST("v1/sessions/{id}/eval")
    suspend fun eval(
        @Path("id") sessionId: String,
        @Body body: EvalRequest,
    ): Response<EvalResponse>

    @POST("v1/sessions/{id}/quickload")
    suspend fun quickload(
        @Path("id") sessionId: String,
        @Body body: QuickloadRequest,
    ): Response<QuickloadResponse>

    @GET("v1/sessions/{id}/files")
    suspend fun listFiles(
        @Path("id") sessionId: String,
    ): Response<List<SandboxFile>>

    @GET("v1/sessions/{id}/files/{path}")
    suspend fun readFile(
        @Path("id") sessionId: String,
        @Path("path", encoded = true) path: String,
    ): Response<ResponseBody>

    @PUT("v1/sessions/{id}/files/{path}")
    suspend fun writeFile(
        @Path("id") sessionId: String,
        @Path("path", encoded = true) path: String,
        @Body body: RequestBody,
    ): Response<Unit>

    @DELETE("v1/sessions/{id}/files/{path}")
    suspend fun deleteFile(
        @Path("id") sessionId: String,
        @Path("path", encoded = true) path: String,
    ): Response<Unit>

    @GET("v1/health")
    suspend fun health(): HealthResponse
}
