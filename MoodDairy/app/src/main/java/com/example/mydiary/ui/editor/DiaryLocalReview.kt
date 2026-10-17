package com.example.mydiary.ui.editor

import com.example.mydiary.data.models.DiaryReviewKeyword
import com.example.mydiary.data.models.DiaryReviewResponse
import com.example.mydiary.data.models.MediaItem
import java.time.Instant

internal fun diaryReviewText(items: List<MediaItem>): String = items.sortedBy { it.order }.mapNotNull {
    when (it) {
        is MediaItem.Text -> it.content
        is MediaItem.Audio -> it.transcription?.let { voice -> voice.processedText.ifBlank { voice.originalText } }
        else -> null
    }
}.filter { it.isNotBlank() }.joinToString("\n")

internal fun shouldShowDiaryReview(state: DiaryEditorUiState): Boolean =
    !state.isLoading && diaryReviewText(state.mediaItems).isNotBlank()

/** Review the last confirmed text cards even while the rest of the diary has pending edits. */
internal fun confirmedDiaryReviewText(state: DiaryEditorUiState): String {
    if (state.diaryId == null) return ""
    if (state.confirmedText.isEmpty() && !state.hasUnsavedChanges) return diaryReviewText(state.mediaItems)
    return state.mediaItems.sortedBy { it.order }.filterIsInstance<MediaItem.Text>()
        .mapNotNull { state.confirmedText[it.id] }.filter { it.isNotBlank() }.joinToString("\n")
}

private val localEmotionWords = linkedMapOf(
    "joy" to "开心 快乐 高兴 喜悦 幸福 满足 惊喜 兴奋 感恩 感谢 温暖 温柔",
    "calm" to "平静 安心 安宁 放松 轻松 松弛 踏实 舒服 舒适 松了一口气",
    "hope" to "期待 希望 向往 憧憬",
    "sadness" to "难过 沮丧 低落 悲伤 失落 遗憾 孤独 委屈 迷茫 想念",
    "anxiety" to "焦虑 忐忑 紧张 担心 不安 害怕 烦躁 压力",
    "fatigue" to "疲惫 疲倦 劳累 累", "anger" to "生气 愤怒 恼火 烦",
).flatMap { (category, words) -> words.split(' ').map { it to category } }.toMap()
private val localEmotionPattern = Regex(localEmotionWords.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
private val localNegation = Regex("(?:没有|并不|不是|不再|不太|不怎么|不|没)(?:很|那么|特别|太)?$")
private val localLabels = mapOf("joy" to "喜悦", "calm" to "安宁", "hope" to "期待", "sadness" to "低落",
    "anxiety" to "紧张", "fatigue" to "疲惫", "anger" to "烦闷")

/** Dependency-free, exact-source extraction when the server/model is unavailable. Never invents a mood. */
internal fun localDiaryReview(diaryId: Long, text: String, version: String = "local"): DiaryReviewResponse {
    val source = text.take(24000)
    val words = linkedMapOf<String, DiaryReviewKeyword>()
    localEmotionPattern.findAll(source).forEach { match ->
        val prefix = source.substring(maxOf(0, match.range.first - 8), match.range.first)
        val negation = localNegation.find(prefix)?.value.orEmpty()
        val word = negation + match.value
        words[word] = DiaryReviewKeyword(word, .82f, if (negation.isEmpty()) localEmotionWords.getValue(match.value) else "neutral", "emotion")
    }
    val candidates = Regex("[\\p{IsHan}A-Za-z0-9]+").findAll(source)
        .flatMap { span ->
            // Separate common connectors, then retain short phrases verbatim, including unfamiliar names.
            span.value.split(Regex("(?:然后|但是|因为|所以|今天|昨天|自己|觉得|感觉|一个|一些|没有|已经|正在|的|了|和|也|在|我|是|很|又|就|都|着|与)"))
                .asSequence().flatMap { it.chunked(6).asSequence() }
        }.filter { it.isNotBlank() && source.contains(it) }.toList()
    candidates.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        .forEach { (word, count) ->
            if (words.size < 12 && words.keys.none { word in it || it in word })
                words[word] = DiaryReviewKeyword(word, (.35f + count * .04f).coerceAtMost(.7f))
        }
    if (words.isEmpty() && source.isNotBlank()) {
        val phrase = source.trim().take(8)
        words[phrase] = DiaryReviewKeyword(phrase, .5f)
    }
    val selected = words.values.take(12).sortedByDescending { it.weight }
    val feelings = selected.filter { it.kind == "emotion" && it.category != "neutral" }
    return DiaryReviewResponse(diaryId, version,
        body = if (feelings.isEmpty()) "已从原文整理关键词；这段文字没有明确的情绪表达，不额外推断心情。"
            else "原文提到了${feelings.take(3).joinToString("、") { "“${it.text}”" }}。这些词呈现了你写下的感受。",
        primaryEmotion = feelings.mapNotNull { localLabels[it.category] }.distinct().take(3).joinToString("、").ifBlank { "情绪不明确" },
        generatedAt = Instant.now().toString(), hasText = source.isNotBlank(), keywords = selected,
        extractionMethod = "device-lexicon+source-phrases")
}
