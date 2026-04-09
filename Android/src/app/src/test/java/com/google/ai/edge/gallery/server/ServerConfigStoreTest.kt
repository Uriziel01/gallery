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
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.Cutout
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.proto.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigStoreTest {
  @Test
  fun readConfig_defaults_whenStorageIsEmpty() {
    val store = ServerConfigStore(InMemoryDataStoreRepository())

    val config = store.readConfig()

    assertFalse(config.enabled)
    assertEquals(8080, config.port)
    assertEquals("", config.bearerToken)
    assertNull(config.selectedModelName)
  }

  @Test
  fun readConfig_returnsPersistedValues() {
    val store = ServerConfigStore(InMemoryDataStoreRepository())

    store.setEnabled(true)
    store.setPort(9001)
    store.setBearerToken("  secret-token  ")
    store.setSelectedModelName("  gemma-3n-e4b-it-int4  ")

    val config = store.readConfig()

    assertTrue(config.enabled)
    assertEquals(9001, config.port)
    assertEquals("secret-token", config.bearerToken)
    assertEquals("gemma-3n-e4b-it-int4", config.selectedModelName)
  }

  @Test
  fun setPort_normalizesOutOfRangePortsToDefault() {
    val store = ServerConfigStore(InMemoryDataStoreRepository())

    store.setPort(0)
    assertEquals(8080, store.getPort())

    store.setPort(70000)
    assertEquals(8080, store.getPort())
  }

  @Test
  fun setSelectedModelName_blankValueClearsSelection() {
    val store = ServerConfigStore(InMemoryDataStoreRepository())

    store.setSelectedModelName("gemma-3n")
    assertEquals("gemma-3n", store.getSelectedModelName())

    store.setSelectedModelName("   ")
    assertNull(store.getSelectedModelName())
  }

  @Test
  fun readConfig_fallsBackForInvalidPersistedBooleanAndPort() {
    val repository = InMemoryDataStoreRepository()
    repository.saveSecret("openai_server_enabled", "not-a-boolean")
    repository.saveSecret("openai_server_port", "-12")

    val store = ServerConfigStore(repository)
    val config = store.readConfig()

    assertFalse(config.enabled)
    assertEquals(8080, config.port)
  }
}

private class InMemoryDataStoreRepository : DataStoreRepository {
  private val secrets = mutableMapOf<String, String>()

  override fun saveSecret(key: String, value: String) {
    secrets[key] = value
  }

  override fun readSecret(key: String): String? = secrets[key]

  override fun deleteSecret(key: String) {
    secrets.remove(key)
  }

  override fun saveTextInputHistory(history: List<String>) = Unit

  override fun readTextInputHistory(): List<String> = emptyList()

  override fun saveTheme(theme: Theme) = Unit

  override fun readTheme(): Theme = Theme.THEME_UNSPECIFIED

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) = Unit

  override fun clearAccessTokenData() = Unit

  override fun readAccessTokenData(): AccessTokenData? = null

  override fun saveImportedModels(importedModels: List<ImportedModel>) = Unit

  override fun readImportedModels(): List<ImportedModel> = emptyList()

  override fun isTosAccepted(): Boolean = false

  override fun acceptTos() = Unit

  override fun isGemmaTermsOfUseAccepted(): Boolean = false

  override fun acceptGemmaTermsOfUse() = Unit

  override fun getHasRunTinyGarden(): Boolean = false

  override fun setHasRunTinyGarden(hasRun: Boolean) = Unit

  override fun addCutout(cutout: Cutout) = Unit

  override fun getAllCutouts(): List<Cutout> = emptyList()

  override fun setCutout(newCutout: Cutout) = Unit

  override fun setCutouts(cutouts: List<Cutout>) = Unit

  override fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) = Unit

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean = false

  override fun addBenchmarkResult(result: BenchmarkResult) = Unit

  override fun getAllBenchmarkResults(): List<BenchmarkResult> = emptyList()

  override fun deleteBenchmarkResult(index: Int) = Unit

  override fun addSkill(skill: Skill) = Unit

  override fun setSkills(skills: List<Skill>) = Unit

  override fun setSkillSelected(skill: Skill, selected: Boolean) = Unit

  override fun setAllSkillsSelected(selected: Boolean) = Unit

  override fun getAllSkills(): List<Skill> = emptyList()

  override fun deleteSkill(name: String) = Unit

  override suspend fun deleteSkills(names: Set<String>) = Unit

  override fun addViewedPromoId(promoId: String) = Unit

  override fun removeViewedPromoId(promoId: String) = Unit

  override fun hasViewedPromo(promoId: String): Boolean = false
}
