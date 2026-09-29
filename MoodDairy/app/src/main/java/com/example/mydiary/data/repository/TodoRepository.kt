package com.example.mydiary.data.repository

import com.example.mydiary.data.models.CreateTodoRequest
import com.example.mydiary.data.models.TodoItem
import com.example.mydiary.data.models.UpdateTodoRequest
import com.example.mydiary.data.models.toDomain
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepository @Inject constructor(
    private val apiService: DiaryApiService
) {

    fun getTodosByDate(
        userId: Long,
        date: LocalDate
    ): Flow<ApiResult<List<TodoItem>>> = flow {
        emit(ApiResult.Loading)

        val result = SafeApiCall.execute {
            apiService.getTodosByDate(
                userId = userId,
                date = date.toString()
            )
        }

        when (result) {
            is ApiResult.Success -> emit(ApiResult.Success(result.data.map { it.toDomain() }))
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }

    fun createTodo(
        userId: Long,
        date: LocalDate,
        title: String,
        note: String? = null
    ): Flow<ApiResult<TodoItem>> = flow {
        emit(ApiResult.Loading)

        val request = CreateTodoRequest(
            userId = userId,
            todoDate = date.toString(),
            title = title.trim(),
            note = note?.takeIf { it.isNotBlank() }
        )

        val result = SafeApiCall.execute {
            apiService.createTodo(request)
        }

        when (result) {
            is ApiResult.Success -> emit(ApiResult.Success(result.data.toDomain()))
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }

    fun updateTodo(
        todoId: Long,
        userId: Long,
        title: String? = null,
        note: String? = null,
        isDone: Boolean? = null,
        sortOrder: Int? = null
    ): Flow<ApiResult<TodoItem>> = flow {
        emit(ApiResult.Loading)

        val request = UpdateTodoRequest(
            title = title?.trim(),
            note = note,
            isDone = isDone?.let { if (it) 1 else 0 },
            sortOrder = sortOrder
        )

        val result = SafeApiCall.execute {
            apiService.updateTodo(
                todoId = todoId,
                userId = userId,
                request = request
            )
        }

        when (result) {
            is ApiResult.Success -> emit(ApiResult.Success(result.data.toDomain()))
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }

    fun deleteTodo(
        todoId: Long,
        userId: Long
    ): Flow<ApiResult<Unit>> = flow {
        emit(ApiResult.Loading)

        val result = SafeApiCall.execute {
            apiService.deleteTodo(todoId = todoId, userId = userId)
        }

        when (result) {
            is ApiResult.Success -> emit(ApiResult.Success(Unit))
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }
}
