package com.example.mydiary.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mydiary.data.models.Diary
import com.example.mydiary.data.models.MediaItem
import com.example.mydiary.data.models.TodoItem
import com.example.mydiary.ui.theme.MintGreen
import com.example.mydiary.ui.theme.PaleYellow
import com.example.mydiary.ui.theme.SoftWhite
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onDateSelected: (LocalDate) -> Unit,
    onDiaryClick: (Long, LocalDate) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var diaryToDelete by remember { mutableStateOf<Diary?>(null) }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.deleteSuccess) {
        if (uiState.deleteSuccess) {
            viewModel.resetDeleteSuccess()
        }
    }

    if (showDeleteDialog && diaryToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                diaryToDelete = null
            },
            title = { Text("确认删除") },
            text = { Text("确定要删除这篇日记吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        diaryToDelete?.id?.let(viewModel::deleteDiary)
                        showDeleteDialog = false
                        diaryToDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        diaryToDelete = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MintGreen.copy(alpha = 0.3f),
                            PaleYellow.copy(alpha = 0.3f),
                            SoftWhite
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                MonthHeader(
                    currentMonth = uiState.currentMonth,
                    onPreviousMonth = { viewModel.previousMonth() },
                    onNextMonth = { viewModel.nextMonth() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                WeekDayHeader()

                Spacer(modifier = Modifier.height(8.dp))

                val animationDirection = uiState.monthAnimationDirection
                AnimatedContent(
                    targetState = uiState.currentMonth,
                    transitionSpec = {
                        if (animationDirection >= 0) {
                            (slideInHorizontally { fullWidth -> fullWidth } + fadeIn())
                                .togetherWith(slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut())
                        } else {
                            (slideInHorizontally { fullWidth -> -fullWidth } + fadeIn())
                                .togetherWith(slideOutHorizontally { fullWidth -> fullWidth } + fadeOut())
                        }
                    },
                    label = "month_transition"
                ) { month ->
                    CalendarGrid(
                        currentMonth = month,
                        selectedDate = uiState.selectedDate,
                        datesWithDiary = uiState.datesWithDiary,
                        onDateClick = { date ->
                            viewModel.selectDate(date)
                            onDateSelected(date)
                        },
                        onSwipeLeft = { viewModel.nextMonth() },
                        onSwipeRight = { viewModel.previousMonth() }
                    )
                }

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (uiState.selectedDate != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            text = { Text("日记") }
                        )
                        Tab(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            text = { Text("待办") }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        when (page) {
                            0 -> DiaryListSection(
                                selectedDate = uiState.selectedDate!!,
                                diaries = uiState.selectedDateDiaries,
                                isLoading = uiState.isLoadingDiaries,
                                isDeleting = uiState.isDeleting,
                                onDiaryClick = { diaryId ->
                                    onDiaryClick(diaryId, uiState.selectedDate!!)
                                },
                                onDeleteClick = { diary ->
                                    diaryToDelete = diary
                                    showDeleteDialog = true
                                }
                            )

                            else -> TodoListSection(
                                selectedDate = uiState.selectedDate!!,
                                todos = uiState.selectedDateTodos,
                                isLoading = uiState.isLoadingTodos,
                                isUpdating = uiState.isUpdatingTodo,
                                onToggleDone = { todo -> viewModel.toggleTodoDone(todo) },
                                onDeleteTodo = { todo -> viewModel.deleteTodo(todo.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一个月",
                tint = PaleYellow
            )
        }

        Text(
            text = "${currentMonth.year}年 ${currentMonth.monthValue}月",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        IconButton(onClick = onNextMonth) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一个月",
                tint = PaleYellow
            )
        }
    }
}

@Composable
private fun WeekDayHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val weekDays = listOf("日", "一", "二", "三", "四", "五", "六")
        weekDays.forEach { day ->
            Text(
                text = day,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    datesWithDiary: Set<LocalDate>,
    onDateClick: (LocalDate) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    val dates = remember(currentMonth) {
        generateCalendarDates(currentMonth)
    }

    var dragOffset by remember { mutableStateOf(0f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffset > 100) {
                            onSwipeRight()
                        } else if (dragOffset < -100) {
                            onSwipeLeft()
                        }
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragOffset += dragAmount
                    }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(dates) { dateInfo ->
            CalendarDateCell(
                dateInfo = dateInfo,
                isSelected = dateInfo.date == selectedDate,
                hasDiary = dateInfo.date in datesWithDiary,
                onClick = { onDateClick(dateInfo.date) }
            )
        }
    }
}

@Composable
private fun CalendarDateCell(
    dateInfo: CalendarDateInfo,
    isSelected: Boolean,
    hasDiary: Boolean,
    onClick: () -> Unit
) {
    val today = LocalDate.now()
    val isToday = dateInfo.date == today

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = dateInfo.isCurrentMonth) { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = dateInfo.date.dayOfMonth.toString(),
                fontSize = 16.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !dateInfo.isCurrentMonth -> MaterialTheme.colorScheme.outlineVariant
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onBackground
                }
            )

            if (hasDiary && dateInfo.isCurrentMonth) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                )
            }
        }
    }
}

private data class CalendarDateInfo(
    val date: LocalDate,
    val isCurrentMonth: Boolean
)

private fun generateCalendarDates(yearMonth: YearMonth): List<CalendarDateInfo> {
    val dates = mutableListOf<CalendarDateInfo>()

    val firstDayOfMonth = yearMonth.atDay(1)
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7

    val previousMonth = yearMonth.minusMonths(1)
    val daysInPreviousMonth = previousMonth.lengthOfMonth()
    for (i in (daysInPreviousMonth - firstDayOfWeek + 1)..daysInPreviousMonth) {
        dates.add(
            CalendarDateInfo(
                date = previousMonth.atDay(i),
                isCurrentMonth = false
            )
        )
    }

    val daysInMonth = yearMonth.lengthOfMonth()
    for (day in 1..daysInMonth) {
        dates.add(
            CalendarDateInfo(
                date = yearMonth.atDay(day),
                isCurrentMonth = true
            )
        )
    }

    val nextMonth = yearMonth.plusMonths(1)
    val remainingCells = 42 - dates.size
    for (day in 1..remainingCells) {
        dates.add(
            CalendarDateInfo(
                date = nextMonth.atDay(day),
                isCurrentMonth = false
            )
        )
    }

    return dates
}

@Composable
private fun DiaryListSection(
    selectedDate: LocalDate,
    diaries: List<Diary>,
    isLoading: Boolean,
    isDeleting: Boolean,
    onDiaryClick: (Long) -> Unit,
    onDeleteClick: (Diary) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日的日记",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (isLoading || isDeleting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (diaries.isEmpty()) {
            EmptyState("这一天还没有日记")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(diaries) { diary ->
                    diary.id?.let { diaryId ->
                        DiaryListItem(
                            diary = diary,
                            onClick = { onDiaryClick(diaryId) },
                            onDeleteClick = { onDeleteClick(diary) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoListSection(
    selectedDate: LocalDate,
    todos: List<TodoItem>,
    isLoading: Boolean,
    isUpdating: Boolean,
    onToggleDone: (TodoItem) -> Unit,
    onDeleteTodo: (TodoItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日的待办",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (isLoading || isUpdating) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (todos.isEmpty()) {
            EmptyState("这一天还没有待办")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(todos) { todo ->
                    TodoListItem(
                        todo = todo,
                        onToggleDone = { onToggleDone(todo) },
                        onDelete = { onDeleteTodo(todo) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiaryListItem(
    diary: Diary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diary.title ?: "无标题",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    diary.createdAt?.let { createdAt ->
                        Text(
                            text = DateTimeFormatter.ofPattern("HH:mm")
                                .format(createdAt.atZone(java.time.ZoneId.systemDefault())),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除日记",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            val contentPreview = getContentPreview(diary)
            if (contentPreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = contentPreview,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (diary.mediaItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${diary.mediaItems.size} 个媒体项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TodoListItem(
    todo: TodoItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = todo.isDone,
                onCheckedChange = { onToggleDone() }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = todo.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (todo.isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                if (!todo.note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = todo.note,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除待办",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun getContentPreview(diary: Diary): String {
    val textItem = diary.mediaItems.firstOrNull { it is MediaItem.Text }
    if (textItem is MediaItem.Text) {
        return textItem.content
    }

    if (diary.mediaItems.isNotEmpty()) {
        val types = diary.mediaItems.map { item ->
            when (item) {
                is MediaItem.Image -> "图片"
                is MediaItem.Audio -> "语音"
                is MediaItem.Video -> "视频"
                else -> ""
            }
        }.filter { it.isNotEmpty() }.distinct()

        return "包含: ${types.joinToString("、")}" 
    }

    return ""
}
