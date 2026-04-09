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

import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenAiRequestSerializationGuardTest {
  @Test
  fun withSerializedAccess_runsRequestsSequentially() = runBlocking {
    val guard = OpenAiRequestSerializationGuard()
    val events = mutableListOf<String>()

    val firstJob =
      launch {
        guard.withSerializedAccess {
          events.add("first-start")
          delay(120)
          events.add("first-end")
        }
      }

    delay(20)

    val secondJob =
      launch {
        guard.withSerializedAccess { events.add("second") }
      }

    joinAll(firstJob, secondJob)

    assertEquals(listOf("first-start", "first-end", "second"), events)
  }

  @Test
  fun close_rejectsFutureRequests() = runBlocking {
    val guard = OpenAiRequestSerializationGuard()
    guard.close()

    var thrown: OpenAiGatewayException? = null
    try {
      guard.withSerializedAccess { /* no-op */ }
    } catch (e: OpenAiGatewayException) {
      thrown = e
    }

    assertNotNull(thrown)
    assertEquals("gateway_closed", thrown?.error?.error?.code)
  }
}
