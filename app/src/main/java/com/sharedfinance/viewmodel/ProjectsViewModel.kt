package com.sharedfinance.viewmodel

import android.content.Context
import com.sharedfinance.data.repository.SharedFinanceRepository
import com.sharedfinance.model.Project
import com.sharedfinance.model.ProjectStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val pinnedProjectIds: Set<UUID> = emptySet(),
    val projectBalances: Map<UUID, Double> = emptyMap(),
    val isLoading: Boolean = true,
    val searchText: String = "",
    val draftTitle: String = "",
    val draftDetails: String = "",
    val editingProjectId: java.util.UUID? = null,
    val editingTitle: String = "",
    val editingDetails: String = "",
    val editingStatus: ProjectStatus = ProjectStatus.ACTIVE
)

class ProjectsViewModel(
    context: Context,
    private val repository: SharedFinanceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        ProjectsUiState(pinnedProjectIds = loadPinnedProjectIds())
    )
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        combine(
            repository.observeProjects(),
            repository.observeParticipants(),
            repository.observeExpenses()
        ) { projects, participants, expenses ->
            val participantById = participants.associateBy { it.id }
            val projectBalances = projects.associate { project ->
                val contributionTotal = project.participantIds.sumOf { participantId ->
                    participantById[participantId]?.contributionAmount ?: 0.0
                }
                val expensesTotal = expenses
                    .filter { expense -> expense.projectId == project.id }
                    .sumOf { it.amount }
                project.id to (contributionTotal - expensesTotal)
            }
            projects to projectBalances
        }
            .onEach { (projects, projectBalances) ->
                _state.value = _state.value.copy(
                    projects = projects
                        .sortedWith(
                            compareBy<Project> { it.id !in _state.value.pinnedProjectIds }
                                .thenBy { it.status == ProjectStatus.ARCHIVED }
                                .thenByDescending { it.updatedAt.time }
                        ),
                    projectBalances = projectBalances,
                    isLoading = false
                )
            }
            .launchIn(scope)
    }

    val filteredProjects: List<Project>
        get() {
            val query = _state.value.searchText.trim()
            if (query.isEmpty()) return _state.value.projects
            return _state.value.projects.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.details.contains(query, ignoreCase = true)
            }
        }

    fun updateSearchText(value: String) {
        _state.value = _state.value.copy(searchText = value)
    }

    fun updateDraftTitle(value: String) {
        _state.value = _state.value.copy(draftTitle = value)
    }

    fun updateDraftDetails(value: String) {
        _state.value = _state.value.copy(draftDetails = value)
    }

    fun createProject() {
        val title = _state.value.draftTitle.trim()
        if (title.isEmpty()) return
        val details = _state.value.draftDetails.trim()
        scope.launch {
            repository.createProject(title, details)
            _state.value = _state.value.copy(draftTitle = "", draftDetails = "")
        }
    }

    fun beginEdit(project: Project) {
        _state.value = _state.value.copy(
            editingProjectId = project.id,
            editingTitle = project.title,
            editingDetails = project.details,
            editingStatus = project.status
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(
            editingProjectId = null,
            editingTitle = "",
            editingDetails = "",
            editingStatus = ProjectStatus.ACTIVE
        )
    }

    fun updateEditingTitle(value: String) {
        _state.value = _state.value.copy(editingTitle = value)
    }

    fun updateEditingDetails(value: String) {
        _state.value = _state.value.copy(editingDetails = value)
    }

    fun updateEditingStatus(status: ProjectStatus) {
        _state.value = _state.value.copy(editingStatus = status)
    }

    fun saveEdit() {
        val projectId = _state.value.editingProjectId ?: return
        val title = _state.value.editingTitle.trim()
        if (title.isEmpty()) return
        val current = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val updated = current.copy(
            title = title,
            details = _state.value.editingDetails.trim(),
            status = _state.value.editingStatus
        )

        scope.launch {
            repository.updateProject(updated)
            cancelEdit()
        }
    }

    fun archiveProject(projectId: java.util.UUID) {
        scope.launch {
            repository.archiveProject(projectId)
        }
    }

    fun togglePinned(projectId: UUID) {
        val updatedPinned = if (projectId in _state.value.pinnedProjectIds) {
            _state.value.pinnedProjectIds - projectId
        } else {
            _state.value.pinnedProjectIds + projectId
        }

        savePinnedProjectIds(updatedPinned)
        _state.value = _state.value.copy(
            pinnedProjectIds = updatedPinned,
            projects = _state.value.projects.sortedWith(
                compareBy<Project> { it.id !in updatedPinned }
                    .thenBy { it.status == ProjectStatus.ARCHIVED }
                    .thenByDescending { it.updatedAt.time }
            )
        )
    }

    fun deleteProject(projectId: java.util.UUID) {
        scope.launch {
            if (projectId in _state.value.pinnedProjectIds) {
                val updatedPinned = _state.value.pinnedProjectIds - projectId
                savePinnedProjectIds(updatedPinned)
                _state.value = _state.value.copy(pinnedProjectIds = updatedPinned)
            }
            repository.deleteProject(projectId)
        }
    }

    private fun loadPinnedProjectIds(): Set<UUID> {
        val values = prefs.getStringSet(KEY_PINNED_PROJECT_IDS, emptySet()) ?: emptySet()
        return values.mapNotNull { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }.toSet()
    }

    private fun savePinnedProjectIds(ids: Set<UUID>) {
        prefs.edit().putStringSet(KEY_PINNED_PROJECT_IDS, ids.map { it.toString() }.toSet()).apply()
    }

    private companion object {
        const val PREFS_NAME = "shared_finance_settings"
        const val KEY_PINNED_PROJECT_IDS = "pinned_project_ids"
    }
}
