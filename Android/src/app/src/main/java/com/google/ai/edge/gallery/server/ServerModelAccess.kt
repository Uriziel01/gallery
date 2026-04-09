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

package com.google.ai.edge.gallery.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "AGServerModelAccess"
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

private val DYNAMIC_MODEL_TASK_IDS =
  setOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

private val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

data class SelectedServerModel(val model: Model, val taskIds: List<String>)

@Singleton
class ServerModelAccess
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val dataStoreRepository: DataStoreRepository,
  private val selectedModelStore: SelectedModelStore,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
) {
  private val graphLock = Any()
  private val gson = Gson()

  @Volatile private var taskGraph: ServerTaskGraph? = null

  fun getSelectedModelName(): String? {
    return selectedModelStore.getSelectedModelName()
  }

  fun getTasks(): List<Task> {
    return getTaskGraph().tasks
  }

  fun refreshTaskGraph(): List<Task> {
    return refreshGraph().tasks
  }

  fun getTaskById(id: String): Task? {
    return getTaskGraph().tasksById[id]
  }

  fun getModelByName(name: String): Model? {
    return getTaskGraph().modelsByName[name]?.model
  }

  fun getTaskIdsForModel(name: String): List<String> {
    return getTaskGraph().modelsByName[name]?.taskIds ?: listOf()
  }

  fun getSelectedModel(): SelectedServerModel? {
    val selectedModelName = getSelectedModelName() ?: return null
    val bindings =
      getTaskGraph().modelsByName[selectedModelName]
        ?: refreshGraph().modelsByName[selectedModelName]
        ?: return null
    return SelectedServerModel(model = bindings.model, taskIds = bindings.taskIds)
  }

  suspend fun initializeSelectedModel(
    preferredTaskId: String? = null,
    force: Boolean = false,
  ): Model? {
    val selectedModelName = getSelectedModelName() ?: return null
    return initializeModelByName(
      name = selectedModelName,
      preferredTaskId = preferredTaskId,
      force = force,
    )
  }

  suspend fun initializeModelByName(
    name: String,
    preferredTaskId: String? = null,
    force: Boolean = false,
  ): Model {
    val binding = resolveBinding(name = name, preferredTaskId = preferredTaskId)
    initializeModel(binding = binding, force = force)
    return binding.model
  }

  suspend fun cleanupSelectedModel(preferredTaskId: String? = null) {
    val selectedModelName = getSelectedModelName() ?: return
    cleanupModelByName(name = selectedModelName, preferredTaskId = preferredTaskId)
  }

  suspend fun cleanupModelByName(name: String, preferredTaskId: String? = null) {
    cleanupModel(binding = resolveBinding(name = name, preferredTaskId = preferredTaskId))
  }

  private fun getTaskGraph(): ServerTaskGraph {
    taskGraph?.let { return it }
    return synchronized(graphLock) {
      taskGraph ?: buildTaskGraph(existingGraph = taskGraph).also { taskGraph = it }
    }
  }

  private fun refreshGraph(): ServerTaskGraph {
    return synchronized(graphLock) {
      buildTaskGraph(existingGraph = taskGraph).also { taskGraph = it }
    }
  }

  private fun buildTaskGraph(existingGraph: ServerTaskGraph?): ServerTaskGraph {
    val previousModels = existingGraph?.modelsByName?.mapValues { it.value.model } ?: mapOf()
    val taskHandlersById = linkedMapOf<String, CustomTask>()
    val tasksById = linkedMapOf<String, Task>()
    val allowlistModelsByName = linkedMapOf<String, Model>()

    for (customTask in getOrderedCustomTasks()) {
      val sourceTask = customTask.task
      val clonedTask = sourceTask.copy(models = mutableListOf())
      taskHandlersById[clonedTask.id] = customTask
      tasksById[clonedTask.id] = clonedTask

      if (sourceTask.id in DYNAMIC_MODEL_TASK_IDS) {
        continue
      }

      for (sourceModel in sourceTask.models) {
        val model = previousModels[sourceModel.name] ?: cloneModel(sourceModel)
        addModelToTask(task = clonedTask, model = model, taskId = clonedTask.id)
      }
    }

    readModelAllowlistFromDisk()?.models?.forEach { allowedModel ->
      if (allowedModel.disabled == true || isUnsupportedNpuOnlyModel(allowedModel)) {
        return@forEach
      }

      val model =
        previousModels[allowedModel.name]
          ?: allowedModel.toModel().also {
            it.preProcess()
          }
      allowlistModelsByName[model.name] = model

      for (taskId in allowedModel.taskTypes) {
        tasksById[taskId]?.let { task -> addModelToTask(task = task, model = model, taskId = taskId) }
      }
    }

    for (task in tasksById.values) {
      if (task.modelNames.isEmpty()) {
        continue
      }
      for (modelName in task.modelNames) {
        allowlistModelsByName[modelName]?.let { model ->
          addModelToTask(task = task, model = model, taskId = task.id)
        }
      }
    }

    for (importedModel in dataStoreRepository.readImportedModels()) {
      val model =
        previousModels[importedModel.fileName] ?: createModelFromImportedModelInfo(importedModel)
      addImportedModel(tasksById = tasksById, model = model)
    }

    for (task in tasksById.values) {
      moveBestModelToFront(task)
    }

    val modelsByName = linkedMapOf<String, ModelBindings>()
    val tasks = tasksById.values.toList()
    for (task in tasks) {
      val customTask = taskHandlersById[task.id] ?: continue
      for (model in task.models.distinctBy { it.name }) {
        val bindings = modelsByName[model.name]
        if (bindings == null) {
          modelsByName[model.name] =
            ModelBindings(
              model = model,
              bindings = mutableListOf(ModelBinding(task = task, customTask = customTask, model = model)),
            )
        } else {
          bindings.bindings.add(ModelBinding(task = task, customTask = customTask, model = model))
        }
      }
    }

    return ServerTaskGraph(tasks = tasks, tasksById = tasksById, modelsByName = modelsByName)
  }

  private fun getOrderedCustomTasks(): List<CustomTask> {
    return customTasks.sortedWith(
      compareBy<CustomTask>({ taskOrderIndex(it.task) }, { it.task.label.lowercase() }, { it.task.id })
    )
  }

  private fun taskOrderIndex(task: Task): Int {
    val llmIndex = PREDEFINED_LLM_TASK_ORDER.indexOf(task.id)
    return if (llmIndex >= 0) llmIndex else Int.MAX_VALUE
  }

  private fun addModelToTask(task: Task, model: Model, taskId: String) {
    if (task.models.any { it.name == model.name }) {
      return
    }

    if (
      taskId == BuiltInTaskId.LLM_TINY_GARDEN &&
        model.configs.none { it.key == ConfigKeys.RESET_CONVERSATION_TURN_COUNT }
    ) {
      model.configs = (model.configs + RESET_CONVERSATION_TURN_COUNT_CONFIG)
      model.preProcess()
    }

    task.models.add(model)
  }

  private fun addImportedModel(tasksById: Map<String, Task>, model: Model) {
    tasksById[BuiltInTaskId.LLM_CHAT]?.let {
      addModelToTask(task = it, model = model, taskId = it.id)
    }
    tasksById[BuiltInTaskId.LLM_PROMPT_LAB]?.let {
      addModelToTask(task = it, model = model, taskId = it.id)
    }
    tasksById[BuiltInTaskId.LLM_AGENT_CHAT]?.let {
      addModelToTask(task = it, model = model, taskId = it.id)
    }
    if (model.llmSupportImage) {
      tasksById[BuiltInTaskId.LLM_ASK_IMAGE]?.let {
        addModelToTask(task = it, model = model, taskId = it.id)
      }
    }
    if (model.llmSupportAudio) {
      tasksById[BuiltInTaskId.LLM_ASK_AUDIO]?.let {
        addModelToTask(task = it, model = model, taskId = it.id)
      }
    }
    if (model.llmSupportTinyGarden) {
      tasksById[BuiltInTaskId.LLM_TINY_GARDEN]?.let {
        addModelToTask(task = it, model = model, taskId = it.id)
      }
    }
    if (model.llmSupportMobileActions) {
      tasksById[BuiltInTaskId.LLM_MOBILE_ACTIONS]?.let {
        addModelToTask(task = it, model = model, taskId = it.id)
      }
    }
  }

  private fun moveBestModelToFront(task: Task) {
    val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) } ?: return
    task.models.remove(bestModel)
    task.models.add(0, bestModel)
  }

  private fun cloneModel(model: Model): Model {
    return model
      .copy(
        configs = model.configs.toList(),
        instance = null,
        initializing = false,
        cleanUpAfterInit = false,
        configValues = mapOf(),
        prevConfigValues = mapOf(),
        totalBytes = 0L,
        accessToken = null,
      )
      .also { it.preProcess() }
  }

  private fun resolveBinding(name: String, preferredTaskId: String?): ModelBinding {
    val bindings =
      getTaskGraph().modelsByName[name] ?: refreshGraph().modelsByName[name]
      ?: throw IllegalArgumentException("Model '$name' is not available in the server task graph.")
    return bindings.resolve(preferredTaskId = preferredTaskId)
  }

  private suspend fun initializeModel(binding: ModelBinding, force: Boolean) = coroutineScope {
    val model = binding.model
    if (!force && model.instance != null && !model.initializing) {
      return@coroutineScope
    }

    if (model.initializing) {
      model.cleanUpAfterInit = false
      return@coroutineScope
    }

    cleanupModel(binding = binding)

    model.initializing = true
    suspendCancellableCoroutine<Unit> { continuation ->
      binding.customTask.initializeModelFn(
        context = context,
        coroutineScope = this,
        model = model,
      ) { error ->
        model.initializing = false
        if (model.instance != null) {
          if (model.cleanUpAfterInit) {
            model.cleanUpAfterInit = false
            binding.customTask.cleanUpModelFn(
              context = context,
              coroutineScope = this,
              model = model,
            ) {
              model.instance = null
              continuation.resume(Unit)
            }
          } else {
            continuation.resume(Unit)
          }
        } else {
          continuation.resumeWithException(
            IllegalStateException(error.ifEmpty { "Failed to initialize model '${model.name}'." })
          )
        }
      }
    }
  }

  private suspend fun cleanupModel(binding: ModelBinding) = coroutineScope {
    val model = binding.model
    if (model.instance == null) {
      if (model.initializing) {
        model.cleanUpAfterInit = true
      }
      return@coroutineScope
    }

    suspendCancellableCoroutine<Unit> { continuation ->
      binding.customTask.cleanUpModelFn(
        context = context,
        coroutineScope = this,
        model = model,
      ) {
        model.instance = null
        model.initializing = false
        continuation.resume(Unit)
      }
    }
  }

  private fun readModelAllowlistFromDisk(): ModelAllowlist? {
    val externalFilesDir = context.getExternalFilesDir(null)
    val candidateFiles =
      listOf(
        File("/data/local/tmp", MODEL_ALLOWLIST_TEST_FILENAME),
        File(externalFilesDir, MODEL_ALLOWLIST_FILENAME),
      )

    for (file in candidateFiles) {
      if (!file.exists()) {
        continue
      }
      try {
        return gson.fromJson(file.readText(), ModelAllowlist::class.java)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse model allowlist from ${file.absolutePath}", e)
      }
    }

    return null
  }

  private fun isUnsupportedNpuOnlyModel(model: com.google.ai.edge.gallery.data.AllowedModel): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return false
    }
    val accelerators =
      model.defaultConfig.accelerators
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf()
    return accelerators.size == 1 &&
      accelerators[0] == "npu" &&
      model.socToModelFiles?.containsKey(SOC) == false
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU
            else -> null
          }
        }
        .toMutableList()
    val llmSupportThinking = info.llmConfig.supportThinking
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = info.llmConfig.defaultMaxTokens,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
        )
        .toMutableList()
    return Model(
        name = info.fileName,
        url = "",
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = "$IMPORTS_DIR/${info.fileName}",
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = info.llmConfig.supportImage,
        llmSupportAudio = info.llmConfig.supportAudio,
        llmSupportTinyGarden = info.llmConfig.supportTinyGarden,
        llmSupportMobileActions = info.llmConfig.supportMobileActions,
        llmSupportThinking = llmSupportThinking,
        llmMaxToken = info.llmConfig.defaultMaxTokens,
        accelerators = accelerators,
        isLlm = true,
        runtimeType = RuntimeType.LITERT_LM,
      )
      .also { it.preProcess() }
  }
}

private data class ServerTaskGraph(
  val tasks: List<Task>,
  val tasksById: Map<String, Task>,
  val modelsByName: Map<String, ModelBindings>,
)

private data class ModelBindings(
  val model: Model,
  val bindings: MutableList<ModelBinding>,
) {
  val taskIds: List<String>
    get() = bindings.map { it.task.id }

  fun resolve(preferredTaskId: String?): ModelBinding {
    if (preferredTaskId != null) {
      return bindings.find { it.task.id == preferredTaskId }
        ?: throw IllegalArgumentException(
          "Model '${model.name}' is not available for task '$preferredTaskId'."
        )
    }
    if (bindings.size > 1) {
      throw IllegalStateException(
        "Model '${model.name}' is shared by multiple tasks: ${taskIds.joinToString()}. " +
          "Pass a preferredTaskId to disambiguate."
      )
    }
    return bindings.first()
  }
}

private data class ModelBinding(
  val task: Task,
  val customTask: CustomTask,
  val model: Model,
)
