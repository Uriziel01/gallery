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
import org.junit.Assert.assertNotNull
import org.junit.Test
import com.google.ai.edge.litertlm.Contents

class OpenAiTranscriptSerializerTest {
  @Test
  fun serialize_preservesFullConversationHistory() {
    val request =
      OpenAiChatCompletionsRequest(
        model = "gemma-4-e2b-it",
        messages =
          listOf(
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.SYSTEM,
              content = "You are concise.",
            ),
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.USER,
              content = "First turn",
            ),
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.ASSISTANT,
              content = "Acknowleged.",
            ),
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.USER,
              content = "Is this working?",
            ),
          ),
      )

    val prompt = OpenAiTranscriptSerializer.serialize(request)

    assertEquals(
      """
      User:
      First turn

      Assistant:
      Acknowleged.

      User:
      Is this working?
      """
        .trimIndent(),
      prompt.transcript,
    )
    assertEquals(Contents.of("You are concise.").toString(), prompt.systemInstruction?.toString())
  }

  @Test
  fun serialize_includesAssistantTurnsWithoutUserMessage() {
    val request =
      OpenAiChatCompletionsRequest(
        model = "gemma-4-e2b-it",
        messages =
          listOf(
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.SYSTEM,
              content = "You are concise.",
            ),
            OpenAiChatCompletionRequestMessage(
              role = OpenAiChatMessageRole.ASSISTANT,
              content = "Hello.",
            ),
          ),
      )

    val prompt = OpenAiTranscriptSerializer.serialize(request)

    assertEquals(
      """
      Assistant:
      Hello.
      """
        .trimIndent(),
      prompt.transcript,
    )
    assertNotNull(prompt.systemInstruction)
    assertEquals(Contents.of("You are concise.").toString(), prompt.systemInstruction?.toString())
  }
}
