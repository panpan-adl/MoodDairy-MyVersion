package com.example.mydiary.ui.agent

import com.example.mydiary.ui.components.PageLazyColumn

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydiary.data.models.AgentTask
import com.example.mydiary.data.models.AgentTaskDraft
import com.example.mydiary.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val domainNames = linkedMapOf("work" to "工作", "study" to "学习", "life" to "生活")
private enum class TodaySection(val title: String, val icon: ImageVector) {
    RECORD("记录与计划", Icons.Outlined.EditNote),
    TASKS("今日任务", Icons.Outlined.Checklist),
    REVIEW("晚间复盘", Icons.Outlined.NightsStay)
}
internal data class TodayActions(
    val refresh: () -> Unit = {}, val record: (String) -> Unit = {},
    val energy: (String) -> Unit = {}, val minutes: (Int) -> Unit = {},
    val generatePlan: () -> Unit = {}, val manualPlan: () -> Unit = {},
    val editDraft: (Int, AgentTaskDraft) -> Unit = { _, _ -> }, val addDraft: () -> Unit = {},
    val removeDraft: (Int) -> Unit = {}, val adopt: () -> Unit = {},
    val editTask: (AgentTask, AgentTaskDraft) -> Unit = { _, _ -> },
    val toggle: (AgentTask) -> Unit = {}, val postpone: (AgentTask) -> Unit = {},
    val reflection: (String) -> Unit = {}, val generateReview: () -> Unit = {},
    val review: (String) -> Unit = {}, val saveReview: () -> Unit = {}, val clearNotice: () -> Unit = {},
    val assistant: () -> Unit = {}, val records: () -> Unit = {}, val openDiary: (Long) -> Unit = {}
)
@Composable
fun TodayScreen(onOpenAssistant: () -> Unit, onOpenRecords: () -> Unit, onOpenDiary: (Long) -> Unit,
                viewModel: TodayViewModel = hiltViewModel(), showTitle: Boolean = true) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    TodayDashboard(state, TodayActions(
        refresh = viewModel::refresh, record = viewModel::updateRecord, energy = viewModel::updateEnergy,
        minutes = viewModel::updateMinutes, generatePlan = viewModel::generatePlan,
        manualPlan = viewModel::manualPlan, editDraft = viewModel::editDraft, addDraft = viewModel::addDraft,
        removeDraft = viewModel::removeDraft, adopt = viewModel::adopt, editTask = viewModel::updateTask,
        toggle = viewModel::toggle, postpone = viewModel::postpone, reflection = viewModel::updateReflection,
        generateReview = viewModel::generateReview, review = viewModel::updateReview, saveReview = viewModel::saveReview,
        clearNotice = viewModel::clearNotice, assistant = onOpenAssistant, records = onOpenRecords,
        openDiary = onOpenDiary), showTitle = showTitle)
}
@Composable
internal fun TodayDashboard(state: TodayState, actions: TodayActions, showTitle: Boolean = true) {
    var selected by rememberSaveable { mutableStateOf(TodaySection.RECORD) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var editTask by remember { mutableStateOf<AgentTask?>(null) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editor by remember { mutableStateOf<AgentTaskDraft?>(null) }
    var awaitingAdopt by remember { mutableStateOf(false) }
    var previousBusy by rememberSaveable { mutableStateOf(state.busy) }
    val recordScroll = rememberLazyListState()
    val tasksScroll = rememberLazyListState()
    val reviewScroll = rememberLazyListState()
    val remaining = state.tasks.filterNot { it.isDone }.sumOf { it.minutes }
    val completed = state.tasks.count { it.isDone }
    val budget = if (state.energy == "low") minOf(state.availableMinutes, 90) else state.availableMinutes
    val notice = state.error ?: state.message
    val canOperate = !state.busy && state.loaded
    LaunchedEffect(state.busy, state.plan) {
        val operationFinished = previousBusy && !state.busy
        previousBusy = state.busy
        if (awaitingAdopt && !state.busy) {
            if (state.plan == null && state.error == null) selected = TodaySection.TASKS
            awaitingAdopt = false
        }
        if (state.plan != null && operationFinished && selected == TodaySection.RECORD) {
            recordScroll.animateScrollToItem(2 + if (notice != null) 1 else 0)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (showTitle) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showTitle) Text("今天", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                val dateLabel = runCatching { LocalDate.parse(state.date).format(DateTimeFormatter.ofPattern("M月d日  EEEE", Locale.CHINA)) }.getOrDefault(state.date)
                Text(dateLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PrimaryDark)
            }
            IconButton(onClick = actions.refresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新今日任务", tint = PrimaryDark) }
        }
        TodayOverview(state, completed, remaining) { domain ->
            filter = domain
            selected = TodaySection.TASKS
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.8f)).selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TodaySection.entries.forEach { section ->
                val active = selected == section
                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (active) PrimaryDark else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { selected = section }).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(section.icon, null, tint = if (active) Color.White else TextSecondary, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp))
                    Text(section.title, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) Color.White else TextSecondary, maxLines = 1)
                }
            }
        }
        PageLazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(),
            state = when (selected) { TodaySection.RECORD -> recordScroll; TodaySection.TASKS -> tasksScroll; TodaySection.REVIEW -> reviewScroll },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = PrimaryVariant, trackColor = MintGreen.copy(alpha = 0.2f)) }
            if (notice != null) item(key = "notice") {
                Surface(shape = RoundedCornerShape(12.dp), color = if (state.error != null) Error.copy(alpha = 0.12f) else MintGreen.copy(alpha = 0.25f)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, Modifier.weight(1f), fontSize = 13.sp, color = TextPrimary)
                        IconButton(onClick = actions.clearNotice) { Icon(Icons.Outlined.Close, "关闭提示", tint = TextSecondary) }
                    }
                }
            }
            when (selected) {
                TodaySection.RECORD -> {
                    item(key = "record") {
                        AgentPanel("随手记录", "把脑海里的待办，轻轻放在这里", Icons.Outlined.EditNote) {
                            AgentTextField(state.record, actions.record, "今天，我想……", enabled = !state.busy,
                                placeholder = "例如：完成工作方案、复习英语，晚饭后散步。", minLines = 2)
                        }
                    }
                    item(key = "planning") {
                        AgentPanel("安排今天", "按你的精力和时间，给出可以执行的下一步", Icons.Outlined.AutoAwesome) {
                            Text("现在的精力", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("low" to "有些疲惫", "normal" to "还不错", "high" to "精力充足").forEachIndexed { index, (value, label) ->
                                    EnergyOption(label, index + 1, state.energy == value, Modifier.weight(1f), !state.busy) { actions.energy(value) }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("可安排时间", fontSize = 13.sp, color = TextPrimary); Spacer(Modifier.weight(1f))
                                Text("${state.availableMinutes} 分钟", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                            }
                            Slider(state.availableMinutes.toFloat(), { actions.minutes((it.toInt() / 15 * 15).coerceIn(15, 480)) },
                                valueRange = 15f..480f, steps = 30, enabled = !state.busy,
                                colors = SliderDefaults.colors(thumbColor = PrimaryVariant, activeTrackColor = PrimaryVariant, inactiveTrackColor = MintGreen.copy(alpha = 0.3f)))
                            TimeBudget(remaining, budget)
                            if (state.energy == "low") Text("精力低时先按最多 90 分钟安排，留出休息空间。", fontSize = 12.sp, color = TextSecondary)
                            PrimaryAction("生成今日计划", Icons.Outlined.AutoAwesome, canOperate && state.record.isNotBlank(), actions.generatePlan)
                            OutlinedButton(onClick = actions.manualPlan, enabled = canOperate && state.plan == null,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryDark), border = BorderStroke(1.dp, MintGreen)) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("手动添加任务")
                            }
                        }
                    }
                    state.plan?.let { plan ->
                        item(key = "plan") {
                            AgentPanel("计划预览", "可以调整任务；确认后才会加入今日任务", Icons.Outlined.FactCheck) {
                                Surface(shape = RoundedCornerShape(12.dp), color = PaleYellow.copy(alpha = 0.45f)) {
                                    Text(plan.suggestion, Modifier.fillMaxWidth().padding(12.dp), fontSize = 13.sp, lineHeight = 20.sp, color = TextPrimary)
                                }
                                plan.tasks.forEachIndexed { index, draft ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = SoftWhite, border = BorderStroke(1.dp, DividerColor)) {
                                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(draft.title.ifBlank { "填写任务名称" }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                                DomainBadge(draft.domain, draft.minutes)
                                                if (draft.nextStep.isNotBlank()) Text(draft.nextStep, fontSize = 12.sp, lineHeight = 18.sp, color = TextSecondary)
                                            }
                                            Column {
                                                IconButton(onClick = { editTask = null; editIndex = index; editor = draft }, enabled = !state.busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Edit, "编辑计划任务", tint = PrimaryDark, modifier = Modifier.size(18.dp)) }
                                                IconButton(onClick = { actions.removeDraft(index) }, enabled = !state.busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, "移除计划任务", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                                            }
                                        }
                                    }
                                }
                                val draftMinutes = plan.tasks.sumOf { it.minutes }
                                Text("新计划 $draftMinutes 分钟 · 已有待办 $remaining 分钟 · 预算 $budget 分钟", fontSize = 12.sp,
                                    color = if (draftMinutes + remaining > budget) Error else TextSecondary)
                                if (draftMinutes + remaining > budget) Text("已超出时间预算，可以缩小任务或延期；确认后仍按你的安排保存。", fontSize = 12.sp, color = Error)
                                OutlinedButton(actions.addDraft, enabled = !state.busy && plan.tasks.size < 6, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryDark)) { Text("继续添加任务") }
                                PrimaryAction("确认加入今日任务", Icons.Outlined.CheckCircle,
                                    !state.busy && plan.tasks.isNotEmpty() && plan.tasks.all { it.title.isNotBlank() }) { awaitingAdopt = true; actions.adopt() }
                            }
                        }
                    }
                }
                TodaySection.TASKS -> {
                    item(key = "taskFilters") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("今日任务", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("按自己的节奏，一次完成一件小事。", fontSize = 12.sp, color = TextSecondary)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (linkedMapOf("all" to "全部") + domainNames).forEach { (key, label) ->
                                    SelectionPill(label, filter == key, Modifier.weight(1f)) { filter = key }
                                }
                            }
                        }
                    }
                    val visible = state.tasks.filter { filter == "all" || it.domain == filter }.sortedBy { it.isDone }
                    val priorities = state.tasks.filterNot { it.isDone }.take(3).map { it.id }
                    if (visible.isEmpty()) item(key = "emptyTasks") {
                        AgentPanel {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.TaskAlt, null, tint = PrimaryVariant, modifier = Modifier.size(42.dp))
                                Text(if (state.loaded) "${if (filter == "all") "今天" else domainNames[filter]}还没有任务" else "任务尚未加载",
                                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text(if (state.loaded) "先做一份计划，或添加一件想完成的小事。" else "请刷新重试，稍后再安排今天。", fontSize = 12.sp, color = TextSecondary)
                                PrimaryAction(if (state.loaded) "去安排今天" else "重新加载", Icons.Outlined.Add, !state.busy) {
                                    if (state.loaded) selected = TodaySection.RECORD else actions.refresh()
                                }
                            }
                        }
                    }
                    items(visible, key = { "task_${it.id}" }) { task ->
                        AgentPanel {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(task.isDone, { actions.toggle(task) }, enabled = !state.busy,
                                    colors = CheckboxDefaults.colors(checkedColor = PrimaryVariant), modifier = Modifier.size(44.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(task.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                            color = if (task.isDone) TextSecondary else TextPrimary,
                                            textDecoration = if (task.isDone) TextDecoration.LineThrough else TextDecoration.None)
                                        if (task.id in priorities) Text("重点", Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(PaleYellow.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 10.sp, color = PrimaryDark)
                                    }
                                    DomainBadge(task.domain, task.minutes)
                                    if (task.nextStep.isNotBlank()) Text("下一步：${task.nextStep}", fontSize = 13.sp, lineHeight = 20.sp, color = TextSecondary)
                                }
                                IconButton(onClick = { editTask = task; editIndex = null; editor = AgentTaskDraft(task.title, task.domain, task.minutes, task.nextStep) },
                                    enabled = !state.busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Edit, "编辑任务", tint = PrimaryDark, modifier = Modifier.size(18.dp)) }
                            }
                            if (!task.isDone) {
                                HorizontalDivider(color = DividerColor)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { actions.postpone(task) }, enabled = !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)) { Text("延期到明天", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
                TodaySection.REVIEW -> {
                    item(key = "reflection") {
                        AgentPanel("回顾今天", "结合日记与完成情况，整理收获和明天的一小步", Icons.Outlined.NightsStay) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PaleYellow.copy(alpha = 0.4f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircle, null, tint = PrimaryDark, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text("今天完成 $completed 项，还有 ${state.tasks.size - completed} 项待办。", fontSize = 13.sp, color = TextPrimary)
                            }
                            AgentTextField(state.reflection, actions.reflection, "今天有什么收获或感受？", enabled = !state.busy,
                                placeholder = "例如：完成了提纲，很有成就感；英语还需要多练习。", minLines = 3)
                            PrimaryAction("生成复盘草稿", Icons.Outlined.AutoAwesome, canOperate && state.savedReviewId == null, actions.generateReview)
                            Text("生成的是草稿，修改并保存后才会写入日记。", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                    if (state.review.isNotBlank()) item(key = "reviewDraft") {
                        AgentPanel(if (state.savedReviewId == null) "复盘草稿" else "复盘已保存",
                            if (state.savedReviewId == null) "检查内容，留下属于自己的表达" else "已保存为私密日记，随时可以回看", Icons.Outlined.MenuBook) {
                            AgentTextField(state.review, actions.review, "复盘内容", enabled = !state.busy && state.savedReviewId == null, minLines = 5, maxLines = 14)
                            if (state.savedReviewId == null) PrimaryAction("保存为私密日记", Icons.Outlined.Lock, !state.busy && state.review.isNotBlank(), actions.saveReview)
                            else PrimaryAction("查看这篇日记", Icons.Outlined.MenuBook, true) { actions.openDiary(state.savedReviewId) }
                        }
                    }
                }
            }
        }
    }
    editor?.let { draft ->
        TaskEditor(draft, onDismiss = { editor = null }, onSave = { edited ->
            editTask?.let { actions.editTask(it, edited) } ?: editIndex?.let { actions.editDraft(it, edited) }
            editor = null
        })
    }
}

@Composable
private fun TodayOverview(state: TodayState, completed: Int, remaining: Int, onDomain: (String) -> Unit) {
    val progress by animateFloatAsState(
        if (state.tasks.isEmpty()) 0f else completed.toFloat() / state.tasks.size, label = "todayProgress")
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFDDEDE4))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(88.dp).semantics(mergeDescendants = true) {
                    contentDescription = "今日完成进度，已完成 $completed 项，共 ${state.tasks.size} 项"
                }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                        drawArc(Color(0xFFEDF5EF), -90f, 360f, false, style = Stroke(7.dp.toPx()))
                        if (progress > 0f) drawArc(PrimaryVariant, -90f, 360f * progress, false,
                            style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(progress * 100).toInt()}%", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PrimaryDark)
                        Text("已完成", fontSize = 10.sp, color = TextSecondary)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.WbSunny, null, tint = Color(0xFFC49A51), modifier = Modifier.size(16.dp))
                        Text("今日概览", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Text(if (state.tasks.isEmpty()) "从一件小事开始" else "$completed / ${state.tasks.size} 已完成 · 按自己的节奏来",
                        fontSize = 11.sp, color = TextSecondary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SummaryStat("待完成", (state.tasks.size - completed).toString(), "项")
                        SummaryStat("待办用时", remaining.toString(), "分钟")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                domainNames.forEach { (domain, label) ->
                    val count = state.tasks.count { it.domain == domain && !it.isDone }
                    val total = state.tasks.count { !it.isDone }.coerceAtLeast(1)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(domainColor(domain).copy(alpha = 0.09f))
                        .clickable { onDomain(domain) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(domainIcon(domain), null, tint = domainColor(domain), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, fontSize = 11.sp, color = TextPrimary)
                            Spacer(Modifier.weight(1f))
                            Text("$count", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        LinearProgressIndicator(progress = { count.toFloat() / total },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)),
                            color = domainColor(domain), trackColor = domainColor(domain).copy(alpha = 0.1f), gapSize = 0.dp,
                            drawStopIndicator = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.width(3.dp))
            Text(unit, Modifier.padding(bottom = 3.dp), fontSize = 10.sp, color = TextSecondary)
        }
        Text(label, fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun EnergyOption(label: String, level: Int, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val accent = when (level) { 1 -> Color(0xFF819BB5); 2 -> Color(0xFFB2955D); else -> PrimaryDark }
    Surface(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = if (selected) accent.copy(alpha = 0.09f) else SoftWhite,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.55f) else Color(0xFFE8EEEA))) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                repeat(3) { index ->
                    Box(Modifier.width(8.dp).height((9 + index * 5).dp).clip(RoundedCornerShape(2.dp))
                        .background(if (index < level) accent.copy(alpha = if (enabled) 0.85f else 0.3f) else accent.copy(alpha = 0.12f)))
                }
            }
            Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (!enabled) TextDisabled else if (selected) accent else TextSecondary)
        }
    }
}

@Composable
private fun TimeBudget(remaining: Int, budget: Int) {
    val overBudget = remaining > budget
    val accent = if (overBudget) Color(0xFFB17A48) else PrimaryVariant
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F8F5)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("已有待办 $remaining 分钟", Modifier.weight(1f), fontSize = 11.sp, color = TextPrimary)
            Text(if (overBudget) "超出 ${remaining - budget} 分钟" else "余量 ${budget - remaining} 分钟", fontSize = 11.sp, color = accent)
        }
        LinearProgressIndicator(progress = { (remaining.toFloat() / budget.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
            color = accent, trackColor = Color(0xFFE2EEE6), gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable
private fun AgentPanel(title: String? = null, subtitle: String? = null, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE4EEE8)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MintGreen.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = PrimaryDark, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        if (subtitle != null) Text(subtitle, fontSize = 11.sp, lineHeight = 17.sp, color = TextSecondary)
                    }
                }
            }
            content()
        }
    }
}
@Composable
private fun PrimaryAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark, contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun SelectionPill(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(10.dp)).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(10.dp), color = if (selected) MintGreen.copy(alpha = 0.4f) else SoftWhite,
        border = BorderStroke(1.dp, if (selected) MintGreen else DividerColor)) {
        Text(label, Modifier.padding(vertical = 11.dp), fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (!enabled) TextDisabled else if (selected) PrimaryDark else TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable
private fun AgentTextField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean = true,
                           placeholder: String = "", minLines: Int = 2, maxLines: Int = 6) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), enabled = enabled, minLines = minLines, maxLines = maxLines,
        shape = RoundedCornerShape(12.dp), label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp, color = TextTertiary) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryVariant, unfocusedBorderColor = DividerColor,
            focusedLabelColor = PrimaryDark, unfocusedLabelColor = TextSecondary, cursorColor = PrimaryDark,
            focusedContainerColor = SoftWhite, unfocusedContainerColor = SoftWhite))
}
private fun domainColor(domain: String): Color = when (domain) {
    "work" -> Color(0xFF64A5CD)
    "study" -> Color(0xFFC39A50)
    else -> PrimaryVariant
}
private fun domainIcon(domain: String): ImageVector = when (domain) {
    "work" -> Icons.Outlined.WorkOutline
    "study" -> Icons.Outlined.School
    else -> Icons.Outlined.Eco
}
@Composable
private fun DomainBadge(domain: String, minutes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clip(RoundedCornerShape(6.dp)).background(domainColor(domain).copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(domainIcon(domain), null, tint = domainColor(domain), modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp))
            Text(domainNames[domain] ?: "生活", fontSize = 11.sp, color = domainColor(domain))
        }
        Text("约 $minutes 分钟", fontSize = 11.sp, color = TextSecondary)
    }
}
@Composable
private fun TaskEditor(draft: AgentTaskDraft, onDismiss: () -> Unit, onSave: (AgentTaskDraft) -> Unit) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    var domain by remember(draft) { mutableStateOf(draft.domain) }
    var minutes by remember(draft) { mutableStateOf(draft.minutes.toString()) }
    var next by remember(draft) { mutableStateOf(draft.nextStep) }
    val valid = title.isNotBlank() && (minutes.toIntOrNull() ?: 0) in 5..240
    AlertDialog(onDismissRequest = onDismiss, containerColor = SoftWhite, shape = RoundedCornerShape(20.dp),
        title = { Text("安排任务", fontWeight = FontWeight.Bold, color = TextPrimary) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentTextField(title, { title = it.take(180) }, "任务名称", minLines = 1, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    domainNames.forEach { (key, label) -> SelectionPill(label, domain == key, Modifier.weight(1f)) { domain = key } }
                }
                AgentTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, "预计分钟数（5–240）", minLines = 1, maxLines = 1)
                AgentTextField(next, { next = it.take(500) }, "具体下一步", minLines = 2, maxLines = 4)
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(AgentTaskDraft(title.trim(), domain, minutes.toInt(), next)) }, enabled = valid,
                colors = ButtonDefaults.textButtonColors(contentColor = PrimaryDark)) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)) { Text("取消") } })
}
@Preview(showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun TodayDashboardPreview() {
    MyDiaryTheme { TodayDashboard(TodayState(loaded = true), TodayActions()) }
}
