package com.example.mydiary.ui.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydiary.data.local.SecurePreferences
import com.example.mydiary.data.models.*
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import com.example.mydiary.data.repository.UserRepository
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Response

data class TodayState(
    val date: String = LocalDate.now().toString(),
    val record: String = "",
    val energy: String = "normal",
    val availableMinutes: Int = 120,
    val plan: AgentPlan? = null,
    val planRequestId: String = UUID.randomUUID().toString(),
    val tasks: List<AgentTask> = emptyList(),
    val historicalTasks: List<AgentTask> = emptyList(),
    val historyLoaded: Boolean = false,
    val reflection: String = "",
    val review: String = "",
    val reviewRequestId: String = UUID.randomUUID().toString(),
    val savedReviewId: Long? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val api: DiaryApiService,
    @Named("longTimeout") private val aiApi: DiaryApiService,
    private val users: UserRepository,
    @ApplicationContext context: Context
) : ViewModel() {
    private val prefs = SecurePreferences.create(context, "personal_agent_drafts")
    private val gson = Gson()
    private val _state = MutableStateFlow(TodayState())
    val state = _state.asStateFlow()
    private var owner: Long? = null

    private fun key() = "${owner}_${_state.value.date}"
    private fun persist() {
        prefs.edit().putString(key(), gson.toJson(_state.value.copy(tasks = emptyList(), busy = false,
            loaded = false, error = null, message = null))).apply()
    }
    fun refresh() {
        if (_state.value.busy) return
        val user = users.getCurrentUserId() ?: return
        val day = LocalDate.now().toString()
        if (owner != user || _state.value.date != day) {
            owner = user
            val saved = runCatching { gson.fromJson(prefs.getString("${user}_$day", null), TodayState::class.java) }.getOrNull()
            _state.value = saved?.takeIf { it.date == day } ?: TodayState(date = day)
        }
        action {
            val today = call { api.agentToday(day) }
            _state.update { it.copy(tasks = today.tasks, loaded = true) }
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                block()
                persist()
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                _state.update { it.copy(error = exc.message ?: "操作失败，请重试") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        return when (val result = SafeApiCall.execute(block)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> throw IllegalStateException(result.message)
            ApiResult.Loading -> throw IllegalStateException("请稍后重试")
        }
    }
    fun updateRecord(value: String) {
        _state.update { it.copy(record = value.take(6000), plan = null, planRequestId = UUID.randomUUID().toString()) }
        persist()
    }
    fun updateEnergy(value: String) {
        _state.update { it.copy(energy = value, plan = null) }; persist()
    }
    fun updateMinutes(value: Int) {
        _state.update { it.copy(availableMinutes = value, plan = null) }; persist()
    }
    fun generatePlan() {
        if (_state.value.record.isBlank()) {
            _state.update { it.copy(error = "先写一句今天的想法、目标或待办") }; return
        }
        action {
            val s = _state.value
            val plan = call { aiApi.agentPlan(AgentPlanRequest(s.date, s.record, s.energy, s.availableMinutes)) }
            _state.update { it.copy(plan = plan, planRequestId = UUID.randomUUID().toString()) }
        }
    }
    fun saveRecord() {
        val s = _state.value
        if (s.record.isBlank()) return
        action {
            call { api.agentSaveRecord(AgentSaveRecord(s.date, s.record, s.planRequestId)) }
            _state.update { it.copy(record = "", plan = null, planRequestId = UUID.randomUUID().toString(), message = "记录已保存为私密日记") }
        }
    }
    fun manualPlan() {
        _state.update { it.copy(plan = AgentPlan("添加你想完成的任务，然后确认加入今天。", listOf(AgentTaskDraft()))) }
        persist()
    }
    fun editDraft(index: Int, draft: AgentTaskDraft) {
        _state.update { s -> s.copy(plan = s.plan?.copy(tasks = s.plan.tasks.mapIndexed { i, t -> if (i == index) draft else t })) }
        persist()
    }
    fun addDraft() {
        _state.update { s -> s.copy(plan = s.plan?.let { if (it.tasks.size < 6) it.copy(tasks = it.tasks + AgentTaskDraft()) else it }) }; persist()
    }
    fun removeDraft(index: Int) {
        _state.update { s -> s.copy(plan = s.plan?.copy(tasks = s.plan.tasks.filterIndexed { i, _ -> i != index })) }; persist()
    }
    fun adopt() {
        val s = _state.value
        val drafts = s.plan?.tasks.orEmpty()
        if (drafts.isEmpty() || drafts.any { it.title.isBlank() }) {
            _state.update { it.copy(error = "请填写任务名称，或删除空白任务") }; return
        }
        action {
            val today = call { api.agentAdopt(AgentAdoptRequest(s.date, s.record.ifBlank { "今天计划：" + drafts.joinToString("、") { it.title } },
                s.energy, s.availableMinutes, s.planRequestId, drafts)) }
            _state.update { it.copy(tasks = today.tasks, loaded = true, plan = null, record = "",
                planRequestId = UUID.randomUUID().toString(), message = "任务已加入今天，原始记录已保存到日记") }
        }
    }
    fun updateTask(task: AgentTask, draft: AgentTaskDraft) = action {
        val updated = call { api.agentUpdateTask(task.id, AgentTaskUpdate(title = draft.title,
            domain = draft.domain, minutes = draft.minutes, nextStep = draft.nextStep)) }
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }
    }
    fun toggle(task: AgentTask) = action {
        val updated = call { api.agentUpdateTask(task.id, AgentTaskUpdate(isDone = !task.isDone)) }
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }
    }
    fun postpone(task: AgentTask) = action {
        call { api.agentUpdateTask(task.id, AgentTaskUpdate(date = LocalDate.parse(task.date).plusDays(1).toString())) }
        _state.update { s -> s.copy(tasks = s.tasks.filter { it.id != task.id }, message = "已延期到明天") }
    }
    fun updateReflection(value: String) { _state.update { it.copy(reflection = value.take(6000)) }; persist() }
    fun generateReview() = action {
        val s = _state.value
        val review = call { aiApi.agentReview(AgentReviewRequest(s.date, s.reflection)) }
        _state.update { it.copy(review = review.content, savedReviewId = null, reviewRequestId = UUID.randomUUID().toString()) }
    }
    fun updateReview(value: String) {
        _state.update { it.copy(review = value.take(12000)) }; persist()
    }
    fun saveReview() {
        val s = _state.value
        if (s.review.isBlank() || s.savedReviewId != null) return
        action {
            val saved = call { api.agentSaveReview(AgentSaveReview(s.date, s.review, s.reviewRequestId)) }
            _state.update { it.copy(savedReviewId = saved.diaryId, message = "复盘已保存为私密日记") }
        }
    }
    fun clearNotice() { _state.update { it.copy(error = null, message = null) } }

    fun loadHistory() = action {
        val history = call { api.agentTaskHistory() }
        _state.update { it.copy(historicalTasks = history, historyLoaded = true) }
    }

    fun toggleHistorical(task: AgentTask) = action {
        val updated = call { api.agentUpdateTask(task.id, AgentTaskUpdate(isDone = !task.isDone)) }
        _state.update { s -> s.copy(historicalTasks = s.historicalTasks.map { if (it.id == updated.id) updated else it }) }
    }

    fun bringToToday(task: AgentTask) = action {
        val updated = call { api.agentUpdateTask(task.id, AgentTaskUpdate(date = _state.value.date)) }
        _state.update { s -> s.copy(tasks = s.tasks + updated, historicalTasks = s.historicalTasks.filterNot { it.id == updated.id }) }
    }
}
