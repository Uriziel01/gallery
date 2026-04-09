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

package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.DataStoreRepository
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedServerConfig(
  val enabled: Boolean,
  val port: Int,
  val bearerToken: String,
  val selectedModelName: String?,
)

@Singleton
class ServerConfigStore
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
) {
  fun readConfig(): PersistedServerConfig {
    val enabled = readBoolean(CONFIG_KEY_ENABLED, defaultValue = false)
    val port = readInt(CONFIG_KEY_PORT, defaultValue = DEFAULT_PORT)
    val bearerToken = dataStoreRepository.readSecret(CONFIG_KEY_BEARER_TOKEN).orEmpty()
    val selectedModelName = dataStoreRepository.readSecret(CONFIG_KEY_SELECTED_MODEL_NAME)
    return PersistedServerConfig(
      enabled = enabled,
      port = normalizePort(port),
      bearerToken = bearerToken,
      selectedModelName = selectedModelName?.takeIf { it.isNotBlank() },
    )
  }

  fun isEnabled(): Boolean = readConfig().enabled

  fun setEnabled(enabled: Boolean) {
    dataStoreRepository.saveSecret(CONFIG_KEY_ENABLED, enabled.toString())
  }

  fun getPort(): Int = readConfig().port

  fun setPort(port: Int) {
    val normalizedPort = normalizePort(port)
    dataStoreRepository.saveSecret(CONFIG_KEY_PORT, normalizedPort.toString())
  }

  fun getBearerToken(): String = readConfig().bearerToken

  fun setBearerToken(token: String) {
    dataStoreRepository.saveSecret(CONFIG_KEY_BEARER_TOKEN, token.trim())
  }

  fun getSelectedModelName(): String? = readConfig().selectedModelName

  fun setSelectedModelName(name: String?) {
    val normalizedName = name?.trim().orEmpty()
    if (normalizedName.isEmpty()) {
      dataStoreRepository.deleteSecret(CONFIG_KEY_SELECTED_MODEL_NAME)
    } else {
      dataStoreRepository.saveSecret(CONFIG_KEY_SELECTED_MODEL_NAME, normalizedName)
    }
  }

  private fun normalizePort(port: Int): Int {
    return if (port in MIN_PORT..MAX_PORT) port else DEFAULT_PORT
  }

  private fun readBoolean(key: String, defaultValue: Boolean): Boolean {
    return when (dataStoreRepository.readSecret(key)?.trim()?.lowercase()) {
      "true" -> true
      "false" -> false
      else -> defaultValue
    }
  }

  private fun readInt(key: String, defaultValue: Int): Int {
    return dataStoreRepository.readSecret(key)?.trim()?.toIntOrNull() ?: defaultValue
  }

  private companion object {
    const val CONFIG_KEY_ENABLED = "openai_server_enabled"
    const val CONFIG_KEY_PORT = "openai_server_port"
    const val CONFIG_KEY_BEARER_TOKEN = "openai_server_bearer_token"
    const val CONFIG_KEY_SELECTED_MODEL_NAME = "openai_server_selected_model_name"
    const val DEFAULT_PORT = 8080
    const val MIN_PORT = 1
    const val MAX_PORT = 65535
  }
}
