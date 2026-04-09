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
import org.junit.Assert.fail
import org.junit.Test

class OpenAiValidationTest {
  @Test
  fun parseAndValidate_withValidRequest_returnsValid() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "system", "content": "You are concise." },
          { "role": "user", "content": "Say hi." }
        ],
        "stream": false
      }
      """
        .trimIndent()

    val result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody)
    when (result) {
      is OpenAiValidationResult.Valid -> {
        assertEquals("gemma-3n", result.value.model)
        assertEquals(2, result.value.messages.size)
        assertEquals(false, result.value.stream)
      }
      is OpenAiValidationResult.Invalid -> {
        fail("Expected valid result, got error code=${result.error.error.code}")
      }
    }
  }

  @Test
  fun parseAndValidate_withUnknownTopLevelFields_returnsValid() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "user", "content": "Hi" }
        ],
        "temperature": 0.2,
        "max_tokens": 64
      }
      """
        .trimIndent()

    when (val result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody)) {
      is OpenAiValidationResult.Valid -> Unit
      is OpenAiValidationResult.Invalid -> {
        fail("Expected valid result, got error code=${result.error.error.code}")
      }
    }
  }

  @Test
  fun parseAndValidate_withMissingModel_returnsMissingRequiredFieldError() {
    val requestBody =
      """
      {
        "messages": [
          { "role": "user", "content": "Hi" }
        ]
      }
      """
        .trimIndent()

    assertInvalid(
      result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody),
      expectedCode = "missing_required_field",
      expectedParam = "model",
    )
  }

  @Test
  fun parseAndValidate_withUnsupportedRole_returnsUnsupportedRoleError() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "critic", "content": "Hi" }
        ]
      }
      """
        .trimIndent()

    assertInvalid(
      result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody),
      expectedCode = "unsupported_role",
      expectedParam = "messages[0].role",
    )
  }

  @Test
  fun parseAndValidate_withNullableAssistantAndToolMessages_returnsValid() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "system", "content": "You are concise." },
          { "role": "assistant", "content": null, "tool_calls": [] },
          { "role": "tool", "tool_call_id": "call_1", "content": null },
          { "role": "user", "content": "Hi" }
        ]
      }
      """
        .trimIndent()

    when (val result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody)) {
      is OpenAiValidationResult.Valid -> Unit
      is OpenAiValidationResult.Invalid -> {
        fail("Expected valid result, got error code=${result.error.error.code}")
      }
    }
  }

  @Test
  fun parseAndValidate_withStructuredTextContentArray_returnsValid() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "system", "content": [{ "type": "text", "text": "You are concise." }] },
          { "role": "user", "content": [{ "type": "text", "text": "Say hi." }] }
        ]
      }
      """
        .trimIndent()

    when (val result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody)) {
      is OpenAiValidationResult.Valid -> {
        assertEquals("You are concise.", result.value.messages[0].content)
        assertEquals("Say hi.", result.value.messages[1].content)
      }
      is OpenAiValidationResult.Invalid -> {
        fail("Expected valid result, got error code=${result.error.error.code}")
      }
    }
  }

  @Test
  fun parseAndValidate_withNonBooleanStream_returnsInvalidStreamError() {
    val requestBody =
      """
      {
        "model": "gemma-3n",
        "messages": [
          { "role": "user", "content": "Hi" }
        ],
        "stream": "yes"
      }
      """
        .trimIndent()

    assertInvalid(
      result = OpenAiChatCompletionsRequestValidator.parseAndValidate(requestBody),
      expectedCode = "invalid_stream",
      expectedParam = "stream",
    )
  }

  private fun assertInvalid(
    result: OpenAiValidationResult<*>,
    expectedCode: String,
    expectedParam: String? = null,
  ) {
    when (result) {
      is OpenAiValidationResult.Invalid -> {
        assertEquals(expectedCode, result.error.error.code)
        assertEquals(expectedParam, result.error.error.param)
      }
      is OpenAiValidationResult.Valid<*> -> {
        fail("Expected invalid result, got valid payload")
      }
    }
  }
}
