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

class OpenAiGatewayException(
  message: String,
  val error: OpenAiErrorEnvelope? = null,
  cause: Throwable? = null,
) : Exception(message, cause) {
  companion object {
    fun invalidRequest(
      message: String,
      code: String,
      param: String? = null,
      cause: Throwable? = null,
    ): OpenAiGatewayException {
      return OpenAiGatewayException(
        message = message,
        error = OpenAiErrors.invalidRequest(message = message, code = code, param = param),
        cause = cause,
      )
    }
  }
}
