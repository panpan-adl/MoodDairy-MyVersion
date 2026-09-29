package com.example.mydiary.data.models

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate

/**
 * Todo request model for backend create API.
 */
data class CreateTodoRequest(
    @SerializedName("user_id")
    val userId: Long,

    @SerializedName("todo_date")
    val todoDate: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("sort_order")
    val sortOrder: Int? = null
)

/**
 * Todo request model for backend update API.
 */
data class UpdateTodoRequest(
    @SerializedName("title")
    val title: String? = null,

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("is_done")
    val isDone: Int? = null,

    @SerializedName("sort_order")
    val sortOrder: Int? = null
)

/**
 * Todo response model from backend API.
 */
data class TodoResponse(
    @SerializedName("id")
    val id: Long,

    @SerializedName("user_id")
    val userId: Long,

    @SerializedName("todo_date")
    val todoDate: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("is_done")
    val isDone: Int,

    @SerializedName("sort_order")
    val sortOrder: Int,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("updated_at")
    val updatedAt: String
)

/**
 * Todo domain model.
 */
data class TodoItem(
    val id: Long,
    val userId: Long,
    val date: LocalDate,
    val title: String,
    val note: String?,
    val isDone: Boolean,
    val sortOrder: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

fun TodoResponse.toDomain(): TodoItem {
    return TodoItem(
        id = id,
        userId = userId,
        date = LocalDate.parse(todoDate),
        title = title,
        note = note,
        isDone = isDone == 1,
        sortOrder = sortOrder,
        createdAt = parseInstantSafe(createdAt),
        updatedAt = parseInstantSafe(updatedAt)
    )
}

private fun parseInstantSafe(timestamp: String?): Instant? {
    if (timestamp.isNullOrBlank()) return null

    return try {
        Instant.parse(timestamp)
    } catch (_: Exception) {
        try {
            val localDateTime = java.time.LocalDateTime.parse(timestamp.replace(" ", "T"))
            localDateTime.atZone(java.time.ZoneId.of("UTC")).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
