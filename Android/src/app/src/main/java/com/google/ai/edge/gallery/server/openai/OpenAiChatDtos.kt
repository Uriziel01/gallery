/*
 * Copyright 2025 Google LLC
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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class OpenAiChatCompletionsRequest(
  val model: String,
  val messages: List<OpenAiChatCompletionRequestMessage>,
  val stream: Boolean = false,
)

@Serializable
data class OpenAiChatCompletionRequestMessage(
  val role: OpenAiChatMessageRole,
  val content: String? = null,
)

@Serializable
data class OpenAiChatCompletionsResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  @SerialName("system_fingerprint") val systemFingerprint: String? = null,
  val choices: List<OpenAiChatCompletionChoice>,
  val usage: OpenAiChatCompletionUsage? = null,
)

@Serializable
data class OpenAiChatCompletionChoice(
  val index: Int,
  val message: OpenAiChatCompletionResponseMessage,
  val logprobs: JsonElement = JsonNull,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiChatCompletionResponseMessage(
  val role: OpenAiChatMessageRole,
  val content: String,
)

@Serializable
data class OpenAiChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  @SerialName("system_fingerprint") val systemFingerprint: String? = null,
  val choices: List<OpenAiChatCompletionChunkChoice>,
)

@Serializable
data class OpenAiChatCompletionChunkChoice(
  val index: Int,
  val logprobs: JsonElement = JsonNull,
  val delta: OpenAiChatCompletionChunkDelta = OpenAiChatCompletionChunkDelta(),
  @SerialName("finish_reason") val finishReason: JsonElement = JsonNull,
)

@Serializable
data class OpenAiChatCompletionChunkDelta(
  val role: OpenAiChatMessageRole? = null,
  val content: String? = null,
)

@Serializable
data class OpenAiChatCompletionUsage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
)
