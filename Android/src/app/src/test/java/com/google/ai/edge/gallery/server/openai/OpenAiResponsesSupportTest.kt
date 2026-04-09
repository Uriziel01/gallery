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

class OpenAiResponsesSupportTest {
  @Test
  fun parseAndValidate_withOpenAiResponsesPayload_returnsChatRequest() {
    val requestBody =
      """
      {
        "model": "gemma-4-e2b-it",
        "stream": true,
        "instructions": "You are concise.",
        "input": [
          {
            "type": "message",
            "role": "user",
            "content": [
              { "type": "input_text", "text": "Hello" }
            ]
          },
          {
            "type": "message",
            "role": "assistant",
            "content": [
              { "type": "output_text", "text": "Hi!" }
            ]
          },
          {
            "type": "function_call_output",
            "call_id": "call_1",
            "output": "Tool result"
          },
          {
            "type": "message",
            "role": "user",
            "content": "What is the weather?"
          }
        ]
      }
      """
        .trimIndent()

    val result = OpenAiResponsesRequestValidator.parseAndValidate(requestBody)
    when (result) {
      is OpenAiValidationResult.Valid -> {
        assertEquals("gemma-4-e2b-it", result.value.model)
        assertTrue(result.value.stream)
        val chatRequest = OpenAiResponsesRequestValidator.toChatCompletionsRequest(result.value)
        assertEquals("gemma-4-e2b-it", chatRequest.model)
        assertEquals(5, chatRequest.messages.size)
        assertEquals("You are concise.", chatRequest.messages.first().content)
        assertEquals("What is the weather?", chatRequest.messages.last().content)
      }
      is OpenAiValidationResult.Invalid -> {
        throw AssertionError("Expected valid result, got error code=${result.error.error.code}")
      }
    }
  }
}

