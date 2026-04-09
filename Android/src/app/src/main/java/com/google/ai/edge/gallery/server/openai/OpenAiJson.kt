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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object OpenAiJson {
  val codec =
    Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      encodeDefaults = true
      isLenient = false
    }

  inline fun <reified T> decode(text: String): T {
    return codec.decodeFromString(text)
  }

  inline fun <reified T> encode(value: T): String {
    return codec.encodeToString(value)
  }

  fun parse(text: String): JsonElement {
    return codec.parseToJsonElement(text)
  }
}

object OpenAiParsers {
  fun parseChatCompletionsResponse(text: String): OpenAiChatCompletionsResponse {
    return OpenAiJson.decode(text)
  }

  fun parseChatCompletionChunk(text: String): OpenAiChatCompletionChunk {
    return OpenAiJson.decode(text)
  }

  fun parseModelListResponse(text: String): OpenAiModelListResponse {
    return OpenAiJson.decode(text)
  }

  fun parseErrorEnvelope(text: String): OpenAiErrorEnvelope {
    return OpenAiJson.decode(text)
  }
}
