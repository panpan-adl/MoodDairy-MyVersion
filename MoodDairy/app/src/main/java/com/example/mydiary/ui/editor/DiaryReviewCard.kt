package com.example.mydiary.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mydiary.R
import com.example.mydiary.data.models.DiaryReviewKeyword
import com.example.mydiary.data.models.DiaryReviewResponse
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val DiarySerif = FontFamily(Font(R.font.diary_serif, FontWeight.Normal))

@Composable
internal fun DiaryReviewCard(
    review: DiaryReviewResponse?, dirty: Boolean, generating: Boolean,
    error: String?, onGenerate: () -> Unit, modifier: Modifier = Modifier,
    hasSavedText: Boolean = false,
) {
    var opened by rememberSaveable(review?.diaryId) { mutableStateOf(false) }
    var waitingToOpen by remember { mutableStateOf(false) }
    var details by rememberSaveable(review?.diaryId) { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    val words = review?.keywords.orEmpty().filter { it.text.isNotBlank() }
        .distinctBy { it.text }.sortedByDescending { it.weight }.take(12)
    val available = words.isNotEmpty() || review?.body != null
    val canGenerate = !generating && hasSavedText
    LaunchedEffect(review?.generatedAt, generating, error) {
        if (shouldOpenDiaryReview(waitingToOpen, generating, available, error)) {
            opened = true; replay++; waitingToOpen = false
        } else if (!generating && !error.isNullOrBlank()) waitingToOpen = false
    }
    val flap = remember { Animatable(0f) }
    LaunchedEffect(opened, replay) {
        if (opened) flap.snapTo(0f)
        flap.animateTo(if (opened) 1f else 0f, tween(600))
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = DiaryCream)) {
        Column(Modifier.fillMaxWidth().drawBehind { drawDiaryGlow() }
            .padding(top = 16.dp, bottom = 8.dp).animateContentSize(tween(600))) {
            Text("情绪关键词回顾", fontSize = 23.sp, fontFamily = DiarySerif,
                fontWeight = FontWeight.Normal, color = DiaryGreen, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Text("把重要的感受轻轻留下", fontSize = 13.sp, fontFamily = DiarySerif,
                color = DiaryGreen.copy(alpha = .86f), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp))
            AnimatedVisibility(opened, enter = expandVertically(tween(600), expandFrom = Alignment.Bottom),
                exit = shrinkVertically(tween(400), shrinkTowards = Alignment.Bottom)) {
                if (words.isNotEmpty()) {
                    ScatteredDiaryWords(words, replay, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                } else {
                    Text(if (review?.hasText == false) "这一页，还在等你写下感受" else "暂时没有可提取的词语",
                        fontFamily = DiarySerif, fontSize = 16.sp, color = DiaryMutedGreen,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val envelopeHeight = maxWidth * .40f
                val envelopeWidth = envelopeHeight / .69f
                Box(Modifier.fillMaxWidth().height(envelopeHeight)) {
                    Box(Modifier.width(envelopeWidth).fillMaxHeight().align(Alignment.Center)
                        .clickable(enabled = available || canGenerate, onClickLabel = if (opened) "收起信笺" else "打开信笺") {
                            if (available) { opened = !opened; if (opened) replay++ }
                            else { waitingToOpen = true; onGenerate() }
                        }) {
                        DiaryPaperEnvelope(flap.value, Modifier.fillMaxSize())
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = envelopeHeight * .10f)
                        .fillMaxWidth(.76f).height(envelopeHeight * .49f)
                        .clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.BottomCenter) {
                        Text(if (generating) "正在整理词语…" else if (opened) "轻触收起信笺" else "轻触，打开这篇日记",
                            Modifier.padding(bottom = 6.dp), fontFamily = DiarySerif,
                            color = DiaryMutedGreen, fontSize = 11.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty) Text(if (hasSavedText) "关键词使用已保存的文字，不必等待整篇日记保存。"
                    else "先点文字卡片的 ✓ 保存文字，就能整理关键词；已有信笺仍可打开。", color = DiaryMutedGreen, fontSize = 12.sp)
                else if (review?.hasText == false) Text("补充一些文字后，就能留下情绪关键词。", color = DiaryMutedGreen, fontSize = 12.sp)
                if (review?.stale == true && !dirty) Text("文字已更新，重新整理后再回看。", color = Color(0xFF997F60), fontSize = 12.sp)
                if (generating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DiaryGreen)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(enabled = canGenerate, onClick = { waitingToOpen = true; onGenerate() }) {
                        Text(if (available) "更新关键词" else "整理关键词", color = DiaryGreen)
                    }
                    if (opened && words.isNotEmpty()) TextButton(onClick = { replay++ }) { Text("再看一次", color = DiaryGreen) }
                    if (review?.body != null) TextButton(onClick = { details = !details }) {
                        Text(if (details) "收起依据" else "分析依据", color = DiaryMutedGreen)
                    }
                }
                if (details) review?.body?.let {
                    if (review.extractionMethod == "device-lexicon+source-phrases") {
                        Text("本次由设备离线整理，结果尚未同步到服务端。", color = DiaryMutedGreen, fontSize = 12.sp)
                    }
                    Text(it, color = DiaryMutedGreen, fontSize = 13.sp, lineHeight = 22.sp)
                }
                Text("词语来自这篇文字 · 大小表示相对重要程度", color = DiaryMutedGreen,
                    fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ScatteredDiaryWords(words: List<DiaryReviewKeyword>, replay: Int, modifier: Modifier = Modifier) {
    val trails = remember(replay) { Animatable(0f) }
    LaunchedEffect(replay) { delay(600); trails.animateTo(1f, tween(900)) }
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.toPx()
        // Width is reduced before measuring so even the rotated text has room for its shadow.
        val wordConstraints = Constraints(maxWidth = (width * .78f).roundToInt())
        val placeables = words.mapIndexed { index, word ->
            subcompose("word_$index") {
                FloatingDiaryWord(word, index,
                    maximumFont = diaryCloudMaximumFont(width / density))
            }.single().measure(wordConstraints)
        }
        val angles = placeables.mapIndexed { index, word ->
            diaryFittingAngle(index, word.width.toFloat(), word.height.toFloat(), width.toFloat(), gap)
        }
        val geometry = arrangeDiaryCloud(width.toFloat(), placeables.mapIndexed { index, word ->
            CloudWordSize(word.width.toFloat(), word.height.toFloat(), angles[index])
        }, gap)
        val height = geometry.height.roundToInt()
        val background = subcompose("trails") {
            DiaryCloudTrails(geometry, trails.value, Modifier.fillMaxSize())
        }.single().measure(Constraints.fixed(width, height))
        // A second composition supplies each measured word's own trajectory without remeasuring text.
        val animated = words.mapIndexed { index, word ->
            val target = geometry.words[index]
            subcompose("animated_$index") {
                val flight = remember(replay, word.text) { Animatable(0f) }
                LaunchedEffect(replay, word.text) {
                    delay(650L + index * 85L)
                    flight.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
                }
                FloatingDiaryWord(word, index,
                    maximumFont = diaryCloudMaximumFont(width / density), angle = angles[index],
                    modifier = Modifier.graphicsLayer {
                        alpha = flight.value
                        scaleX = .22f + .78f * flight.value; scaleY = scaleX
                        val position = diaryFlightPosition(width * .5f,
                            height + diaryEnvelopeMouthOffset(width + 16.dp.toPx()),
                            target.centerX, target.centerY, height.toFloat(),
                            (if (index % 2 == 0) -1 else 1) * width * .14f, flight.value)
                        translationX = position.first - target.centerX
                        translationY = position.second - target.centerY
                    })
            }.single().measure(wordConstraints)
        }
        layout(width, height) {
            background.placeRelative(0, 0)
            animated.forEachIndexed { index, word ->
                val target = geometry.words[index]
                word.placeRelative((target.centerX - word.width / 2).roundToInt(),
                    (target.centerY - word.height / 2).roundToInt())
            }
        }
    }
}

@Composable
private fun FloatingDiaryWord(word: DiaryReviewKeyword, index: Int, maximumFont: Float,
    modifier: Modifier = Modifier, angle: Float = diaryWordAngle(index)) {
    val fontSize = diaryWordFontSize(word.weight, 14f, maximumFont)
    val (paper, ink) = diaryWordColors(word.category, index)
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.padding(4.dp).graphicsLayer { rotationZ = angle }
        .shadow(4.dp, shape, ambientColor = Color(0xFF789B73), spotColor = Color(0xFF789B73))
        .background(Brush.linearGradient(listOf(paper.copy(alpha = .78f), paper)), shape)
        .border(1.dp, Color.White.copy(alpha = .85f), shape)
        .padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(word.text, fontFamily = DiarySerif, fontSize = fontSize.sp,
            color = ink, textAlign = TextAlign.Center, lineHeight = (fontSize * 1.22f).sp)
    }
}
