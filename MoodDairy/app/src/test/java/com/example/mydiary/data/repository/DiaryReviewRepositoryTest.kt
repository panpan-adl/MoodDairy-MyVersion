package com.example.mydiary.data.repository

import com.example.mydiary.data.models.DiaryReviewRequest
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.network.DiaryApiService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

class DiaryReviewRepositoryTest {
    @Test fun cachedReadsAndExplicitGenerationUseTheirRespectiveClients() = runTest {
        val reads = mockk<DiaryApiService>()
        val generation = mockk<DiaryApiService>()
        val value = DiaryReviewResponse(10, "version")
        coEvery { reads.getDiaryReview(10) } returns Response.success(value)
        coEvery { generation.generateDiaryReview(10, DiaryReviewRequest("version", true)) } returns Response.success(value)
        val repo = GrowthPortraitRepository(reads, generation)
        repo.getDiaryReview(10)
        repo.generateDiaryReview(10, "version", true)
        coVerify(exactly = 1) { reads.getDiaryReview(10) }
        coVerify(exactly = 0) { reads.generateDiaryReview(any(), any()) }
        coVerify(exactly = 1) { generation.generateDiaryReview(10, DiaryReviewRequest("version", true)) }
    }
}
