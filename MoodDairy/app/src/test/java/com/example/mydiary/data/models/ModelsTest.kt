package com.example.mydiary.data.models

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate

/**
 * 数据模型单元测试
 * 验证领域模型的创建和转换功能
 */
class ModelsTest {

    @Test
    fun `test Diary domain model creation`() {
        // Given
        val diary = Diary(
            id = 1L,
            userId = 100L,
            title = "测试日记",
            date = LocalDate.of(2024, 1, 15),
            mediaItems = emptyList(),
            weather = "晴天",
            location = "北京",
            moodScore = 80,
            moodType = "快乐",
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // Then
        assertNotNull(diary)
        assertEquals(1L, diary.id)
        assertEquals(100L, diary.userId)
        assertEquals("测试日记", diary.title)
        assertEquals(LocalDate.of(2024, 1, 15), diary.date)
        assertEquals("晴天", diary.weather)
        assertEquals("北京", diary.location)
        assertEquals(80, diary.moodScore)
        assertEquals("快乐", diary.moodType)
    }

    @Test
    fun `test MediaItem Text creation`() {
        // Given
        val now = Instant.now()
        val textItem = MediaItem.Text(
            id = "1",
            content = "这是一段文字内容",
            order = 0,
            createdAt = now
        )

        // Then
        assertNotNull(textItem)
        assertEquals("1", textItem.id)
        assertEquals("这是一段文字内容", textItem.content)
        assertEquals(0, textItem.order)
        assertEquals(now, textItem.createdAt)
    }

    @Test
    fun `test MediaItem Image creation`() {
        // Given
        val now = Instant.now()
        val imageItem = MediaItem.Image(
            id = "2",
            url = "https://example.com/image.jpg",
            thumbnailUrl = "https://example.com/thumb.jpg",
            order = 1,
            createdAt = now
        )

        // Then
        assertNotNull(imageItem)
        assertEquals("2", imageItem.id)
        assertEquals("https://example.com/image.jpg", imageItem.url)
        assertEquals("https://example.com/thumb.jpg", imageItem.thumbnailUrl)
        assertEquals(1, imageItem.order)
    }

    @Test
    fun `test MediaItem Audio creation`() {
        // Given
        val now = Instant.now()
        val audioItem = MediaItem.Audio(
            id = "3",
            url = "https://example.com/audio.mp3",
            duration = 120,
            transcription = null,
            order = 2,
            createdAt = now
        )

        // Then
        assertNotNull(audioItem)
        assertEquals("3", audioItem.id)
        assertEquals("https://example.com/audio.mp3", audioItem.url)
        assertEquals(120, audioItem.duration)
        assertNull(audioItem.transcription)
        assertEquals(2, audioItem.order)
    }

    @Test
    fun `test MediaItem Video creation`() {
        // Given
        val now = Instant.now()
        val videoItem = MediaItem.Video(
            id = "4",
            url = "https://example.com/video.mp4",
            thumbnailUrl = "https://example.com/video-thumb.jpg",
            duration = 300,
            order = 3,
            createdAt = now
        )

        // Then
        assertNotNull(videoItem)
        assertEquals("4", videoItem.id)
        assertEquals("https://example.com/video.mp4", videoItem.url)
        assertEquals("https://example.com/video-thumb.jpg", videoItem.thumbnailUrl)
        assertEquals(300, videoItem.duration)
        assertEquals(3, videoItem.order)
    }

    @Test
    fun `test EmotionAnalysis creation`() {
        // Given
        val emotion = EmotionAnalysis(
            emotionType = "快乐",
            score = 85,
            confidence = 0.92f
        )

        // Then
        assertNotNull(emotion)
        assertEquals("快乐", emotion.emotionType)
        assertEquals(85, emotion.score)
        assertEquals(0.92f, emotion.confidence, 0.001f)
    }

    @Test
    fun `test VoiceTranscription creation`() {
        // Given
        val emotion = EmotionAnalysis(
            emotionType = "快乐",
            score = 85,
            confidence = 0.92f
        )
        val transcription = VoiceTranscription(
            originalText = "嗯，今天天气真好啊",
            processedText = "今天天气真好",
            emotion = emotion
        )

        // Then
        assertNotNull(transcription)
        assertEquals("嗯，今天天气真好啊", transcription.originalText)
        assertEquals("今天天气真好", transcription.processedText)
        assertEquals(emotion, transcription.emotion)
    }

    @Test
    fun `test DiaryResponse to Diary domain conversion`() {
        // Given
        val response = DiaryResponse(
            id = 1L,
            userId = 100L,
            title = "测试日记",
            content = null,
            diaryDate = "2024-01-15",
            weather = "晴天",
            location = "北京",
            moodScore = 80,
            moodType = "快乐",
            isPrivate = 1,
            isExtracted = 0,
            wordCount = 100,
            createdAt = "2024-01-15T10:00:00Z",
            updatedAt = "2024-01-15T10:00:00Z",
            mediaItems = emptyList()
        )

        // When
        val diary = response.toDomain()

        // Then
        assertNotNull(diary)
        assertEquals(1L, diary.id)
        assertEquals(100L, diary.userId)
        assertEquals("测试日记", diary.title)
        assertEquals(LocalDate.of(2024, 1, 15), diary.date)
        assertEquals("晴天", diary.weather)
        assertEquals("北京", diary.location)
        assertEquals(80, diary.moodScore)
        assertEquals("快乐", diary.moodType)
        assertTrue(diary.mediaItems.isEmpty())
    }

    @Test
    fun `test MediaItemResponse to MediaItem Text conversion`() {
        // Given
        val response = MediaItemResponse(
            id = 1L,
            diaryId = 100L,
            mediaType = "text",
            content = "这是文字内容",
            mediaUrl = null,
            thumbnailUrl = null,
            duration = null,
            fileSize = null,
            sortOrder = 0,
            createdAt = "2024-01-15T10:00:00Z"
        )

        // When
        val mediaItem = response.toDomain()

        // Then
        assertTrue(mediaItem is MediaItem.Text)
        val textItem = mediaItem as MediaItem.Text
        assertEquals("1", textItem.id)
        assertEquals("这是文字内容", textItem.content)
        assertEquals(0, textItem.order)
    }

    @Test
    fun `test MediaItemResponse to MediaItem Image conversion`() {
        // Given
        val response = MediaItemResponse(
            id = 2L,
            diaryId = 100L,
            mediaType = "image",
            content = null,
            mediaUrl = "https://example.com/image.jpg",
            thumbnailUrl = "https://example.com/thumb.jpg",
            duration = null,
            fileSize = 1024L,
            sortOrder = 1,
            createdAt = "2024-01-15T10:00:00Z"
        )

        // When
        val mediaItem = response.toDomain()

        // Then
        assertTrue(mediaItem is MediaItem.Image)
        val imageItem = mediaItem as MediaItem.Image
        assertEquals("2", imageItem.id)
        assertEquals("https://example.com/image.jpg", imageItem.url)
        assertEquals("https://example.com/thumb.jpg", imageItem.thumbnailUrl)
        assertEquals(1, imageItem.order)
    }

    @Test
    fun `test MediaItemResponse to MediaItem Audio conversion`() {
        // Given
        val response = MediaItemResponse(
            id = 3L,
            diaryId = 100L,
            mediaType = "audio",
            content = null,
            mediaUrl = "https://example.com/audio.mp3",
            thumbnailUrl = null,
            duration = 120,
            fileSize = 2048L,
            sortOrder = 2,
            createdAt = "2024-01-15T10:00:00Z"
        )

        // When
        val mediaItem = response.toDomain()

        // Then
        assertTrue(mediaItem is MediaItem.Audio)
        val audioItem = mediaItem as MediaItem.Audio
        assertEquals("3", audioItem.id)
        assertEquals("https://example.com/audio.mp3", audioItem.url)
        assertEquals(120, audioItem.duration)
        assertEquals(2, audioItem.order)
    }

    @Test
    fun `test MediaItemResponse to MediaItem Video conversion`() {
        // Given
        val response = MediaItemResponse(
            id = 4L,
            diaryId = 100L,
            mediaType = "video",
            content = null,
            mediaUrl = "https://example.com/video.mp4",
            thumbnailUrl = "https://example.com/video-thumb.jpg",
            duration = 300,
            fileSize = 10240L,
            sortOrder = 3,
            createdAt = "2024-01-15T10:00:00Z"
        )

        // When
        val mediaItem = response.toDomain()

        // Then
        assertTrue(mediaItem is MediaItem.Video)
        val videoItem = mediaItem as MediaItem.Video
        assertEquals("4", videoItem.id)
        assertEquals("https://example.com/video.mp4", videoItem.url)
        assertEquals("https://example.com/video-thumb.jpg", videoItem.thumbnailUrl)
        assertEquals(300, videoItem.duration)
        assertEquals(3, videoItem.order)
    }

    @Test
    fun `test EmotionResult to EmotionAnalysis conversion`() {
        // Given
        val emotionResult = EmotionResult(
            emotionType = "快乐",
            score = 85,
            confidence = 0.92f,
            details = null
        )

        // When
        val emotionAnalysis = emotionResult.toDomain()

        // Then
        assertNotNull(emotionAnalysis)
        assertEquals("快乐", emotionAnalysis.emotionType)
        assertEquals(85, emotionAnalysis.score)
        assertEquals(0.92f, emotionAnalysis.confidence, 0.001f)
    }

    @Test
    fun `test VoiceProcessingResult to VoiceTranscription conversion`() {
        // Given
        val emotionResult = EmotionResult(
            emotionType = "快乐",
            score = 85,
            confidence = 0.92f,
            details = null
        )
        val voiceResult = VoiceProcessingResult(
            originalText = "嗯，今天天气真好啊",
            processedText = "今天天气真好",
            emotion = emotionResult,
            requestId = "req-123",
            timestamp = "2024-01-15T10:00:00Z"
        )

        // When
        val transcription = voiceResult.toVoiceTranscription()

        // Then
        assertNotNull(transcription)
        assertEquals("嗯，今天天气真好啊", transcription.originalText)
        assertEquals("今天天气真好", transcription.processedText)
        assertEquals("快乐", transcription.emotion.emotionType)
        assertEquals(85, transcription.emotion.score)
    }

    @Test
    fun `test Diary to CreateDiaryRequest conversion`() {
        // Given
        val diary = Diary(
            id = null,
            userId = 100L,
            title = "新日记",
            date = LocalDate.of(2024, 1, 15),
            mediaItems = emptyList(),
            weather = "晴天",
            location = "北京",
            moodScore = 80,
            moodType = "快乐",
            createdAt = null,
            updatedAt = null
        )

        // When
        val request = diary.toCreateRequest()

        // Then
        assertNotNull(request)
        assertEquals(100L, request.userId)
        assertEquals("新日记", request.title)
        assertEquals("2024-01-15", request.diaryDate)
        assertEquals("晴天", request.weather)
        assertEquals("北京", request.location)
        assertEquals(80, request.moodScore)
        assertEquals("快乐", request.moodType)
        assertEquals(1, request.isPrivate)
    }

    @Test
    fun `test Diary to UpdateDiaryRequest conversion`() {
        // Given
        val diary = Diary(
            id = 1L,
            userId = 100L,
            title = "更新的日记",
            date = LocalDate.of(2024, 1, 15),
            mediaItems = emptyList(),
            weather = "多云",
            location = "上海",
            moodScore = 75,
            moodType = "平静",
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // When
        val request = diary.toUpdateRequest()

        // Then
        assertNotNull(request)
        assertEquals("更新的日记", request.title)
        assertEquals("多云", request.weather)
        assertEquals("上海", request.location)
        assertEquals(75, request.moodScore)
        assertEquals("平静", request.moodType)
        assertNull(request.isPrivate)
    }

    @Test
    fun `test MediaItem sealed class polymorphism`() {
        // Given
        val now = Instant.now()
        val mediaItems: List<MediaItem> = listOf(
            MediaItem.Text(id = "1", assetId = null, content = "文字", order = 0, createdAt = now),
            MediaItem.Image(id = "2", assetId = null, url = "url", thumbnailUrl = null, order = 1, createdAt = now),
            MediaItem.Audio(id = "3", assetId = null, url = "url", duration = 120, transcription = null, order = 2, createdAt = now),
            MediaItem.Video(id = "4", assetId = null, url = "url", thumbnailUrl = null, duration = 300, order = 3, createdAt = now)
        )

        // Then
        assertEquals(4, mediaItems.size)
        assertTrue(mediaItems[0] is MediaItem.Text)
        assertTrue(mediaItems[1] is MediaItem.Image)
        assertTrue(mediaItems[2] is MediaItem.Audio)
        assertTrue(mediaItems[3] is MediaItem.Video)
    }
}
