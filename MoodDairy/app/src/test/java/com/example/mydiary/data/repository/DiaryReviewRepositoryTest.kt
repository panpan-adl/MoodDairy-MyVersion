package com.example.mydiary.data.repository

import com.example.mydiary.data.models.DiaryReviewRequest
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.network.DiaryApiService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response

class DiaryReviewRepositoryTest {
    @Test fun offlineWordCloudPayloadKeepsWeightsAndCategories() {
        val json = """{"diary_id":10,"content_version":"v3","has_text":true,"keywords":[{"text":"安心","weight":0.82,"category":"calm","kind":"emotion"}],"extraction_method":"keybert-local+lexicon"}"""
        val review = com.google.gson.Gson().fromJson(json, DiaryReviewResponse::class.java)
        org.junit.Assert.assertEquals("安心", review.keywords.single().text)
        org.junit.Assert.assertEquals(.82f, review.keywords.single().weight, .001f)
        org.junit.Assert.assertEquals("calm", review.keywords.single().category)
        org.junit.Assert.assertEquals("keybert-local+lexicon", review.extractionMethod)
    }

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
