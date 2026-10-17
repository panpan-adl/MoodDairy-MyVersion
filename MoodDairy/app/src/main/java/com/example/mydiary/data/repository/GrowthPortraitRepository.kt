package com.example.mydiary.data.repository

import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.models.DiaryReviewRequest
import com.example.mydiary.data.models.TitleRecommendationRequest
import com.example.mydiary.data.models.TitleRecommendationResponse
import com.example.mydiary.data.models.GrowthPortraitResponse
import com.example.mydiary.data.models.DiaryMultimodalInsightResponse
import com.example.mydiary.data.network.ApiResult
import com.example.mydiary.data.network.DiaryApiService
import com.example.mydiary.data.network.SafeApiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named

@Singleton
class GrowthPortraitRepository @Inject constructor(
    private val apiService: DiaryApiService,
    @Named("diaryReview") private val reviewApiService: DiaryApiService,
) {
    suspend fun recommendDiaryTitle(text: String): ApiResult<TitleRecommendationResponse> =
        SafeApiCall.execute { reviewApiService.recommendDiaryTitle(TitleRecommendationRequest(text)) }

    suspend fun getDiaryReview(diaryId: Long): ApiResult<DiaryReviewResponse> =
        SafeApiCall.execute { apiService.getDiaryReview(diaryId) }

    suspend fun generateDiaryReview(diaryId: Long, version: String?, refresh: Boolean): ApiResult<DiaryReviewResponse> =
        SafeApiCall.execute { reviewApiService.generateDiaryReview(diaryId, DiaryReviewRequest(version, refresh)) }

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
