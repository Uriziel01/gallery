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

import com.google.ai.edge.litertlm.Contents
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class OpenAiResponsesRequest(
  val model: String,
  val input: JsonElement? = null,
  val stream: Boolean = false,
  val instructions: JsonElement? = null,
)

data class OpenAiResponsesPrompt(
  val systemInstruction: Contents?,
  val transcript: String,
)

object OpenAiResponsesRequestValidator {
  fun parseAndValidate(requestBody: String): OpenAiValidationResult<OpenAiResponsesRequest> {
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

    val model = root["model"].asStringOrNull()
    if (model.isNullOrBlank()) {
      return invalid(
        message = "Field `model` must be a non-empty string.",
        code = "invalid_model",
        param = "model",
      )
    }

    if (!root.containsKey("input")) {
      return invalid(
        message = "Missing required field: `input`.",
        code = "missing_required_field",
        param = "input",
      )
    }

    val input = root["input"]
    if (input == null || input is JsonPrimitive && input.contentOrNull == null && input.toString() == "null") {
      return invalid(
        message = "Field `input` must not be null.",
        code = "invalid_input",
        param = "input",
      )
    }

    val request =
      try {
        OpenAiJson.decode<OpenAiResponsesRequest>(requestBody)
      } catch (_: IllegalArgumentException) {
        return invalid(
          message = "Request body does not match the v1 responses contract.",
          code = "invalid_request",
        )
      } catch (_: SerializationException) {
        return invalid(
          message = "Request body does not match the v1 responses contract.",
          code = "invalid_request",
        )
      }

    return OpenAiValidationResult.Valid(request)
  }

  fun toChatCompletionsRequest(request: OpenAiResponsesRequest): OpenAiChatCompletionsRequest {
    val instructionsText = extractInstructionText(request.instructions)
    val chatMessages = mutableListOf<OpenAiChatCompletionRequestMessage>()

    if (!instructionsText.isNullOrBlank()) {
      chatMessages +=
        OpenAiChatCompletionRequestMessage(
          role = OpenAiChatMessageRole.SYSTEM,
          content = instructionsText,
        )
    }

    chatMessages += extractMessagesFromInput(request.input)

    return OpenAiChatCompletionsRequest(
      model = request.model,
      messages = chatMessages,
      stream = request.stream,
    )
  }

  private fun extractMessagesFromInput(input: JsonElement?): List<OpenAiChatCompletionRequestMessage> {
    if (input == null) {
      return emptyList()
    }

    return when (input) {
      is JsonPrimitive -> {
        val text = input.contentOrNull?.takeIf(String::isNotBlank)
        if (text == null) {
          emptyList()
        } else {
          listOf(
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.USER,
              content = text,
            )
          )
        }
      }
      is JsonArray -> {
        input.mapNotNull { item ->
          val objectItem = item as? JsonObject ?: return@mapNotNull null
          val role = objectItem["role"].asStringOrNull()
          val type = objectItem["type"].asStringOrNull()
          val contentText = extractMessageContentText(objectItem)
          when {
            role != null -> {
              val parsedRole = OpenAiChatMessageRole.fromWireValue(role) ?: return@mapNotNull null
              OpenAiChatCompletionRequestMessage(
                role = parsedRole,
                content =
                  contentText?.takeIf(String::isNotBlank)
                    ?: if (parsedRole == OpenAiChatMessageRole.ASSISTANT ||
                      parsedRole == OpenAiChatMessageRole.TOOL
                    ) null else "",
              )
            }
            type == "function_call_output" -> {
              OpenAiChatCompletionRequestMessage(
                role = OpenAiChatMessageRole.TOOL,
                content =
                  objectItem["output"].asStringOrNull()
                    ?.takeIf(String::isNotBlank),
              )
            }
            else -> null
          }
        }
      }
      else -> emptyList()
    }
  }

  private fun extractInstructionText(element: JsonElement?): String? {
    if (element == null) {
      return null
    }

    return when (element) {
      is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)
      is JsonArray -> {
        element.mapNotNull { part ->
          when (part) {
            is JsonPrimitive -> part.contentOrNull?.takeIf(String::isNotBlank)
            is JsonObject -> extractMessageContentText(part)
            else -> null
          }
        }.joinToString(separator = "\n\n").takeIf { it.isNotBlank() }
      }
      is JsonObject -> extractMessageContentText(element)
      else -> null
    }
  }

  private fun extractMessageContentText(message: JsonObject): String? {
    val content = message["content"]
    if (content is JsonPrimitive) {
      return content.contentOrNull?.takeIf(String::isNotBlank)
    }
    if (content is JsonArray) {
      val parts =
        content.mapNotNull { part ->
          val partObject = part as? JsonObject ?: return@mapNotNull null
          val partType = partObject["type"].asStringOrNull()
          when (partType) {
            "input_text",
            "output_text",
            "text" -> partObject["text"].asStringOrNull()?.takeIf(String::isNotBlank)
            else -> null
          }
        }
      return parts.joinToString(separator = "\n").takeIf { it.isNotBlank() }
    }
    return message["output"].asStringOrNull()?.takeIf(String::isNotBlank)
  }

  private fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
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

