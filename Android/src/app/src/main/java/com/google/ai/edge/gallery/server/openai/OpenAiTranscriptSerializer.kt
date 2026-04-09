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

data class OpenAiLiteRtLmPrompt(
  val systemInstruction: Contents?,
  val transcript: String,
)

object OpenAiTranscriptSerializer {
  fun serialize(request: OpenAiChatCompletionsRequest): OpenAiLiteRtLmPrompt {
    val systemPrompt =
      request.messages
        .filter { it.role == OpenAiChatMessageRole.SYSTEM || it.role == OpenAiChatMessageRole.DEVELOPER }
        .mapNotNull { it.content?.takeIf(String::isNotBlank) }
        .joinToString(separator = "\n\n")
        .takeIf { it.isNotBlank() }

    val transcript =
      request.messages
        .asSequence()
        .filter { it.role != OpenAiChatMessageRole.SYSTEM && it.role != OpenAiChatMessageRole.DEVELOPER }
        .map { message ->
          val roleLabel = message.role.toWireValue().replaceFirstChar(Char::uppercaseChar)
          val content = message.content?.takeIf(String::isNotBlank)
          if (content == null) {
            "$roleLabel:"
          } else {
            "$roleLabel:\n$content"
          }
        }
        .joinToString(separator = "\n\n")

    return OpenAiLiteRtLmPrompt(
      systemInstruction = systemPrompt?.let(Contents::of),
      transcript = transcript,
    )
  }
}
