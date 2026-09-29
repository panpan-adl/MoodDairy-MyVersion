package com.example.mydiary.data.repository

import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.models.DiaryMultimodalInsightResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GrowthPortraitRepository @Inject constructor(
    private val apiService: DiaryApiService,
) {
    fun getGrowthPortrait(
        days: Int = 30,
        endDate: String? = null,
    ): Flow<ApiResult<GrowthPortraitResponse>> = flow {
        emit(ApiResult.Loading)
        when (val result = SafeApiCall.execute { apiService.getGrowthPortrait(days = days, endDate = endDate) }) {
            is ApiResult.Success -> emit(result)
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }

    fun getDiaryMultimodalInsight(
        diaryId: Long,
    ): Flow<ApiResult<DiaryMultimodalInsightResponse>> = flow {
        emit(ApiResult.Loading)
        when (val result = SafeApiCall.execute { apiService.getDiaryMultimodalInsight(diaryId) }) {
            is ApiResult.Success -> emit(result)
            is ApiResult.Error -> emit(result)
            is ApiResult.Loading -> Unit
        }
    }
}
