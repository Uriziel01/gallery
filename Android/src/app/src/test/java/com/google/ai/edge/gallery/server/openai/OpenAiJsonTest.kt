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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiJsonTest {
  @Test
  fun chatCompletionRequest_roundTrip_preservesFields() {
    val original =
      OpenAiChatCompletionsRequest(
        model = "gemma-3n",
        messages =
          listOf(
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.SYSTEM,
              content = "You are concise.",
            ),
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.USER,
              content = "Hello from PC",
            ),
          ),
        stream = true,
      )

    val encoded = OpenAiJson.encode(original)
    val decoded = OpenAiJson.decode<OpenAiChatCompletionsRequest>(encoded)

    assertEquals(original, decoded)
  }

  @Test
  fun responseAndChunkParsers_decodeWireFields() {
    val responseJson =
      """
      {
        "id": "chatcmpl-1",
        "object": "chat.completion",
        "created": 1735689600,
        "model": "gemma-3n",
        "choices": [
          {
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "Hi!"
            },
            "finish_reason": "stop"
          }
        ]
      }
      """
        .trimIndent()
    val chunkJson =
      """
      {
        "id": "chatcmpl-1",
        "object": "chat.completion.chunk",
        "created": 1735689600,
        "model": "gemma-3n",
        "choices": [
          {
            "index": 0,
            "delta": {
              "role": "assistant",
              "content": "H"
            },
            "finish_reason": null
          }
        ]
      }
      """
        .trimIndent()

    val response = OpenAiParsers.parseChatCompletionsResponse(responseJson)
    val chunk = OpenAiParsers.parseChatCompletionChunk(chunkJson)

    assertEquals("chatcmpl-1", response.id)
    assertEquals("stop", response.choices.first().finishReason)
    assertEquals(OpenAiChatMessageRole.ASSISTANT, response.choices.first().message.role)

    assertEquals("chatcmpl-1", chunk.id)
    assertEquals(OpenAiChatMessageRole.ASSISTANT, chunk.choices.first().delta.role)
    assertEquals("H", chunk.choices.first().delta.content)
  }

  @Test
  fun modelAndErrorParsers_decodeOpenAiPayloads() {
    val modelsJson =
      """
      {
        "object": "list",
        "data": [
          {
            "id": "gemma-3n",
            "object": "model",
            "created": 0,
            "owned_by": "google-ai-edge-gallery"
          }
        ]
      }
      """
        .trimIndent()
    val errorJson =
      """
      {
        "error": {
          "message": "Invalid bearer token.",
          "type": "invalid_request_error",
          "param": null,
          "code": "invalid_api_key"
        }
      }
      """
        .trimIndent()

    val models = OpenAiParsers.parseModelListResponse(modelsJson)
    val error = OpenAiParsers.parseErrorEnvelope(errorJson)

    assertEquals("list", models.`object`)
    assertEquals("google-ai-edge-gallery", models.data.first().ownedBy)
    assertTrue(models.data.isNotEmpty())

    assertEquals("invalid_api_key", error.error.code)
    assertEquals("invalid_request_error", error.error.type)
  }
}
