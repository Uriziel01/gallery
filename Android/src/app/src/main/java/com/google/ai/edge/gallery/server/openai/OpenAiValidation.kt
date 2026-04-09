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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class OpenAiValidationResult<out T> {
  data class Valid<T>(val value: T) : OpenAiValidationResult<T>()

  data class Invalid(val error: OpenAiErrorEnvelope) : OpenAiValidationResult<Nothing>()
}

object OpenAiChatCompletionsRequestValidator {
  private val supportedTopLevelFields =
    setOf(
      "model",
      "messages",
      "stream",
      "temperature",
      "top_p",
      "max_tokens",
      "max_completion_tokens",
      "stop",
      "presence_penalty",
      "frequency_penalty",
      "n",
      "user",
      "response_format",
      "tools",
      "tool_choice",
      "parallel_tool_calls",
      "seed",
      "logit_bias",
      "modalities",
      "stream_options",
      "service_tier",
      "reasoning_effort",
    )

  private val supportedMessageFields =
    setOf(
      "role",
      "content",
      "name",
      "tool_calls",
      "tool_call_id",
      "function_call",
      "refusal",
      "audio",
    )

  fun parseAndValidate(requestBody: String): OpenAiValidationResult<OpenAiChatCompletionsRequest> {
    val root =
      try {
        OpenAiJson.parse(requestBody).jsonObject
      } catch (_: IllegalArgumentException) {
        return invalid(
          message = "Request body must be a JSON object.",
          code = "invalid_json",
        )
      } catch (_: SerializationException) {
        return invalid(
          message = "Request body must be valid JSON.",
          code = "invalid_json",
        )
      }

    validateTopLevelFields(root)?.let { return it }
    validateModel(root["model"])?.let { return it }
    validateMessages(root["messages"])?.let { return it }
    validateStream(root["stream"])?.let { return it }

    val normalizedRequestBody = normalizeRequestBody(root)
    val request =
      try {
        OpenAiJson.decode<OpenAiChatCompletionsRequest>(normalizedRequestBody)
      } catch (_: IllegalArgumentException) {
        return invalid(
          message = "Request body does not match the v1 chat completions contract.",
          code = "invalid_request",
        )
      } catch (_: SerializationException) {
        return invalid(
          message = "Request body does not match the v1 chat completions contract.",
          code = "invalid_request",
        )
      }

    return validate(request)
  }

  fun validate(request: OpenAiChatCompletionsRequest): OpenAiValidationResult<OpenAiChatCompletionsRequest> {
    if (request.model.isBlank()) {
      return invalid(
        message = "Field `model` must be a non-empty string.",
        code = "invalid_model",
        param = "model",
      )
    }

    if (request.messages.isEmpty()) {
      return invalid(
        message = "Field `messages` must be a non-empty array.",
        code = "invalid_messages",
        param = "messages",
      )
    }

    return OpenAiValidationResult.Valid(request)
  }

  private fun validateTopLevelFields(root: JsonObject): OpenAiValidationResult.Invalid? {
    val unsupportedFields = root.keys.minus(supportedTopLevelFields).sorted()
    if (unsupportedFields.isNotEmpty()) { /* ignored intentionally */ }

    if ("model" !in root) {
      return invalid(
        message = "Missing required field: `model`.",
        code = "missing_required_field",
        param = "model",
      )
    }

    if ("messages" !in root) {
      return invalid(
        message = "Missing required field: `messages`.",
        code = "missing_required_field",
        param = "messages",
      )
    }

    return null
  }

  private fun validateModel(element: JsonElement?): OpenAiValidationResult.Invalid? {
    val value = element.asStringOrNull()
    if (value.isNullOrBlank()) {
      return invalid(
        message = "Field `model` must be a non-empty string.",
        code = "invalid_model",
        param = "model",
      )
    }
    return null
  }

  private fun validateMessages(element: JsonElement?): OpenAiValidationResult.Invalid? {
    val array =
      element as? JsonArray
        ?: return invalid(
          message = "Field `messages` must be a non-empty array.",
          code = "invalid_messages",
          param = "messages",
        )

    if (array.isEmpty()) {
      return invalid(
        message = "Field `messages` must be a non-empty array.",
        code = "invalid_messages",
        param = "messages",
      )
    }

    array.forEachIndexed { index, item ->
      val message = item as? JsonObject
      if (message == null) {
        return invalid(
          message = "Each item in `messages` must be an object.",
          code = "invalid_message",
          param = "messages[$index]",
        )
      }

      val unsupportedFields = message.keys.minus(supportedMessageFields).sorted()
      if (unsupportedFields.isNotEmpty()) { /* ignored intentionally */ }

      if ("role" !in message) {
        return invalid(
          message = "Missing required field: `messages[$index].role`.",
          code = "missing_required_field",
          param = "messages[$index].role",
        )
      }

      val role = message["role"].asStringOrNull()
      if (role == null) {
        return invalid(
          message = "Field `messages[$index].role` must be a string.",
          code = "invalid_role",
          param = "messages[$index].role",
        )
      }

      val parsedRole = OpenAiChatMessageRole.fromWireValue(role)
      if (parsedRole == null) {
        return invalid(
          message = "Unsupported role at `messages[$index].role`: `$role`.",
          code = "unsupported_role",
          param = "messages[$index].role",
        )
      }

      val contentValue = extractContentText(message["content"])
      val nullableContentRole =
        parsedRole == OpenAiChatMessageRole.ASSISTANT || parsedRole == OpenAiChatMessageRole.TOOL
      if (contentValue.isNullOrBlank() && !nullableContentRole) {
        return invalid(
          message = "Field `messages[$index].content` must be a string.",
          code = "invalid_content",
          param = "messages[$index].content",
        )
      }
    }

    return null
  }

  private fun normalizeRequestBody(root: JsonObject): String {
    val messages = root["messages"] as? JsonArray ?: JsonArray(emptyList())
    val normalizedMessages =
      buildJsonArray {
        messages.forEach { item ->
          val message = item as? JsonObject ?: return@forEach
          add(normalizeMessage(message))
        }
      }
    val normalizedRoot =
      buildJsonObject {
        root.forEach { (key, value) ->
          if (key == "messages") {
            put("messages", normalizedMessages)
          } else {
            put(key, value)
          }
        }
      }
    return OpenAiJson.encode(normalizedRoot)
  }

  private fun normalizeMessage(message: JsonObject): JsonObject {
    val role = message["role"].asStringOrNull()?.let(OpenAiChatMessageRole::fromWireValue)
    val contentText = extractContentText(message["content"])
    return buildJsonObject {
      message.forEach { (key, value) ->
        when (key) {
          "content" -> {
            when {
              !contentText.isNullOrBlank() -> put("content", JsonPrimitive(contentText))
              role == OpenAiChatMessageRole.ASSISTANT || role == OpenAiChatMessageRole.TOOL -> Unit
              else -> put("content", JsonPrimitive(""))
            }
          }
          else -> put(key, value)
        }
      }
      if ("content" !in message && contentText != null) {
        put("content", JsonPrimitive(contentText))
      }
      if ("content" !in message && (role == OpenAiChatMessageRole.ASSISTANT || role == OpenAiChatMessageRole.TOOL)) {
        put("content", JsonNull)
      }
    }
  }

  private fun extractContentText(element: JsonElement?): String? {
    return when (element) {
      null -> null
      is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)
      is JsonArray ->
        element
          .mapNotNull { part ->
            when (part) {
              is JsonPrimitive -> part.contentOrNull?.takeIf(String::isNotBlank)
              is JsonObject -> extractTextFromContentObject(part)
              else -> null
            }
          }
          .joinToString(separator = "\n")
          .takeIf { it.isNotBlank() }
      is JsonObject -> extractTextFromContentObject(element)
      else -> null
    }
  }

  private fun extractTextFromContentObject(content: JsonObject): String? {
    val type = content["type"].asStringOrNull()
    val text =
      when (type) {
        "input_text",
        "output_text",
        "text",
        null -> content["text"].asStringOrNull()
        else -> null
      }
    return text?.takeIf(String::isNotBlank)
      ?: content["content"].asStringOrNull()?.takeIf(String::isNotBlank)
      ?: content["output"].asStringOrNull()?.takeIf(String::isNotBlank)
  }

  private fun validateStream(element: JsonElement?): OpenAiValidationResult.Invalid? {
    if (element == null) {
      return null
    }
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.booleanOrNull == null) {
      return invalid(
        message = "Field `stream` must be a boolean.",
        code = "invalid_stream",
        param = "stream",
      )
    }
    return null
  }

  private fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    val encoded = primitive.toString()
    if (encoded.length < 2 || encoded.first() != '"' || encoded.last() != '"') {
      return null
    }
    return primitive.contentOrNull
  }

  private fun invalid(
    message: String,
    code: String,
    param: String? = null,
  ): OpenAiValidationResult.Invalid {
    return OpenAiValidationResult.Invalid(
      error = OpenAiErrors.invalidRequest(message = message, code = code, param = param)
    )
  }
}
