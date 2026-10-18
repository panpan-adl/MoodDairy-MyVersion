package com.example.mydiary.ui.calendar

import com.example.mydiary.ui.components.PageLazyColumn

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.mydiary.ui.theme.PageTopBackground
import com.example.mydiary.ui.theme.PageMiddleBackground
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.repeatOnLifecycle
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
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // The calendar stays on the navigation back stack while a diary is edited.
    // Reload on return instead of displaying the previous title/media snapshot.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshRecords()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A local backend can take time to restart. Recover while this page is visible,
    // and stop polling as soon as it leaves the foreground.
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var retryDelay = 3_000L
            while (true) {
                kotlinx.coroutines.delay(retryDelay)
                viewModel.retryFailedReads()
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

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
                            PageTopBackground,
                            PageMiddleBackground,
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonthHeader(
                        currentMonth = uiState.currentMonth,
                        onPreviousMonth = { viewModel.previousMonth() },
                        onNextMonth = { viewModel.nextMonth() }
                    )
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = .68f), Color(0xFFF6FAF3).copy(alpha = .86f))))
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("一日一格，收藏日常", Modifier.weight(1f), color = Color(0xFF39856F), fontSize = 11.sp)
                            val recordedDays = (uiState.datesWithDiary + uiState.datesWithTodos)
                                .count { YearMonth.from(it) == uiState.currentMonth }
                            Text(if (uiState.isLoading && recordedDays == 0) "正在整理…" else "$recordedDays 天有记录",
                                color = Color(0xFF39856F), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        WeekDayHeader()
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
                                datesWithDiary = uiState.datesWithDiary + uiState.datesWithTodos,
                                onDateClick = { date ->
                                    if (viewModel.selectDate(date)) onDateSelected(date)
                                },
                                onSwipeLeft = { viewModel.nextMonth() },
                                onSwipeRight = { viewModel.previousMonth() }
                            )
                        }

                    }
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
                uiState.calendarLoadError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    TextButton(onClick = viewModel::refreshRecords) { Text("重新加载日历") }
                }

                if (uiState.selectedDate != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    RecordListSection(
                                selectedDate = uiState.selectedDate!!,
                                diaries = uiState.selectedDateDiaries,
                                todos = uiState.selectedDateTodos,
                                isLoadingTodos = uiState.isLoadingTodos,
                                isUpdatingTodo = uiState.isUpdatingTodo,
                                todoLoadError = uiState.todoLoadError,
                                onToggleDone = viewModel::toggleTodoDone,
                                onDeleteTodo = { viewModel.deleteTodo(it.id) },
                                isLoading = uiState.isLoadingDiaries,
                                isDeleting = uiState.isDeleting,
                                loadError = uiState.diaryLoadError,
                                onRetry = viewModel::reloadSelectedDateData,
                                onDiaryClick = { diaryId ->
                                    onDiaryClick(diaryId, uiState.selectedDate!!)
                                },
                                onDeleteClick = { diary ->
                                    diaryToDelete = diary
                                    showDeleteDialog = true
                                }
                            )
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("日常手账 · 收藏每一天", fontSize = 11.sp, color = Color(0xFF39856F), letterSpacing = 1.sp)
            Text("${currentMonth.year}年 ${currentMonth.monthValue}月", fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onPreviousMonth,
                modifier = Modifier.size(48.dp)) {
                Box(Modifier.size(34.dp).background(Color.White.copy(alpha = .52f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一个月", modifier = Modifier.size(20.dp), tint = Color(0xFF39856F))
                }
            }
            IconButton(onClick = onNextMonth,
                modifier = Modifier.size(48.dp)) {
                Box(Modifier.size(34.dp).background(Color.White.copy(alpha = .52f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一个月", modifier = Modifier.size(20.dp), tint = Color(0xFF39856F))
                }
            }
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
                fontSize = 11.sp,
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

    Column(Modifier.fillMaxWidth().pointerInput(onSwipeLeft, onSwipeRight) {
        detectHorizontalDragGestures(
            onDragCancel = { dragOffset = 0f },
            onDragEnd = {
                if (dragOffset > 100) onSwipeRight()
                else if (dragOffset < -100) onSwipeLeft()
                dragOffset = 0f
            },
            onHorizontalDrag = { _, dragAmount -> dragOffset += dragAmount }
        )
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { dateInfo ->
                    Box(Modifier.weight(1f)) {
                        CalendarDateCell(dateInfo, dateInfo.date == selectedDate,
                            dateInfo.date in datesWithDiary, onClick = { onDateClick(dateInfo.date) })
                    }
                }
            }
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

    val shape = RoundedCornerShape(10.dp)
    val ink = Color(0xFF39856F)
    Box(Modifier.aspectRatio(1.16f).testTag("calendar-date-${dateInfo.date}")
        .clip(shape).background(when {
            isSelected -> ink
            !dateInfo.isCurrentMonth -> Color.Transparent
            hasDiary -> Color(0xFFC9E9DC)
            else -> Color.White.copy(alpha = .45f)
        }).border(if (isToday) 1.dp else 0.dp, if (isToday) ink.copy(alpha = .6f) else Color.Transparent, shape)
        .clickable(enabled = dateInfo.isCurrentMonth || dateInfo.date.isAfter(today)) { onClick() }
        .semantics { contentDescription = "${dateInfo.date}${if (isToday) "，今天" else ""}${if (hasDiary) "，有记录" else ""}${if (isSelected) "，已选中" else ""}" },
        contentAlignment = Alignment.Center) {
        Text(dateInfo.date.dayOfMonth.toString(), fontSize = 13.sp,
            fontWeight = if (isToday || isSelected || hasDiary) FontWeight.Medium else FontWeight.Normal,
            color = when {
                isSelected -> Color.White
                !dateInfo.isCurrentMonth -> Color(0xFFB7C6BE)
                hasDiary || isToday -> Color(0xFF426456)
                else -> Color(0xFF71897D)
            })
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
private fun RecordListSection(
    selectedDate: LocalDate,
    diaries: List<Diary>,
    todos: List<TodoItem>,
    isLoading: Boolean,
    isLoadingTodos: Boolean,
    isDeleting: Boolean,
    isUpdatingTodo: Boolean,
    loadError: String?,
    todoLoadError: String?,
    onRetry: () -> Unit,
    onDiaryClick: (Long) -> Unit,
    onDeleteClick: (Diary) -> Unit,
    onToggleDone: (TodoItem) -> Unit,
    onDeleteTodo: (TodoItem) -> Unit
) {
    PageLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "diaryHeading") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(5.dp).background(Color(0xFF39856F), CircleShape))
                    Text("日记", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF39856F))
                    Text("${diaries.size} 篇", fontSize = 11.sp, color = Color(0xFF6D897B))
                }
            }
        }
        if (loadError != null) item(key = "diaryError") {
            Text(loadError, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            TextButton(onClick = onRetry) { Text("重新加载日记") }
        }
        if ((isLoading && diaries.isEmpty()) || isDeleting) item(key = "diaryLoading") {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (!isLoading && !isDeleting && diaries.isEmpty() && loadError == null) item(key = "emptyDiary") {
            Text("这一天还没有日记", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        items(diaries, key = { "diary_${it.id}" }) { diary ->
            diary.id?.let { id ->
                DiaryListItem(diary, onClick = { onDiaryClick(id) }, onDeleteClick = { onDeleteClick(diary) })
            }
        }
        item(key = "todoHeading") {
            Row(Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(5.dp).background(Color(0xFF39856F), CircleShape))
                Text("待办", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF39856F))
                Text("${todos.size} 项", fontSize = 11.sp, color = Color(0xFF6D897B))
            }
        }
        if (todoLoadError != null) item(key = "todoError") {
            Text(todoLoadError, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            TextButton(onClick = onRetry) { Text("重新加载待办") }
        }
        if (isLoadingTodos && todos.isEmpty()) item(key = "todoLoading") {
            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (!isLoadingTodos && todos.isEmpty() && todoLoadError == null) item(key = "emptyTodo") {
            Text("这一天还没有待办", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        items(todos, key = { "todo_${it.id}" }) { todo ->
            TodoListItem(todo, enabled = !isUpdatingTodo,
                onToggleDone = { onToggleDone(todo) }, onDelete = { onDeleteTodo(todo) })
        }
        item(key = "recordsBottom") { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun DiaryListItem(
    diary: Diary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            diary.createdAt?.let { createdAt ->
                Text(DateTimeFormatter.ofPattern("HH:mm").format(createdAt.atZone(java.time.ZoneId.systemDefault())),
                    fontSize = 11.sp, color = Color(0xFF6D897B), letterSpacing = .5.sp)
                Text("  /  ", fontSize = 11.sp, color = Color(0xFFABC4B7))
            }
            Text(diary.title?.takeIf { it.isNotBlank() } ?: "无标题",
                modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2C493E), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除日记",
                    tint = Color(0xFF6D897B), modifier = Modifier.size(18.dp))
            }
        }
        val preview = getContentPreview(diary)
        if (preview.isNotEmpty()) {
            Text(preview, modifier = Modifier.padding(bottom = 12.dp, end = 12.dp),
                fontSize = 14.sp, lineHeight = 23.sp, color = Color(0xFF6B8176),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val attachments = diary.mediaItems.filterNot { it is MediaItem.Text }
        if (attachments.isNotEmpty()) {
            val labels = buildList {
                val images = attachments.count { it is MediaItem.Image }
                val audio = attachments.count { it is MediaItem.Audio }
                val videos = attachments.count { it is MediaItem.Video }
                if (images > 0) add("$images 张照片")
                if (audio > 0) add("$audio 段语音")
                if (videos > 0) add("$videos 段视频")
            }
            Text(labels.joinToString(" · "), fontSize = 11.sp, color = Color(0xFF39856F),
                modifier = Modifier.padding(bottom = 14.dp))
        }
        HorizontalDivider(color = Color(0xFF39856F).copy(alpha = .14f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

@Composable
private fun TodoListItem(
    todo: TodoItem,
    enabled: Boolean = true,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = todo.isDone, enabled = enabled, onCheckedChange = { onToggleDone() },
                colors = androidx.compose.material3.CheckboxDefaults.colors(
                    checkedColor = Color(0xFF39856F), uncheckedColor = Color(0xFF8DA498)))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(todo.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (todo.isDone) Color(0xFF8DA498) else Color(0xFF2C493E),
                    textDecoration = if (todo.isDone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                if (!todo.note.isNullOrBlank()) {
                    Text(todo.note, modifier = Modifier.padding(top = 4.dp), fontSize = 12.sp,
                        color = Color(0xFF6B8176), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = "删除待办",
                    tint = Color(0xFF6D897B), modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = Color(0xFF39856F).copy(alpha = .10f), modifier = Modifier.padding(horizontal = 4.dp))
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


