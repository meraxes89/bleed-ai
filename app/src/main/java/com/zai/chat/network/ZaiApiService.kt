package com.zai.chat.network

import com.zai.chat.config.ZaiConfig
import com.zai.chat.network.model.ChatCompletionRequest
import com.zai.chat.network.model.ChatDetailResponse
import com.zai.chat.network.model.ChatListItemResponse
import com.zai.chat.network.model.FileUploadResponse
import com.zai.chat.network.model.ModelsListResponse
import com.zai.chat.network.sse.ManualSseReader
import com.zai.chat.network.sse.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZaiApiService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Fully cold and lazy: nothing hits the network until collected, and
     * everything (connect + stream) runs on Dispatchers.IO.
     * Cancellation aborts the socket instantly via call.cancel(), so the
     * Stop button takes effect even while blocked in a read.
     */
    fun streamChatCompletion(request: ChatCompletionRequest): Flow<StreamEvent> = flow {
        val http = Request.Builder()
            .url(ZaiConfig.BASE_URL + ZaiConfig.COMPLETIONS_PATH)
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(request).toRequestBody(jsonMediaType))
            .build()

        val call = okHttpClient.newCall(http)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errSnippet = resp.body?.string().orEmpty().take(400)
                    emit(
                        StreamEvent.Error(
                            throwable = IOException("HTTP ${resp.code}: $errSnippet"),
                            partialContent = "",
                            partialReasoning = ""
                        )
                    )
                    return@flow
                }
                val body = resp.body
                if (body == null) {
                    emit(StreamEvent.Error(IOException("Empty response body"), "", ""))
                    return@flow
                }
                ManualSseReader(body, json).streamEvents().collect { emit(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(StreamEvent.Error(e, "", ""))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getChats(page: Int): List<ChatListItemResponse> = withContext(Dispatchers.IO) {
        val http = Request.Builder()
            .url("${ZaiConfig.BASE_URL}${ZaiConfig.CHATS_PATH}?page=$page")
            .get()
            .build()
        okHttpClient.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("getChats failed: HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            json.decodeFromString(body)
        }
    }

    suspend fun getChatDetail(chatId: String): ChatDetailResponse = withContext(Dispatchers.IO) {
        val http = Request.Builder()
            .url("${ZaiConfig.BASE_URL}${ZaiConfig.CHATS_PATH}$chatId")
            .get()
            .build()
        okHttpClient.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("getChatDetail failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty chat detail body")
            json.decodeFromString(body)
        }
    }

    suspend fun createChat(title: String): ChatListItemResponse = withContext(Dispatchers.IO) {
        // FE parity (reverse-engineered from the deployed bundle): the route
        // expects {"chat": {...chat object...}}, NOT {"title": "..."} — the old
        // shape was answered with HTTP 400 (Pydantic validation) on every
        // attempt. Minimal chat object mirrors the frontend's new-chat payload.
        val chatId = UUID.randomUUID().toString()
        val chatObj = buildJsonObject {
            put("id", chatId)
            put("title", title)
            put("models", json.encodeToJsonElement(listOf(ZaiConfig.MODEL_DEFAULT)))
            putJsonObject("history") { putJsonObject("messages") {} }
            put("tags", json.encodeToJsonElement(emptyList<String>()))
            put("timestamp", System.currentTimeMillis())
        }
        val payload = json.encodeToString(buildJsonObject { put("chat", chatObj) })
        val http = Request.Builder()
            .url("${ZaiConfig.BASE_URL}${ZaiConfig.CHATS_PATH}new")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        okHttpClient.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("createChat failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty createChat body")
            json.decodeFromString(body)
        }
    }

    suspend fun deleteChat(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val http = Request.Builder()
            .url("${ZaiConfig.BASE_URL}${ZaiConfig.CHATS_PATH}$chatId")
            .delete()
            .build()
        okHttpClient.newCall(http).execute().use { it.isSuccessful }
    }

    /**
     * Honest probe: throws on any HTTP or network failure so session verification
     * accurately reflects auth & connectivity state.
     */
    suspend fun pingAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        val http = Request.Builder()
            .url(ZaiConfig.BASE_URL + ZaiConfig.MODELS_PATH)
            .get()
            .build()
        okHttpClient.newCall(http).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            val body = response.body?.string() ?: throw IOException("Empty models response")
            json.decodeFromString<ModelsListResponse>(body).data.map { it.id }
        }
    }

    /** Never throws — model list failure falls back to the config default for UI picker. */
    suspend fun getAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            pingAvailableModels()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            listOf(ZaiConfig.MODEL_DEFAULT)
        }
    }

    suspend fun uploadFile(
        file: File,
        mimeType: String = "application/octet-stream",
        onProgress: ((bytesWritten: Long, contentLength: Long) -> Unit)? = null
    ): FileUploadResponse = withContext(Dispatchers.IO) {
        val fileBody = file.asRequestBody(mimeType.toMediaType())
        val requestBody = if (onProgress != null) {
            ProgressRequestBody(fileBody, onProgress)
        } else {
            fileBody
        }
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                requestBody
            )
            .build()
        val httpRequest = Request.Builder()
            .url(ZaiConfig.BASE_URL + ZaiConfig.UPLOAD_FILE_PATH)
            .post(multipartBody)
            .build()
        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty file upload body")
            json.decodeFromString<FileUploadResponse>(body)
        }
    }
}
