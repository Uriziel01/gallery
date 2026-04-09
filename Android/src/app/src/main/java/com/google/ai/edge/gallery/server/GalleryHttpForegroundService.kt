/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.server.openai.OpenAiErrorEnvelope
import com.google.ai.edge.gallery.server.openai.OpenAiErrors
import com.google.ai.edge.gallery.server.openai.OpenAiGatewayException
import com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionChunk
import com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionChunkChoice
import com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionChunkDelta
import com.google.ai.edge.gallery.server.openai.OpenAiChatMessageRole
import com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionsRequest
import com.google.ai.edge.gallery.server.openai.OpenAiResponsesRequest
import com.google.ai.edge.gallery.server.openai.OpenAiResponsesRequestValidator
import com.google.ai.edge.gallery.server.openai.OpenAiJson
import com.google.ai.edge.gallery.server.openai.OpenAiLiteRtLmGateway
import com.google.ai.edge.gallery.server.openai.OpenAiModelListResponse
import com.google.ai.edge.gallery.server.openai.OpenAiModelSummary
import com.google.ai.edge.gallery.server.openai.OpenAiValidationResult
import com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionsRequestValidator
import dagger.hilt.android.AndroidEntryPoint
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.LinkedHashMap
import java.util.UUID
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@AndroidEntryPoint
class GalleryHttpForegroundService : Service() {
  @Inject lateinit var openAiGateway: OpenAiLiteRtLmGateway
  @Inject lateinit var serverModelAccess: ServerModelAccess
  @Inject lateinit var serverConfigStore: ServerConfigStore

  private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var httpServer: GalleryNanoHttpServer? = null
  private var activePort: Int = DEFAULT_PORT
  private var activeBearerToken: String = ""

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return when (intent?.action ?: ACTION_START) {
      ACTION_STOP -> {
        stopServiceShell()
        START_NOT_STICKY
      }
      ACTION_START -> {
        startServiceShell()
        START_STICKY
      }
      else -> {
        Log.w(TAG, "Ignoring unknown action: ${intent?.action}")
        START_NOT_STICKY
      }
    }
  }

  override fun onDestroy() {
    requestScope.cancel()
    stopHttpServer()
    runCatching { runBlocking { openAiGateway.close() } }
      .onFailure { Log.w(TAG, "Failed to close OpenAI gateway during service shutdown.", it) }
    super.onDestroy()
  }

  private fun startServiceShell() {
    createNotificationChannel()
    val config = serverConfigStore.readConfig()
    activePort = config.port
    activeBearerToken = config.bearerToken

    try {
      val foregroundServiceType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
          0
        }
      startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceType)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to promote embedded HTTP server to a foreground service.", e)
      stopSelf()
      return
    }

    runCatching { runBlocking { openAiGateway.reopen() } }
      .onFailure {
        Log.e(TAG, "Failed to reopen OpenAI inference gateway during service startup.", it)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return
      }

    if (httpServer != null) {
      return
    }

    val server =
      GalleryNanoHttpServer(port = activePort, routeDispatcher = ::dispatchRoute)
    try {
      server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
      httpServer = server
    } catch (e: IOException) {
      Log.e(TAG, "Failed to start embedded HTTP server.", e)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun stopServiceShell() {
    stopHttpServer()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun stopHttpServer() {
    httpServer?.stop()
    httpServer = null
  }

  private fun dispatchRoute(session: IHTTPSession): Response {
    if (
      session.uri == V1_MODELS_PATH ||
        session.uri == V1_CHAT_COMPLETIONS_PATH ||
        session.uri == V1_RESPONSES_PATH
    ) {
      val authResponse = validateAuthorization(session)
      if (authResponse != null) {
        return authResponse
      }
    }

    return when (session.uri) {
      HEALTH_PATH -> handleHealth(session)
      V1_MODELS_PATH -> handleModels(session)
      V1_CHAT_COMPLETIONS_PATH -> handleChatCompletions(session)
      V1_RESPONSES_PATH -> handleResponses(session)
      else ->
        NanoHTTPD.newFixedLengthResponse(
          Response.Status.NOT_FOUND,
          MIME_JSON,
          """{"error":"not_found"}""",
        )
    }
  }

  private fun handleResponses(session: IHTTPSession): Response {
    if (session.method != NanoHTTPD.Method.POST) {
      return newOpenAiErrorResponse(
        status = Response.Status.METHOD_NOT_ALLOWED,
        error =
          OpenAiErrors.invalidRequest(
            message = "Method ${session.method} is not allowed for ${session.uri}.",
            code = "method_not_allowed",
          ),
      )
    }

    val requestBody =
      readRequestBody(session)
        ?: return newOpenAiErrorResponse(
          status = Response.Status.BAD_REQUEST,
          error =
            OpenAiErrors.invalidRequest(
              message = "Request body must be valid JSON.",
              code = "invalid_json",
            ),
        )

    val validationResult = OpenAiResponsesRequestValidator.parseAndValidate(requestBody)
    val request =
      when (validationResult) {
        is OpenAiValidationResult.Invalid -> {
          Log.w(
            TAG,
            "Rejected responses request: code=${validationResult.error.error.code}, param=${validationResult.error.error.param}",
          )
          return newOpenAiErrorResponse(
            status = Response.Status.BAD_REQUEST,
            error = validationResult.error,
          )
        }
        is OpenAiValidationResult.Valid -> validationResult.value
      }

    return if (request.stream) {
      newResponsesStreamResponse(request)
    } else {
      try {
        val completionRequest = OpenAiResponsesRequestValidator.toChatCompletionsRequest(request)
        val completion = runBlocking { openAiGateway.createChatCompletion(completionRequest) }
        newFixedLengthResponsesResponse(request = request, completion = completion.choices.first().message.content)
      } catch (e: OpenAiGatewayException) {
        val error =
          e.error
            ?: OpenAiErrors.invalidRequest(
              message = e.message ?: "Failed to process responses request.",
              code = "gateway_error",
            )
        newOpenAiErrorResponse(status = Response.Status.BAD_REQUEST, error = error)
      } catch (e: IllegalArgumentException) {
        newOpenAiErrorResponse(
          status = Response.Status.BAD_REQUEST,
          error =
            OpenAiErrors.invalidRequest(
              message = e.message ?: "Invalid responses request.",
              code = "invalid_request",
            ),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Unexpected error while handling responses.", e)
        newOpenAiErrorResponse(
          status = Response.Status.INTERNAL_ERROR,
          error =
            OpenAiErrors.invalidRequest(
              message = "Internal server error.",
              code = "internal_error",
            ),
        )
      }
    }
  }

  private fun validateAuthorization(session: IHTTPSession): Response? {
    val expectedToken = activeBearerToken.trim()
    if (expectedToken.isEmpty()) {
      return newUnauthorizedOpenAiErrorResponse(
        message = "Bearer token is not configured for the server.",
        code = "auth_not_configured",
      )
    }

    val authorizationHeader =
      session.headers.entries
        .firstOrNull { it.key.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
        ?.value
        ?.trim()
        .orEmpty()
    if (!authorizationHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
      return newUnauthorizedOpenAiErrorResponse(
        message = "Missing or invalid Authorization header.",
        code = "invalid_api_key",
      )
    }

    val providedToken = authorizationHeader.substringAfter(" ", "").trim()
    if (providedToken != expectedToken) {
      return newUnauthorizedOpenAiErrorResponse(
        message = "Invalid bearer token.",
        code = "invalid_api_key",
      )
    }

    return null
  }

  private fun handleHealth(session: IHTTPSession): Response {
    if (session.method != NanoHTTPD.Method.GET) {
      return NanoHTTPD.newFixedLengthResponse(
        Response.Status.METHOD_NOT_ALLOWED,
        MIME_JSON,
        """{"error":"method_not_allowed"}""",
      )
    }

    return NanoHTTPD.newFixedLengthResponse(
      Response.Status.OK,
      MIME_JSON,
      """{"status":"ok"}""",
    )
  }

  private fun handleModels(session: IHTTPSession): Response {
    if (session.method != NanoHTTPD.Method.GET) {
      return newOpenAiErrorResponse(
        status = Response.Status.METHOD_NOT_ALLOWED,
        error =
          OpenAiErrors.invalidRequest(
            message = "Method ${session.method} is not allowed for ${session.uri}.",
            code = "method_not_allowed",
          ),
      )
    }

    val selectedModel =
      serverModelAccess.getSelectedModel()
        ?: return newOpenAiErrorResponse(
          status = Response.Status.SERVICE_UNAVAILABLE,
          error =
            OpenAiErrors.invalidRequest(
              message = "No model is selected for the OpenAI-compatible server.",
              code = "model_not_selected",
            ),
        )

    if (BuiltInTaskId.LLM_CHAT !in selectedModel.taskIds) {
      return newOpenAiErrorResponse(
        status = Response.Status.SERVICE_UNAVAILABLE,
        error =
          OpenAiErrors.invalidRequest(
            message = "The selected model does not support chat completions.",
            code = "unsupported_model_task",
            param = "model",
          ),
      )
    }

    return newJsonResponse(
      status = Response.Status.OK,
      body =
        OpenAiModelListResponse(
          data =
            listOf(
              OpenAiModelSummary(
                id = selectedModel.model.name,
                created = 0L,
                ownedBy = OPENAI_MODEL_OWNER,
              )
            )
        ),
    )
  }

  private fun handleChatCompletions(session: IHTTPSession): Response {
    if (session.method != NanoHTTPD.Method.POST) {
      return newOpenAiErrorResponse(
        status = Response.Status.METHOD_NOT_ALLOWED,
        error =
          OpenAiErrors.invalidRequest(
            message = "Method ${session.method} is not allowed for ${session.uri}.",
            code = "method_not_allowed",
          ),
      )
    }

    val requestBody =
      readRequestBody(session)
        ?: return newOpenAiErrorResponse(
          status = Response.Status.BAD_REQUEST,
          error =
            OpenAiErrors.invalidRequest(
              message = "Request body must be valid JSON.",
              code = "invalid_json",
            ),
        )

    val validationResult = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody)
    val request =
      when (validationResult) {
        is OpenAiValidationResult.Invalid -> {
          Log.w(
            TAG,
            "Rejected chat completions request: code=${validationResult.error.error.code}, param=${validationResult.error.error.param}",
          )
          return newOpenAiErrorResponse(
            status = Response.Status.BAD_REQUEST,
            error = validationResult.error,
          )
        }
        is OpenAiValidationResult.Valid -> validationResult.value
      }

    if (request.stream) {
      return newChatCompletionStreamResponse(request)
    }

    return try {
      val completion = runBlocking { openAiGateway.createChatCompletion(request) }
      newChatCompletionJsonResponse(status = Response.Status.OK, body = completion)
    } catch (e: OpenAiGatewayException) {
      val error =
        e.error
          ?: OpenAiErrors.invalidRequest(
            message = e.message ?: "Failed to process chat completion request.",
            code = "gateway_error",
          )
      newOpenAiErrorResponse(status = Response.Status.BAD_REQUEST, error = error)
    } catch (e: IllegalArgumentException) {
      newOpenAiErrorResponse(
        status = Response.Status.BAD_REQUEST,
        error =
          OpenAiErrors.invalidRequest(
            message = e.message ?: "Invalid chat completion request.",
            code = "invalid_request",
          ),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Unexpected error while handling chat completions.", e)
      newOpenAiErrorResponse(
        status = Response.Status.INTERNAL_ERROR,
        error =
          OpenAiErrors.invalidRequest(
            message = "Internal server error.",
            code = "internal_error",
          ),
      )
    }
  }

  private fun readRequestBody(session: IHTTPSession): String? {
    val files = HashMap<String, String>()
    return try {
      session.parseBody(files)
      files["postData"]
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse HTTP request body for ${session.uri}", e)
      null
    }
  }

  private fun newJsonResponse(status: Response.Status, body: OpenAiModelListResponse): Response {
    return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, OpenAiJson.encode(body))
  }

  private fun newChatCompletionJsonResponse(
    status: Response.Status,
    body: com.google.ai.edge.gallery.server.openai.OpenAiChatCompletionsResponse,
  ): Response {
    return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, OpenAiJson.encode(body))
  }

  private fun newFixedLengthResponsesResponse(
    request: OpenAiResponsesRequest,
    completion: String,
  ): Response {
    val responseId = "resp-${UUID.randomUUID()}"
    val created = System.currentTimeMillis() / 1000L
    val responseBody =
      buildJsonObject {
        put("id", responseId)
        put("object", "response")
        put("created_at", created)
        put("model", request.model)
        put("status", "completed")
        put(
          "output",
          buildJsonArray {
            add(
              buildJsonObject {
                put("id", "msg-${UUID.randomUUID()}")
                put("object", "response.output_message")
                put("type", "message")
                put("status", "completed")
                put("role", "assistant")
                put(
                  "content",
                  buildJsonArray {
                    add(
                      buildJsonObject {
                        put("type", "output_text")
                        put("text", completion)
                      }
                    )
                  },
                )
              }
            )
          },
        )
      }

    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, OpenAiJson.encode(responseBody))
  }

  private fun newResponsesStreamResponse(request: OpenAiResponsesRequest): Response {
    val responseId = "resp-${UUID.randomUUID()}"
    val created = System.currentTimeMillis() / 1000L
    val itemId = "msg-${UUID.randomUUID()}"
    val chatRequest = OpenAiResponsesRequestValidator.toChatCompletionsRequest(request)
    val stream = StreamingSseConnection()
    val deltas = Channel<String>(Channel.UNLIMITED)
    var sequenceNumber = 1

    requestScope.launch {
      try {
        if (
          !stream.writeResponsesEvent(
            buildJsonObject {
              put("type", "response.created")
              put("event_id", "evt-${UUID.randomUUID()}")
              put("sequence_number", sequenceNumber++)
              put(
                "response",
                buildResponsesObject(
                  responseId = responseId,
                  created = created,
                  model = request.model,
                  status = "in_progress",
                  text = null,
                  includeOutput = false,
                ),
              )
            }
          )
        ) {
          return@launch
        }

        if (
          !stream.writeResponsesEvent(
            buildJsonObject {
              put("type", "response.output_item.added")
              put("event_id", "evt-${UUID.randomUUID()}")
              put("response_id", responseId)
              put("output_index", 0)
              put("sequence_number", sequenceNumber++)
              put(
                "item",
                buildResponsesOutputMessageItem(
                  itemId = itemId,
                  status = "in_progress",
                  text = null,
                ),
              )
            }
          )
        ) {
          return@launch
        }

        val producer =
          launch {
            try {
              openAiGateway.streamChatCompletion(chatRequest) { partialDelta ->
                deltas.trySend(partialDelta).isSuccess
              }
            } finally {
              deltas.close()
            }
          }

        var finalText = ""
        for (partialDelta in deltas) {
          if (
            !stream.writeResponsesEvent(
              buildJsonObject {
                put("type", "response.output_text.delta")
                put("event_id", "evt-${UUID.randomUUID()}")
                put("response_id", responseId)
                put("item_id", itemId)
                put("output_index", 0)
                put("content_index", 0)
                put("delta", partialDelta)
                put("sequence_number", sequenceNumber++)
              }
            )
          ) {
            break
          }
          finalText += partialDelta
        }

        producer.join()

        if (
          !stream.writeResponsesEvent(
            buildJsonObject {
              put("type", "response.output_text.done")
              put("event_id", "evt-${UUID.randomUUID()}")
              put("response_id", responseId)
              put("item_id", itemId)
              put("output_index", 0)
              put("content_index", 0)
              put("text", finalText)
              put("sequence_number", sequenceNumber++)
            }
          )
        ) {
          return@launch
        }
        if (
          !stream.writeResponsesEvent(
            buildJsonObject {
              put("type", "response.output_item.done")
              put("event_id", "evt-${UUID.randomUUID()}")
              put("response_id", responseId)
              put("output_index", 0)
              put("sequence_number", sequenceNumber++)
              put(
                "item",
                buildResponsesOutputMessageItem(
                  itemId = itemId,
                  status = "completed",
                  text = finalText,
                ),
              )
            }
          )
        ) {
          return@launch
        }
        stream.writeResponsesEvent(
          buildJsonObject {
            put("type", "response.completed")
            put("event_id", "evt-${UUID.randomUUID()}")
            put("sequence_number", sequenceNumber++)
            put(
              "response",
              buildResponsesObject(
                responseId = responseId,
                created = created,
                model = request.model,
                status = "completed",
                text = finalText,
                includeOutput = true,
                itemId = itemId,
              ),
            )
          }
        )
      } catch (_: CancellationException) {
        // Client disconnected or the service is shutting down.
      } catch (e: OpenAiGatewayException) {
        Log.e(TAG, "Streaming responses request failed.", e)
        runCatching { stream.writeDone() }
      } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Invalid responses request.", e)
        runCatching { stream.writeDone() }
      } catch (e: Exception) {
        Log.e(TAG, "Unexpected error while handling responses.", e)
        runCatching { stream.writeDone() }
      } finally {
        stream.close()
      }
    }

    return newChunkedSseResponse(stream.inputStream())
  }

  private fun newOpenAiErrorResponse(status: Response.Status, error: OpenAiErrorEnvelope): Response {
    return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, OpenAiJson.encode(error))
  }

  private fun newUnauthorizedOpenAiErrorResponse(message: String, code: String): Response {
    return newOpenAiErrorResponse(
        status = Response.Status.UNAUTHORIZED,
        error = OpenAiErrors.invalidRequest(message = message, code = code),
      )
      .apply { addHeader("WWW-Authenticate", "Bearer") }
  }

  private fun buildResponsesObject(
    responseId: String,
    created: Long,
    model: String,
    status: String,
    text: String?,
    includeOutput: Boolean,
    itemId: String? = null,
  ) =
    buildJsonObject {
      put("id", responseId)
      put("object", "response")
      put("created_at", created)
      put("model", model)
      put("status", status)
      if (includeOutput && text != null && itemId != null) {
        put(
          "output",
          buildJsonArray {
            add(buildResponsesOutputMessageItem(itemId = itemId, status = status, text = text))
          },
        )
      } else {
        put("output", buildJsonArray { })
      }
    }

  private fun buildResponsesOutputMessageItem(
    itemId: String,
    status: String,
    text: String?,
  ) =
    buildJsonObject {
      put("id", itemId)
      put("object", "response.output_message")
      put("type", "message")
      put("status", status)
      put("role", "assistant")
      put(
        "content",
        buildJsonArray {
          if (text != null) {
            add(
              buildJsonObject {
                put("type", "output_text")
                put("text", text)
              }
            )
          }
        },
      )
    }

  private fun newChatCompletionStreamResponse(request: OpenAiChatCompletionsRequest): Response {
    val completionId = "chatcmpl-${UUID.randomUUID()}"
    val created = System.currentTimeMillis() / 1000L
    val stream = StreamingSseConnection()
    val deltas = Channel<String>(Channel.UNLIMITED)
    val systemFingerprint = request.model
    requestScope.launch {
      try {
        val producer =
          launch {
            try {
              openAiGateway.streamChatCompletion(request) { partialDelta ->
                deltas.trySend(partialDelta).isSuccess
              }
            } finally {
              deltas.close()
            }
          }

        val firstDelta = deltas.receiveCatching().getOrNull()
        if (firstDelta == null) {
          producer.join()
          stream.writeDone()
          return@launch
        }

        if (
          !stream.writeChatChunk(
            OpenAiChatCompletionChunk(
              id = completionId,
              created = created,
              model = request.model,
              systemFingerprint = systemFingerprint,
              choices =
                listOf(
                  OpenAiChatCompletionChunkChoice(
                    index = 0,
                    finishReason = JsonNull,
                    delta =
                      OpenAiChatCompletionChunkDelta(
                        role = OpenAiChatMessageRole.ASSISTANT,
                        content = firstDelta,
                      ),
                  )
                ),
            )
          )
        ) {
          return@launch
        }

        for (partialDelta in deltas) {
          if (
            !stream.writeChatChunk(
              OpenAiChatCompletionChunk(
                id = completionId,
                created = created,
                model = request.model,
                systemFingerprint = systemFingerprint,
                choices =
                  listOf(
                    OpenAiChatCompletionChunkChoice(
                      index = 0,
                      finishReason = JsonNull,
                      delta = OpenAiChatCompletionChunkDelta(content = partialDelta),
                    )
                  ),
              )
            )
          ) {
            break
          }
        }

        producer.join()

        if (
          !stream.writeChatChunk(
            OpenAiChatCompletionChunk(
              id = completionId,
              created = created,
              model = request.model,
              systemFingerprint = systemFingerprint,
                choices =
                  listOf(
                    OpenAiChatCompletionChunkChoice(
                      index = 0,
                      delta = OpenAiChatCompletionChunkDelta(),
                      finishReason = JsonPrimitive("stop"),
                    )
                  ),
            )
          )
        ) {
          return@launch
        }
        stream.writeDone()
      } catch (_: CancellationException) {
        // Client disconnected or the service is shutting down.
      } catch (e: OpenAiGatewayException) {
        Log.e(TAG, "Streaming chat completion failed.", e)
        runCatching { stream.writeDone() }
      } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Invalid streaming chat completion request.", e)
        runCatching { stream.writeDone() }
      } catch (e: Exception) {
        Log.e(TAG, "Unexpected error while handling streaming chat completions.", e)
        runCatching { stream.writeDone() }
      } finally {
        stream.close()
      }
    }

    return newChunkedSseResponse(stream.inputStream())
  }

  private fun newChunkedSseResponse(inputStream: InputStream): Response {
    return StreamingSseResponse(Response.Status.OK, MIME_EVENT_STREAM, inputStream).apply {
      addHeader("Cache-Control", "no-cache")
      addHeader("X-Accel-Buffering", "no")
      addHeader("Connection", "keep-alive")
    }
  }

  private fun createNotificationChannel() {
    val notificationManager =
      getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel =
      NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "Embedded HTTP server",
        NotificationManager.IMPORTANCE_LOW,
      )
        .apply { description = "Foreground notification for the embedded OpenAI-compatible server." }
    notificationManager.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val contentIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setContentTitle("Local server running")
      .setContentText("Serving health checks on port $activePort")
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .build()
  }

  private class GalleryNanoHttpServer(
    port: Int,
    private val routeDispatcher: (IHTTPSession) -> Response,
  ) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response = routeDispatcher(session)
  }

  private class StreamingSseConnection : AutoCloseable {
    private val queue = LinkedBlockingQueue<StreamEvent>()
    @Volatile private var closed = false

    fun inputStream(): InputStream = QueueInputStream()

    @Synchronized
    fun writeChatChunk(chunk: OpenAiChatCompletionChunk): Boolean {
      return writeText(buildString {
        append("data: ")
        append(OpenAiJson.encode(chunk))
        append("\n\n")
      })
    }

    @Synchronized
    fun writeResponsesEvent(event: kotlinx.serialization.json.JsonObject): Boolean {
      return writeText(buildString {
        append("data: ")
        append(OpenAiJson.encode(event))
        append("\n\n")
      })
    }

    @Synchronized
    fun writeDone(): Boolean {
      return writeText("data: [DONE]\n\n")
    }

    @Synchronized
    private fun writeText(text: String): Boolean {
      if (closed) {
        return false
      }
      return try {
        queue.put(StreamEvent.Bytes(text.toByteArray(Charsets.UTF_8)))
        true
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        closed = true
        false
      }
    }

    @Synchronized
    override fun close() {
      if (closed) {
        return
      }
      closed = true
      queue.offer(StreamEvent.End)
    }

    private sealed class StreamEvent {
      data class Bytes(val bytes: ByteArray) : StreamEvent()
      data object End : StreamEvent()
    }

    private inner class QueueInputStream : InputStream() {
      private var buffer: ByteArray = ByteArray(0)
      private var index: Int = 0

      override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read == -1) -1 else one[0].toInt() and 0xFF
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
          return 0
        }

        while (true) {
          if (index < buffer.size) {
            val copyLength = minOf(len, buffer.size - index)
            System.arraycopy(buffer, index, b, off, copyLength)
            index += copyLength
            return copyLength
          }

          val event =
            try {
              queue.take()
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
              return -1
            }

          when (event) {
            is StreamEvent.Bytes -> {
              buffer = event.bytes
              index = 0
            }
            StreamEvent.End -> return -1
          }
        }
      }
    }
  }

  private class StreamingSseResponse(
    status: Response.Status,
    mimeType: String,
    data: InputStream,
  ) : Response(status, mimeType, data, -1) {
    private val responseHeaders = LinkedHashMap<String, String>()
    private val responseStatus = status
    private val responseMimeType = mimeType
    private val responseStream = data

    override fun addHeader(name: String, value: String) {
      responseHeaders[name] = value
      super.addHeader(name, value)
    }

    override fun send(outputStream: OutputStream) {
      val statusLine = "HTTP/1.1 ${responseStatus.description} \r\n"
      val headerBlock = buildString {
        append(statusLine)
        if (responseMimeType.isNotBlank()) {
          append("Content-Type: ")
          append(responseMimeType)
          append("\r\n")
        }
        if (responseHeaders.keys.none { it.equals("Date", ignoreCase = true) }) {
          append("Date: ")
          append(formatHttpDate(Date()))
          append("\r\n")
        }
        if (responseHeaders.keys.none { it.equals("Connection", ignoreCase = true) }) {
          append("Connection: keep-alive\r\n")
        }
        for ((name, value) in responseHeaders) {
          if (name.equals("Date", ignoreCase = true) || name.equals(
              "Connection",
              ignoreCase = true,
            ) || name.equals("Transfer-Encoding", ignoreCase = true) || name.equals(
              "Content-Length",
              ignoreCase = true,
            )
          ) {
            continue
          }
          append(name)
          append(": ")
          append(value)
          append("\r\n")
        }
        append("Transfer-Encoding: chunked\r\n")
        append("\r\n")
      }

      outputStream.write(headerBlock.toByteArray(Charsets.UTF_8))
      outputStream.flush()

      val buffer = ByteArray(1024)
      try {
        while (true) {
          val read = responseStream.read(buffer)
          if (read <= 0) {
            break
          }
          outputStream.write(read.toString(16).toByteArray(Charsets.US_ASCII))
          outputStream.write("\r\n".toByteArray(Charsets.US_ASCII))
          outputStream.write(buffer, 0, read)
          outputStream.write("\r\n".toByteArray(Charsets.US_ASCII))
          outputStream.flush()
        }
        outputStream.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        outputStream.flush()
      } finally {
        runCatching { responseStream.close() }
      }
    }

    private fun formatHttpDate(date: Date): String {
      val format = java.text.SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US)
      format.timeZone = TimeZone.getTimeZone("GMT")
      return format.format(date)
    }
  }

  companion object {
    const val ACTION_START =
      "com.google.ai.edge.gallery.server.action.START_HTTP_FOREGROUND_SERVICE"
    const val ACTION_STOP =
      "com.google.ai.edge.gallery.server.action.STOP_HTTP_FOREGROUND_SERVICE"
    const val DEFAULT_PORT = 8080

    private const val TAG = "AGHttpFgService"
    private const val NOTIFICATION_CHANNEL_ID = "gallery_embedded_http_server"
    private const val NOTIFICATION_ID = 4040
    private const val HEALTH_PATH = "/health"
    private const val V1_MODELS_PATH = "/v1/models"
    private const val V1_CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    private const val V1_RESPONSES_PATH = "/v1/responses"
    private const val MIME_JSON = "application/json; charset=utf-8"
    private const val MIME_EVENT_STREAM = "text/event-stream"
    private const val OPENAI_MODEL_OWNER = "google-ai-edge-gallery"
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val BEARER_PREFIX = "Bearer "

    fun createStartIntent(context: Context): Intent {
      return Intent(context, GalleryHttpForegroundService::class.java).setAction(ACTION_START)
    }

    fun createStopIntent(context: Context): Intent {
      return Intent(context, GalleryHttpForegroundService::class.java).setAction(ACTION_STOP)
    }
  }
}
