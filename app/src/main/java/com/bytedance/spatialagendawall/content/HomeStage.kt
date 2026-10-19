package com.bytedance.spatialagendawall.content

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytedance.spatialagendawall.ui.theme.AgendaWallColors
import com.bytedance.spatialagendawall.ui.theme.AgendaPalette
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.LookAtComponent
import com.pico.spatial.core.ecs.LookAtForwardDirection
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import com.pico.spatial.ui.design.TextFieldDefaults
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.SpatialPointerInfo
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.PhysicalLengthConverter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val MAX_NOTES = 40

private enum class AgendaSection {
    CREATE,
    LIBRARY,
    APPEARANCE,
}

@Composable
fun HomeStage() {
    val root = remember { Entity().apply { setName("AgendaWallRoot") } }
    val context = LocalContext.current
    val repository = remember(context) { AgendaRepository(context) }
    var notes by remember { mutableStateOf(repository.load()) }
    var editorTitle by remember { mutableStateOf("") }
    var editorBody by remember { mutableStateOf("") }
    var editorBold by remember { mutableStateOf(false) }
    var editorItalic by remember { mutableStateOf(false) }
    var editorHighlight by remember { mutableStateOf(false) }
    var editorChecklist by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<UUID?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showCompleted by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在扫描真实平面…") }
    var previewNote by remember { mutableStateOf<SavedNote?>(null) }
    var panelVisible by remember { mutableStateOf(true) }
    var panelExpanded by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(AgendaSection.CREATE) }
    var palette by remember { mutableStateOf(AgendaWallColors.midnight) }
    val jobs = remember { mutableListOf<Job>() }
    val detectedPlanes = remember { mutableMapOf<UUID, DetectedPlane>() }
    val planeEntities = remember { mutableMapOf<UUID, Entity>() }
    val noteEntities = remember { mutableMapOf<UUID, Entity>() }
    val mountedNotePanels = remember { mutableSetOf<UUID>() }
    val spatialDensity = LocalDensity.current
    val physicalLengthConverter = LocalPhysicalLengthConverter.current
    val visibleNotes = notes.filter { note ->
        (showCompleted || !note.completed) &&
            (searchQuery.isBlank() || note.title.contains(searchQuery, true) || note.body.contains(searchQuery, true) || note.tags.contains(searchQuery, true))
    }

    fun startEditing(note: SavedNote? = null) {
        editingId = note?.uuid
        editorTitle = note?.title.orEmpty()
        editorBody = note?.body.orEmpty()
        editorBold = note?.bold == true
        editorItalic = note?.italic == true
        editorHighlight = note?.highlight == true
        editorChecklist = note?.checklist == true
    }

    fun saveEditor() {
        val body = editorBody.trim()
        if (body.isBlank()) {
            status = "先写一点正文，再保存笔记"
            return
        }
        val now = System.currentTimeMillis()
        val old = editingId?.let { id -> notes.firstOrNull { it.uuid == id } }
        val saved = SavedNote(
            uuid = old?.uuid ?: UUID.randomUUID(),
            title = editorTitle.trim().ifBlank { "未命名笔记" },
            body = body,
            bold = editorBold,
            italic = editorItalic,
            highlight = editorHighlight,
            checklist = editorChecklist,
            completed = old?.completed ?: false,
            tags = old?.tags.orEmpty(),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        repository.save(saved)
        notes = notes.filterNot { it.uuid == saved.uuid } + saved
        editingId = null
        editorTitle = ""
        editorBody = ""
        status = "笔记已保存，可继续指向表面贴上"
    }

    fun commitNote(note: SavedNote, plane: DetectedPlane) {
        if (notes.size >= MAX_NOTES) {
            status = "已达到 $MAX_NOTES 条上限"
            return
        }
        jobs += CoroutineScope(Dispatchers.Main).launch {
            when (val result = WorldTrackingManager.createAnchor(plane.position, plane.rotation, note.title)) {
                is WorldTrackingResult.Success -> {
                    val anchor = result.data ?: return@launch
                    val committed = note.copy(uuid = anchor.anchorUUID, updatedAt = System.currentTimeMillis())
                    repository.save(committed)
                    note.uuid.let { noteEntities.remove(it)?.destroy(true) }
                    addNoteEntity(root, committed, noteEntities)
                    notes = repository.load()
                    previewNote = null
                    status = "已贴附到真实表面"
                }
                is WorldTrackingResult.Error -> status = "贴附失败：${result.errorMessage}"
            }
        }
    }

    DisposableEffect(root) {
        val subscription = PlaneTrackingManager.subscribeAnchorUpdate { update ->
            val anchor = update.anchor
            when (update.event) {
                AnchorUpdate.Event.REMOVED -> {
                    detectedPlanes.remove(anchor.anchorUUID)
                    planeEntities.remove(anchor.anchorUUID)?.destroy(true)
                }
                AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED -> {
                    detectedPlanes[anchor.anchorUUID] = DetectedPlane(anchor.transform.position, anchor.transform.rotation.toQuat().toEulerAngles())
                    if (update.event == AnchorUpdate.Event.ADDED && anchor.anchorUUID !in planeEntities) {
                        val mesh = MeshResource.loadFromPlaneAnchor(anchor.anchorUUID)
                        val planeEntity = Entity().apply {
                            setName("DetectedSurface-${anchor.anchorUUID}")
                            components[TransformComponent::class.java]?.apply {
                                setPosition(anchor.transform.position)
                                setEulerAngles(anchor.transform.rotation.toQuat().toEulerAngles())
                            }
                            components.set(InteractableComponent())
                            components.set(CollisionComponent(listOf(ShapeResource.createStaticMesh(mesh)), PhysicsMaterialResource()))
                        }
                        planeEntities[anchor.anchorUUID] = planeEntity
                        root.addChild(planeEntity)
                    } else {
                        planeEntities[anchor.anchorUUID]?.components[TransformComponent::class.java]?.apply {
                            setPosition(anchor.transform.position)
                            setEulerAngles(anchor.transform.rotation.toQuat().toEulerAngles())
                        }
                    }
                }
                else -> Unit
            }
        }
        PlaneTrackingManager.start()
        jobs += CoroutineScope(Dispatchers.Main).launch {
            when (val result = WorldTrackingManager.loadAnchor()) {
                is WorldTrackingResult.Success -> result.data?.forEach { anchor ->
                    val note = repository.find(anchor.anchorUUID) ?: SavedNote(anchor.anchorUUID, anchor.name, anchor.name)
                    if (notes.none { it.uuid == note.uuid }) notes = notes + note
                    addNoteEntity(root, note, noteEntities)
                }
                is WorldTrackingResult.Error -> status = "锚点恢复失败：${result.errorMessage}"
            }
        }
        onDispose {
            subscription.cancel()
            PlaneTrackingManager.stop()
            jobs.forEach(Job::cancel)
            root.destroy(true)
        }
    }

    SpatialView(
        modifier = Modifier
            .pointerInput(previewNote) {
                detectSpatialPointerEvent(context) { events: List<SpatialPointerInfo> ->
                    val event = events.firstOrNull() ?: return@detectSpatialPointerEvent false
                    val preview = previewNote ?: return@detectSpatialPointerEvent false
                    val planeId = planeEntities.entries.firstOrNull { it.value == event.targetedEntity }?.key
                    val plane = planeId?.let { detectedPlanes[it] }
                    val previewEntity = preview?.let { noteEntities[it.uuid] }
                    if (plane != null && previewEntity != null) {
                        previewEntity.components[TransformComponent::class.java]?.apply {
                            setPosition(plane.position)
                            setEulerAngles(plane.rotation)
                        }
                        status = if (event.isUpEvent()) "正在贴附到选中的表面…" else "已吸附到表面，释放以贴上"
                        if (event.isUpEvent()) commitNote(preview, plane)
                        return@detectSpatialPointerEvent true
                    }
                    false
                }
            }
            .pointerInput(Unit) {
                detectSpatialDragGesture(
                    context,
                    targetedToEntity = TargetEntity.any(),
                    onDragEnd = { value ->
                        val target = value.targetEntity ?: return@detectSpatialDragGesture
                        val entry = findNoteTarget(target, noteEntities) ?: return@detectSpatialDragGesture
                        val noteId = entry.first
                        val noteEntity = entry.second
                        val transform = noteEntity.components[TransformComponent::class.java] ?: return@detectSpatialDragGesture
                        val savedPosition = transform.position
                        noteDragPositions.remove(noteId)
                        notes.firstOrNull { it.uuid == noteId }?.let { note ->
                            val saved = note.copy(offsetX = savedPosition.x, offsetY = savedPosition.y, offsetZ = savedPosition.z, updatedAt = System.currentTimeMillis())
                            repository.save(saved)
                            notes = notes.map { if (it.uuid == noteId) saved else it }
                        }
                        status = "便签已移动并保存"
                    },
                ) { value ->
                    val target = value.targetEntity ?: return@detectSpatialDragGesture
                    val entry = findNoteTarget(target, noteEntities) ?: return@detectSpatialDragGesture
                    val noteId = entry.first
                    val noteEntity = entry.second
                    val transform = noteEntity.components[TransformComponent::class.java] ?: return@detectSpatialDragGesture
                    val previous = noteDragPositions.getOrPut(noteId) { transform.position }
                    val next = Vector3(previous.x + convertPxToMeter(value.dragAmount.x, spatialDensity, physicalLengthConverter), previous.y + convertPxToMeter(-value.dragAmount.y, spatialDensity, physicalLengthConverter), previous.z + convertPxToMeter(value.dragAmount.z, spatialDensity, physicalLengthConverter))
                    transform.setPosition(next)
                    status = "正在移动便签，松手后保存位置"
                }
            },
        initial = { content, attachments ->
            content.addEntity(root)
            val cameraAnchor = AnchorEntity(AnchorTarget.createCameraTarget()).apply {
                components[AnchorComponent::class.java]?.positionOffset = Vector3(0f, 0.05f, -1.2f)
                setName("AgendaWallPanelCameraAnchor")
                attachments.entity("AgendaWallPanel")?.let { addChild(it) }
                attachments.entity("AgendaReopenPanel")?.let { addChild(it) }
            }
            content.addEntity(cameraAnchor)
        },
        update = { _, attachments ->
            attachments.entity("AgendaWallPanel")?.apply {
                enabled = panelVisible
                components[TransformComponent::class.java]?.setScaleVector(Vector3(if (panelExpanded) 1.18f else 1f))
            }
            attachments.entity("AgendaReopenPanel")?.apply { enabled = !panelVisible }
            previewNote?.let { note ->
                attachments.entity("PreviewNotePanel")?.let { panel ->
                    noteEntities[note.uuid]?.let { entity ->
                        setNotePanelFront(panel)
                        entity.addChild(panel)
                    }
                }
            }
            notes.forEach { note ->
                if (note.uuid !in mountedNotePanels && note.uuid != previewNote?.uuid) {
                    attachments.entity("NotePanel-${note.uuid}")?.let { panel ->
                        setNotePanelFront(panel)
                        noteEntities[note.uuid]?.let { it.addChild(panel); mountedNotePanels += note.uuid }
                    }
                }
            }
        },
        attachments = {
            notes.forEach { note ->
                AttachmentPanel("NotePanel-${note.uuid}") { NoteSurface(note, palette = palette) }
            }
            AttachmentPanel("PreviewNotePanel") { previewNote?.let { NoteSurface(it, palette = palette, preview = true) } }
            AttachmentPanel("AgendaWallPanel") {
                PicoTheme {
                    val panelShape = RoundedCornerShape(28.dp)
                    val fieldColors = TextFieldDefaults.textFieldColors(textColor = palette.primaryText, backgroundColor = palette.fieldSurface, focusedColor = palette.accent, placeholderColor = palette.secondaryText, errorColor = palette.accent)
                    Box(Modifier.width(760.dp).heightIn(min = 440.dp).clip(panelShape).background(palette.panelSurface, panelShape).padding(horizontal = 38.dp, vertical = 28.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("空间日程墙", fontSize = 42.sp, color = palette.primaryText)
                                    Text("把重要事项放进你的空间", fontSize = 21.sp, color = palette.secondaryText)
                                }
                                Text("${notes.size}/$MAX_NOTES", fontSize = 24.sp, color = palette.primaryText)
                                Button(onClick = { panelExpanded = !panelExpanded }) { Text(if (panelExpanded) "收起" else "放大") }
                                Button(onClick = { panelVisible = false }) { Text("关闭") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { activeSection = AgendaSection.CREATE }) { Text(if (activeSection == AgendaSection.CREATE) "创建 · 当前" else "创建") }
                                Button(onClick = { activeSection = AgendaSection.LIBRARY }) { Text(if (activeSection == AgendaSection.LIBRARY) "笔记库 · 当前" else "笔记库") }
                                Button(onClick = { activeSection = AgendaSection.APPEARANCE }) { Text(if (activeSection == AgendaSection.APPEARANCE) "设置 · 当前" else "设置") }
                            }
                            Text(status, fontSize = 19.sp, color = palette.secondaryText)
                            when (activeSection) {
                                AgendaSection.CREATE -> {
                                    Text("新建一张便签", fontSize = 28.sp, color = palette.primaryText)
                                    TextField(value = editorTitle, onValueChange = { editorTitle = it.take(48) }, placeholder = { Text("标题", color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    TextField(value = editorBody, onValueChange = { editorBody = it.take(500) }, placeholder = { Text("正文：支持多行和基础富文本", color = palette.secondaryText) }, colors = fieldColors, singleLine = false, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { editorBold = !editorBold }) { Text(if (editorBold) "粗体 ✓" else "粗体") }
                                        Button(onClick = { editorItalic = !editorItalic }) { Text(if (editorItalic) "斜体 ✓" else "斜体") }
                                        Button(onClick = { editorHighlight = !editorHighlight }) { Text(if (editorHighlight) "高亮 ✓" else "高亮") }
                                        Button(onClick = { editorChecklist = !editorChecklist }) { Text(if (editorChecklist) "清单 ✓" else "清单") }
                                    }
                                    val canGenerate = notes.size < MAX_NOTES && previewNote == null
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(enabled = canGenerate, onClick = {
                                            if (editorTitle.isBlank() && editorBody.isBlank()) {
                                                status = "请先填写标题或正文，再生成便签"
                                            } else {
                                                val note = SavedNote(UUID.randomUUID(), editorTitle.trim().ifBlank { "未命名笔记" }, editorBody.trim().ifBlank { editorTitle.trim() }, editorBold, editorItalic, editorHighlight, editorChecklist)
                                                previewNote = note
                                                addNoteVisual(root, note, noteEntities)
                                                status = "请指向墙面或桌面，吸附后释放以贴上"
                                            }
                                        }, modifier = Modifier.weight(1f)) { Text("生成并定位") }
                                        if (previewNote != null) {
                                            Button(onClick = {
                                                previewNote?.let { noteEntities.remove(it.uuid)?.destroy(true) }
                                                previewNote = null
                                                status = "已取消定位，可继续编辑"
                                            }) { Text("取消定位") }
                                        }
                                        Button(onClick = { saveEditor() }, modifier = Modifier.weight(1f)) { Text(if (editingId == null) "保存到笔记库" else "保存修改") }
                                        Button(onClick = { startEditing(null) }) { Text("清空") }
                                    }
                                    Text(
                                        when {
                                            previewNote != null -> "当前便签正在定位；如果要重新编辑，请先取消定位。"
                                            notes.size >= MAX_NOTES -> "已达到 $MAX_NOTES 条上限，请先删除或完成一条便签。"
                                            editorTitle.isBlank() && editorBody.isBlank() -> "先填写标题或正文，生成按钮会自动启用。"
                                            else -> "生成并定位会创建空间预览；指向墙面或桌面，松手后完成吸附。"
                                        },
                                        fontSize = 17.sp,
                                        color = palette.secondaryText,
                                    )
                                }
                                AgendaSection.LIBRARY -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("笔记库", fontSize = 28.sp, color = palette.primaryText, modifier = Modifier.weight(1f))
                                        Button(onClick = { showCompleted = !showCompleted }) { Text(if (showCompleted) "含已完成" else "仅未完成") }
                                    }
                                    TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("搜索标题、正文或标签", color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    visibleNotes.takeLast(5).reversed().forEach { note ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.weight(1f)) {
                                                Text(if (note.completed) "✓ ${note.title}" else note.title, fontSize = 20.sp, color = palette.primaryText)
                                                Text(note.body.take(44), fontSize = 17.sp, color = palette.secondaryText)
                                            }
                                            Button(onClick = { startEditing(note); activeSection = AgendaSection.CREATE }) { Text("编辑") }
                                            Button(onClick = { val changed = note.copy(completed = !note.completed, updatedAt = System.currentTimeMillis()); repository.save(changed); notes = notes.map { if (it.uuid == note.uuid) changed else it } }) { Text(if (note.completed) "恢复" else "完成") }
                                            Button(onClick = { jobs += CoroutineScope(Dispatchers.Main).launch { WorldTrackingManager.removeAnchor(note.uuid); repository.remove(note.uuid); noteEntities.remove(note.uuid)?.destroy(true); notes = repository.load(); status = "笔记已删除" } }) { Text("删除") }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("走近便签并抓取即可移动，松手后位置会保存。", fontSize = 17.sp, color = palette.secondaryText, modifier = Modifier.weight(1f))
                                        Button(onClick = { notes.forEach { note -> jobs += CoroutineScope(Dispatchers.Main).launch { WorldTrackingManager.removeAnchor(note.uuid) } }; repository.clear(); notes = emptyList(); status = "墙面已清空" }) { Text("清空全部") }
                                    }
                                }
                                AgendaSection.APPEARANCE -> {
                                    Text("界面设置", fontSize = 28.sp, color = palette.primaryText)
                                    Text("选择适合当前环境光线的色系。", fontSize = 18.sp, color = palette.secondaryText)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { palette = AgendaWallColors.midnight }) { Text("墨黑") }
                                        Button(onClick = { palette = AgendaWallColors.warm }) { Text("暖沙") }
                                        Button(onClick = { palette = AgendaWallColors.ocean }) { Text("海蓝") }
                                    }
                                    Text("空间操作", fontSize = 24.sp, color = palette.primaryText)
                                    Text("• 生成便签后，指向最近的墙面或桌面并释放。\n• 走近便签，抓取卡片即可移动。\n• 便签文字会始终朝向你，并在释放后保存位置。", fontSize = 18.sp, color = palette.secondaryText)
                                }
                            }
                        }
                    }
                }
            }
            AttachmentPanel("AgendaReopenPanel") {
                PicoTheme { Button(onClick = { panelVisible = true }) { Text("打开日程墙") } }
            }
        },
    )
}

@Composable
private fun NoteSurface(note: SavedNote, palette: AgendaPalette, preview: Boolean = false) {
    PicoTheme {
        Box(Modifier.width(300.dp).background(palette.noteSurface, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note.title, fontSize = 22.sp, color = palette.noteInk)
                Text(richText(note, palette), fontSize = 24.sp, color = palette.noteInk)
                if (preview) Text("预览 · 指向表面后释放", fontSize = 16.sp, color = palette.noteInk)
            }
        }
    }
}

private fun richText(note: SavedNote, palette: AgendaPalette): AnnotatedString = buildAnnotatedString {
    val style = SpanStyle(fontWeight = if (note.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (note.italic) FontStyle.Italic else FontStyle.Normal, background = if (note.highlight) palette.noteHighlight else androidx.compose.ui.graphics.Color.Transparent)
    withStyle(style) { append(if (note.checklist) "☐ ${note.body}" else note.body) }
}

private fun setNotePanelFront(panel: Entity) {
    panel.components[TransformComponent::class.java]?.apply {
        setPosition(Vector3(0f, 0f, 0.028f))
        // Let the panel choose the viewer-facing side instead of guessing a
        // fixed wall normal. This prevents notes from being readable only
        // from the back of a detected wall.
        setEulerAngles(EulerAngles(0f, 0f, 0f))
        setScaleVector(Vector3(1f, 1f, 1f))
    }
    if (panel.components[LookAtComponent::class.java] == null) {
        panel.components.set(LookAtComponent().apply {
            setViewerAsTarget()
            lookAtForwardDirection = LookAtForwardDirection.NEGATIVE_Z
            alignLocalUpToWorldUp = true
        })
    }
}

private val noteDragPositions = mutableMapOf<UUID, Vector3>()
private data class DetectedPlane(val position: Vector3, val rotation: EulerAngles)

private fun findNoteTarget(target: Entity, noteEntities: Map<UUID, Entity>): Pair<UUID, Entity>? {
    var current: Entity? = target
    while (current != null) {
        noteEntities.entries.firstOrNull { it.value == current }?.let { return it.key to it.value }
        current = current.getParent()
    }
    return null
}

private fun addNoteEntity(root: Entity, note: SavedNote, noteEntities: MutableMap<UUID, Entity>) {
    val anchorEntity = Entity().apply {
        setName("NoteAnchor-${note.uuid}")
        components[AnchorComponent::class.java] = AnchorComponent(AnchorTarget.createWorldAnchorTarget(note.uuid))
    }
    addNoteVisual(anchorEntity, note, noteEntities)
    root.addChild(anchorEntity)
}

private fun addNoteVisual(root: Entity, note: SavedNote, noteEntities: MutableMap<UUID, Entity>) {
    val cardMaterial = UnlitMaterial.create().apply { setBaseColor(Color4.fromLinearHex("FFD166FF")) }
    val card = ModelEntity(MeshResource.createBox(Vector3(0.34f, 0.23f, 0.018f)), cardMaterial).apply {
        setName("NoteCard-${note.uuid}")
        components[TransformComponent::class.java]?.setPosition(Vector3(note.offsetX, note.offsetY, note.offsetZ))
        components.set(InteractableComponent())
        components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(0.34f, 0.23f, 0.018f))), PhysicsMaterialResource()))
        // Keep the card readable even when a detected wall reports its back
        // normal or the user approaches from an oblique angle. The anchor
        // position remains the real surface hit; only the card presentation
        // is viewer-facing.
        components.set(LookAtComponent().apply {
            setViewerAsTarget()
            lookAtForwardDirection = LookAtForwardDirection.POSITIVE_Z
            alignLocalUpToWorldUp = true
        })
    }
    val edgeMaterial = UnlitMaterial.create().apply { setBaseColor(Color4.fromLinearHex("E6A93AFF")) }
    val edge = ModelEntity(MeshResource.createBox(Vector3(0.35f, 0.24f, 0.008f)), edgeMaterial).apply {
        setName("NoteBackEdge-${note.uuid}")
        components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, -0.014f))
    }
    card.addChild(edge)
    root.addChild(card)
    noteEntities[note.uuid] = card
}

private fun convertPxToMeter(px: Float, density: androidx.compose.ui.unit.Density, converter: PhysicalLengthConverter): Float = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }

private data class SavedNote(
    val uuid: UUID,
    val title: String,
    val body: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val highlight: Boolean = false,
    val checklist: Boolean = false,
    val completed: Boolean = false,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
)

private class AgendaRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("agenda_wall_notes", Context.MODE_PRIVATE)

    fun load(): List<SavedNote> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith("note_")) return@mapNotNull null
        val uuid = runCatching { UUID.fromString(key.removePrefix("note_")) }.getOrNull() ?: return@mapNotNull null
        parse(uuid, value as? String ?: return@mapNotNull null)
    }.sortedBy { it.updatedAt }

    fun find(uuid: UUID): SavedNote? = load().firstOrNull { it.uuid == uuid }

    fun save(note: SavedNote) {
        prefs.edit().putString("note_${note.uuid}", JSONObject().apply {
            put("title", note.title); put("body", note.body); put("bold", note.bold); put("italic", note.italic); put("highlight", note.highlight); put("checklist", note.checklist); put("completed", note.completed); put("tags", note.tags); put("createdAt", note.createdAt); put("updatedAt", note.updatedAt); put("offsetX", note.offsetX); put("offsetY", note.offsetY); put("offsetZ", note.offsetZ)
        }.toString()).apply()
    }

    fun remove(uuid: UUID) { prefs.edit().remove("note_$uuid").apply() }
    fun clear() { prefs.edit().clear().apply() }

    private fun parse(uuid: UUID, raw: String): SavedNote = runCatching {
        val json = JSONObject(raw)
        SavedNote(uuid, json.optString("title", "未命名笔记"), json.optString("body", ""), json.optBoolean("bold"), json.optBoolean("italic"), json.optBoolean("highlight"), json.optBoolean("checklist"), json.optBoolean("completed"), json.optString("tags"), json.optLong("createdAt", System.currentTimeMillis()), json.optLong("updatedAt", System.currentTimeMillis()), json.optDouble("offsetX", 0.0).toFloat(), json.optDouble("offsetY", 0.0).toFloat(), json.optDouble("offsetZ", 0.0).toFloat())
    }.getOrElse {
        // Backward compatibility with the original note_<uuid> = plain text format.
        SavedNote(uuid, "未命名笔记", raw)
    }
}
