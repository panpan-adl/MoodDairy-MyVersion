package com.example.mydiary.ui.test

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.GlobalOption
import com.example.mydiary.data.models.TestAnswer
import com.example.mydiary.data.models.TestData
import com.example.mydiary.data.models.TestOption
import com.example.mydiary.data.models.TestQuestion
import com.example.mydiary.ui.theme.DividerColor
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.Primary
import com.example.mydiary.ui.theme.SoftWhite
import com.example.mydiary.ui.theme.Surface
import com.example.mydiary.ui.theme.TextPrimary
import com.example.mydiary.ui.theme.TextSecondary
import com.example.mydiary.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    testId: String,
    onNavigateBack: () -> Unit,
    onNavigateToResult: (String) -> Unit,
    viewModel: TestViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(testId) {
        viewModel.loadTest(testId)
    }

    LaunchedEffect(uiState.result) {
        if (uiState.result != null) {
            onNavigateToResult(testId)
        }
    }

    Scaffold(
        topBar = {
            TestTopBar(
                testName = uiState.testData?.testName ?: "加载中...",
                progress = uiState.progress,
                answeredCount = uiState.answeredCount,
                totalQuestions = uiState.totalQuestions,
                onNavigateBack = onNavigateBack,
            )
        },
        bottomBar = {
            TestBottomBar(
                isComplete = uiState.isComplete,
                isLoading = uiState.isLoading,
                onSubmit = viewModel::submitTest,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.2f),
                            SoftWhite,
                        ),
                    ),
                )
                .padding(paddingValues),
        ) {
            when {
                uiState.isLoading && uiState.testData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Primary,
                    )
                }

                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "加载失败",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadTest(testId) }) {
                            Text("重试")
                        }
                    }
                }

                uiState.testData != null -> {
                    TestContent(
                        testData = uiState.testData!!,
                        answers = uiState.answers,
                        onAnswer = { questionId, optionId, scores ->
                            viewModel.answerQuestion(questionId, optionId, scores)
                        },
                        onTextAnswer = { questionId, answerText ->
                            viewModel.answerQuestion(questionId, answerText)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestTopBar(
    testName: String,
    progress: Float,
    answeredCount: Int,
    totalQuestions: Int,
    onNavigateBack: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    Column {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = testName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$answeredCount/$totalQuestions",
                        fontSize = 14.sp,
                        color = Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = Primary,
            trackColor = Primary.copy(alpha = 0.2f),
        )
    }
}

@Composable
private fun TestBottomBar(
    isComplete: Boolean,
    isLoading: Boolean,
    onSubmit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = Surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Button(
                onClick = onSubmit,
                enabled = isComplete && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    disabledContainerColor = Primary.copy(alpha = 0.3f),
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (isComplete) "提交测试" else "请先完成必答题",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestContent(
    testData: TestData,
    answers: Map<Int, TestAnswer>,
    onAnswer: (Int, String, Map<String, Int>) -> Unit,
    onTextAnswer: (Int, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
            ) {
                Text(
                    text = testData.testDescription,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp),
                    lineHeight = 22.sp,
                )
            }
        }

        itemsIndexed(testData.questions) { index, question ->
            QuestionCard(
                index = index + 1,
                question = question,
                globalOptions = testData.globalOptions,
                selectedOptionId = answers[question.questionId]?.selectedOptionId,
                textAnswer = answers[question.questionId]?.selectedOptionId.orEmpty(),
                onAnswer = { optionId, scores ->
                    onAnswer(question.questionId, optionId, scores)
                },
                onTextAnswer = { answerText ->
                    onTextAnswer(question.questionId, answerText)
                },
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuestionCard(
    index: Int,
    question: TestQuestion,
    globalOptions: List<GlobalOption>?,
    selectedOptionId: String?,
    textAnswer: String,
    onAnswer: (String, Map<String, Int>) -> Unit,
    onTextAnswer: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = index.toString(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = question.questionText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    lineHeight = 24.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (question.questionType == "text_input") {
                OutlinedTextField(
                    value = textAnswer,
                    onValueChange = { updated ->
                        val limited = question.maxLength?.let { updated.take(it) } ?: updated
                        onTextAnswer(limited)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(question.placeholder ?: "请输入你的回答")
                    },
                    minLines = 4,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = DividerColor,
                    ),
                    supportingText = {
                        when {
                            question.maxLength != null -> Text("${textAnswer.length}/${question.maxLength}")
                            !question.required -> Text("本题可选")
                        }
                    },
                )
            } else {
                val options = question.options ?: globalOptions?.map { option ->
                    TestOption(
                        optionId = option.optionId.toString(),
                        optionText = option.optionText,
                        scores = emptyMap(),
                        score = option.score,
                    )
                }.orEmpty()
                val selectedOptionIds = selectedOptionId
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    .orEmpty()

                options.forEach { option ->
                    OptionItem(
                        optionText = option.optionText,
                        isSelected = selectedOptionIds.contains(option.optionId),
                        isMultiple = question.allowMultiple,
                        onClick = {
                            if (question.allowMultiple) {
                                val maxSelect = (question.maxSelect ?: Int.MAX_VALUE).coerceAtLeast(1)
                                val mutableSelected = selectedOptionIds.toMutableSet()
                                if (mutableSelected.contains(option.optionId)) {
                                    mutableSelected.remove(option.optionId)
                                } else if (mutableSelected.size < maxSelect) {
                                    mutableSelected.add(option.optionId)
                                }

                                val mergedScores = mutableMapOf<String, Int>()
                                mutableSelected.forEach { selectedId ->
                                    options.firstOrNull { it.optionId == selectedId }
                                        ?.scores
                                        ?.orEmpty()
                                        ?.forEach { (code, score) ->
                                            mergedScores[code] = (mergedScores[code] ?: 0) + score
                                        }
                                }

                                onAnswer(
                                    mutableSelected.joinToString(","),
                                    mergedScores,
                                )
                            } else {
                                onAnswer(option.optionId, option.scores.orEmpty())
                            }
                        },
                    )

                    if (option != options.last()) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            if (question.isCritical) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF3E0),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "这道题很重要，请认真作答",
                            fontSize = 12.sp,
                            color = Color(0xFFE65100),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionItem(
    optionText: String,
    isSelected: Boolean,
    isMultiple: Boolean = false,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) Primary.copy(alpha = 0.1f) else Color.Transparent
    val borderColor = if (isSelected) Primary else DividerColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(
                    width = if (isSelected) {
                        if (isMultiple) 4.dp else 6.dp
                    } else {
                        2.dp
                    },
                    color = if (isSelected) Primary else TextTertiary,
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = optionText,
            fontSize = 15.sp,
            color = if (isSelected) Primary else TextPrimary,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = Primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
