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

import kotlinx.serialization.Serializable

@Serializable
data class OpenAiErrorEnvelope(val error: OpenAiErrorBody)

@Serializable
data class OpenAiErrorBody(
  val message: String,
  val type: String,
  val param: String? = null,
  val code: String? = null,
)

object OpenAiErrors {
  const val INVALID_REQUEST_TYPE = "invalid_request_error"

  fun invalidRequest(
    message: String,
    code: String,
    param: String? = null,
  ): OpenAiErrorEnvelope {
    return OpenAiErrorEnvelope(
      error = OpenAiErrorBody(message = message, type = INVALID_REQUEST_TYPE, param = param, code = code)
    )
  }
}
