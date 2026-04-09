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

package com.google.ai.edge.gallery.server.openai

import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.server.ServerModelAccess
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class OpenAiLiteRtLmGateway
@Inject
constructor(
  private val serverModelAccess: ServerModelAccess,
  private val requestSerializationGuard: OpenAiRequestSerializationGuard,
) {
  private var activeModelName: String? = null

  suspend fun createChatCompletion(
    request: OpenAiChatCompletionsRequest
  ): OpenAiChatCompletionsResponse {
    return requestSerializationGuard.withSerializedAccess {
      currentCoroutineContext().ensureActive()

      val model = resolveModelForRequest(request)
      val prompt = OpenAiTranscriptSerializer.serialize(request)

      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = prompt.systemInstruction,
      )

      val completion = runSingleTextInference(model = model, transcript = prompt.transcript)

      OpenAiChatCompletionsResponse(
        id = "chatcmpl-${UUID.randomUUID()}",
        created = System.currentTimeMillis() / 1000L,
        model = model.name,
        systemFingerprint = model.name,
        choices =
          listOf(
            OpenAiChatCompletionChoice(
              index = 0,
              message =
                OpenAiChatCompletionResponseMessage(
                  role = OpenAiChatMessageRole.ASSISTANT,
                  content = completion,
                ),
              finishReason = "stop",
            )
          ),
      )
    }
  }

  suspend fun streamChatCompletion(
    request: OpenAiChatCompletionsRequest,
    onDelta: (String) -> Boolean,
  ) {
    requestSerializationGuard.withSerializedAccess {
      currentCoroutineContext().ensureActive()

      val model = resolveModelForRequest(request)
      val prompt = OpenAiTranscriptSerializer.serialize(request)

      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = prompt.systemInstruction,
      )

      runStreamingTextInference(
        model = model,
        transcript = prompt.transcript,
        onDelta = onDelta,
      )
    }
  }

  suspend fun close() {
    requestSerializationGuard.close {
      val modelName = activeModelName ?: return@close
      activeModelName = null
      serverModelAccess.cleanupModelByName(
        name = modelName,
        preferredTaskId = BuiltInTaskId.LLM_CHAT,
      )
    }
  }

  suspend fun reopen() {
    requestSerializationGuard.reset()
  }

  private suspend fun resolveModelForRequest(request: OpenAiChatCompletionsRequest): Model {
    val selectedModelName =
      serverModelAccess.getSelectedModelName()
        ?: throw OpenAiGatewayException.invalidRequest(
          message = "No model is selected for OpenAI chat completions.",
          code = "model_not_selected",
          param = "model",
        )

    maybeReleaseInactiveModel(selectedModelName)

    return try {
      serverModelAccess.initializeModelByName(
        name = selectedModelName,
        preferredTaskId = BuiltInTaskId.LLM_CHAT,
      )
    } catch (e: OpenAiGatewayException) {
      throw e
    } catch (e: IllegalArgumentException) {
      throw OpenAiGatewayException.invalidRequest(
        message = e.message ?: "The selected model does not support chat completions.",
        code = "unsupported_model_task",
        param = "model",
        cause = e,
      )
    }.also { model ->
      activeModelName = model.name
    }
  }

  private suspend fun maybeReleaseInactiveModel(selectedModelName: String) {
    val staleModelName = activeModelName ?: return
    if (staleModelName == selectedModelName) {
      return
    }

    serverModelAccess.cleanupModelByName(
      name = staleModelName,
      preferredTaskId = BuiltInTaskId.LLM_CHAT,
    )
    activeModelName = null
  }

  private suspend fun runSingleTextInference(model: Model, transcript: String): String {
    return suspendCancellableCoroutine { continuation ->
      val finished = AtomicBoolean(false)
      val responseBuilder = StringBuilder()

      continuation.invokeOnCancellation {
        finished.set(true)
        model.runtimeHelper.stopResponse(model)
      }

      try {
        model.runtimeHelper.runInference(
          model = model,
          input = transcript,
          resultListener = { partialResult, done, _ ->
            if (!finished.get()) {
              if (partialResult.isNotEmpty()) {
                responseBuilder.append(partialResult)
              }
              if (done && finished.compareAndSet(false, true)) {
                continuation.resume(responseBuilder.toString())
              }
            }
          },
          cleanUpListener = {},
          onError = { message ->
            if (finished.compareAndSet(false, true)) {
              continuation.resumeWithException(
                OpenAiGatewayException(
                  message = message.ifBlank { "LiteRT-LM inference failed." },
                )
              )
            }
          }
        )
      } catch (e: Exception) {
        if (finished.compareAndSet(false, true)) {
          continuation.resumeWithException(
            OpenAiGatewayException(
              message = e.message ?: "LiteRT-LM inference failed.",
              cause = e,
            )
          )
        }
      }
    }
  }

  private suspend fun runStreamingTextInference(
    model: Model,
    transcript: String,
    onDelta: (String) -> Boolean,
  ) {
    return suspendCancellableCoroutine { continuation ->
      val finished = AtomicBoolean(false)

      continuation.invokeOnCancellation {
        finished.set(true)
        model.runtimeHelper.stopResponse(model)
      }

      try {
        model.runtimeHelper.runInference(
          model = model,
          input = transcript,
          resultListener = { partialResult, done, _ ->
            if (!finished.get()) {
              if (partialResult.isNotEmpty()) {
                val keepGoing = onDelta(partialResult)
                if (!keepGoing && finished.compareAndSet(false, true)) {
                  model.runtimeHelper.stopResponse(model)
                  continuation.resume(Unit)
                }
              }
              if (done && finished.compareAndSet(false, true)) {
                continuation.resume(Unit)
              }
            }
          },
          cleanUpListener = {},
          onError = { message ->
            if (finished.compareAndSet(false, true)) {
              continuation.resumeWithException(
                OpenAiGatewayException(
                  message = message.ifBlank { "LiteRT-LM inference failed." },
                )
              )
            }
          }
        )
      } catch (e: Exception) {
        if (finished.compareAndSet(false, true)) {
          continuation.resumeWithException(
            OpenAiGatewayException(
              message = e.message ?: "LiteRT-LM inference failed.",
              cause = e,
            )
          )
        }
      }
    }
  }
}
