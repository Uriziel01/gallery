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

@Serializable
enum class OpenAiChatMessageRole {
  @SerialName("system") SYSTEM,
  @SerialName("developer") DEVELOPER,
  @SerialName("user") USER,
  @SerialName("assistant") ASSISTANT,
  @SerialName("tool") TOOL,
  ;

  fun toWireValue(): String {
    return when (this) {
      SYSTEM -> "system"
      DEVELOPER -> "developer"
      USER -> "user"
      ASSISTANT -> "assistant"
      TOOL -> "tool"
    }
  }

  companion object {
    fun fromWireValue(value: String): OpenAiChatMessageRole? {
      return when (value) {
        "system" -> SYSTEM
        "developer" -> DEVELOPER
        "user" -> USER
        "assistant" -> ASSISTANT
        "tool" -> TOOL
        else -> null
      }
    }
  }
}
