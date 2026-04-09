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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class OpenAiRequestSerializationGuard @Inject constructor() {
  private val mutex = Mutex()
  private var closed = false

  suspend fun <T> withSerializedAccess(block: suspend () -> T): T {
    return mutex.withLock {
      if (closed) {
        throw OpenAiGatewayException.invalidRequest(
          message = "The OpenAI inference gateway is closed.",
          code = "gateway_closed",
        )
      }
      block()
    }
  }

  suspend fun close(onClose: suspend () -> Unit = {}) {
    withContext(NonCancellable) {
      mutex.withLock {
        if (closed) {
          return@withLock
        }
        closed = true
        onClose()
      }
    }
  }

  suspend fun reset() {
    withContext(NonCancellable) {
      mutex.withLock {
        closed = false
      }
    }
  }
}
