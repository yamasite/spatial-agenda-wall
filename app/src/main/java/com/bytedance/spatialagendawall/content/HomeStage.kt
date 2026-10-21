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
import com.bytedance.spatialagendawall.R
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.simulation.CollisionCastHitMode
import com.pico.spatial.core.ecs.simulation.CollisionGroup
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import com.pico.spatial.ui.design.TextFieldDefaults
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.SpatialPointerInfo
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.platform.PhysicalLengthConverter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val MAX_NOTES = 40
// Keep collision surfaces bounded, but choose them by wall/semantic/area
// priority instead of keeping whichever planes happened to arrive first.
private const val MAX_INTERACTION_PLANES = 12

private class AgendaText(private val context: Context) {
    operator fun get(@androidx.annotation.StringRes id: Int): String = context.getString(id)
    fun format(@androidx.annotation.StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
}

private enum class AgendaSection {
    CREATE,
    LIBRARY,
    APPEARANCE,
}

private enum class AgendaFilter {
    ALL,
    TODAY,
    PRIORITY,
}

@Composable
fun HomeStage() {
    val root = remember { Entity().apply { setName("AgendaWallRoot") } }
    val context = LocalContext.current
    val text = remember(context) { AgendaText(context) }
    val repository = remember(context) { AgendaRepository(context) }
    var notes by remember { mutableStateOf(repository.load()) }
    var editorTitle by remember { mutableStateOf("") }
    var editorBody by remember { mutableStateOf("") }
    var editorBold by remember { mutableStateOf(false) }
    var editorItalic by remember { mutableStateOf(false) }
    var editorHighlight by remember { mutableStateOf(false) }
    var editorChecklist by remember { mutableStateOf(false) }
    var editorDueDate by remember { mutableStateOf("") }
    var editorPriority by remember { mutableStateOf("normal") }
    var editorTags by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<UUID?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showCompleted by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf(text[R.string.status_scanning]) }
    var previewNote by remember { mutableStateOf<SavedNote?>(null) }
    var panelVisible by remember { mutableStateOf(true) }
    var panelExpanded by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(AgendaSection.CREATE) }
    var activeFilter by remember { mutableStateOf(AgendaFilter.ALL) }
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
            (activeFilter == AgendaFilter.ALL ||
                (activeFilter == AgendaFilter.TODAY && note.dueDate == todayKey()) ||
                (activeFilter == AgendaFilter.PRIORITY && note.priority == "high")) &&
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
        editorDueDate = note?.dueDate.orEmpty()
        editorPriority = note?.priority ?: "normal"
        editorTags = note?.tags.orEmpty()
    }

    fun saveEditor() {
        val body = editorBody.trim()
        if (body.isBlank()) {
            status = text[R.string.status_body_required]
            return
        }
        val now = System.currentTimeMillis()
        val old = editingId?.let { id -> notes.firstOrNull { it.uuid == id } }
        val saved = SavedNote(
            uuid = old?.uuid ?: UUID.randomUUID(),
            title = editorTitle.trim().ifBlank { text[R.string.untitled_note] },
            body = body,
            bold = editorBold,
            italic = editorItalic,
            highlight = editorHighlight,
            checklist = editorChecklist,
            completed = old?.completed ?: false,
            tags = editorTags.trim(),
            dueDate = editorDueDate.trim(),
            priority = editorPriority,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        repository.save(saved)
        notes = notes.filterNot { it.uuid == saved.uuid } + saved
        editingId = null
        editorTitle = ""
        editorBody = ""
        editorDueDate = ""
        editorPriority = "normal"
        editorTags = ""
        status = text[R.string.status_note_saved]
    }

    fun commitNote(note: SavedNote, plane: DetectedPlane, positionWorld: Vector3) {
        if (notes.size >= MAX_NOTES) {
            status = text.format(R.string.status_note_limit, MAX_NOTES)
            return
        }
        jobs += CoroutineScope(Dispatchers.Main).launch {
            when (val result = WorldTrackingManager.createAnchor(positionWorld, plane.rotation.toEulerAngles(), note.title)) {
                is WorldTrackingResult.Success -> {
                    val anchor = result.data ?: return@launch
                    val committed = note.copy(uuid = anchor.anchorUUID, updatedAt = System.currentTimeMillis())
                    repository.remove(note.uuid)
                    repository.save(committed)
                    note.uuid.let { noteEntities.remove(it)?.destroy(true) }
                    addNoteEntity(
                        root,
                        committed,
                        noteEntities,
                        initialPosition = root.convertPositionFrom(anchor.transform.position, null),
                        initialRotation = root.convertRotationFrom(anchor.transform.rotation.toQuat(), null),
                    )
                    notes = repository.load()
                    previewNote = null
                    status = text[R.string.status_attached]
                }
                is WorldTrackingResult.Error -> status = text.format(R.string.status_attach_failed, result.errorMessage)
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
                    refreshInteractionPlanes(root, detectedPlanes, planeEntities)
                }
                AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED -> {
                    detectedPlanes[anchor.anchorUUID] = DetectedPlane(
                        position = anchor.transform.position,
                        rotation = anchor.transform.rotation.toQuat(),
                        orientation = anchor.planeOrientation.name,
                        semantic = anchor.semantics.name,
                        area = anchor.boundingBoxSize.x * anchor.boundingBoxSize.y,
                    )
                    refreshInteractionPlanes(root, detectedPlanes, planeEntities)
                }
                else -> Unit
            }
        }
        val worldAnchorSubscription = WorldTrackingManager.subscribeAnchorUpdate { update ->
            val noteEntity = noteEntities[update.anchor.anchorUUID] ?: return@subscribeAnchorUpdate
            val anchorEntity = noteEntity.getParent() ?: return@subscribeAnchorUpdate
            anchorEntity.components[TransformComponent::class.java]?.apply {
                setPosition(root.convertPositionFrom(update.anchor.transform.position, null))
                setQuaternion(root.convertRotationFrom(update.anchor.transform.rotation.toQuat(), null))
            }
        }
        PlaneTrackingManager.start()
        jobs += CoroutineScope(Dispatchers.Main).launch {
            when (val result = WorldTrackingManager.loadAnchor()) {
                is WorldTrackingResult.Success -> result.data?.forEach { anchor ->
                    val note = repository.find(anchor.anchorUUID) ?: SavedNote(anchor.anchorUUID, anchor.name, anchor.name)
                    if (notes.none { it.uuid == note.uuid }) notes = notes + note
                    addNoteEntity(
                        root,
                        note,
                        noteEntities,
                        initialPosition = root.convertPositionFrom(anchor.transform.position, null),
                        initialRotation = root.convertRotationFrom(anchor.transform.rotation.toQuat(), null),
                    )
                }
                is WorldTrackingResult.Error -> status = text.format(R.string.status_restore_failed, result.errorMessage)
            }
        }
        onDispose {
            subscription.cancel()
            worldAnchorSubscription.cancel()
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
                    val planeId = findPlaneTarget(event.targetedEntity, planeEntities)
                    val plane = planeId?.let { detectedPlanes[it] }
                    val previewEntity = preview?.let { noteEntities[it.uuid] }
                    if (plane != null && previewEntity != null) {
                        val viewerPosition = event.inputDevicePose.rawPosition.let {
                            Vector3(it.x, it.y, it.z)
                        }
                        val orientedPlane = plane.copy(
                            rotation = orientPlaneTowardViewer(plane, viewerPosition),
                        )
                        val localHitPosition = raycastPlanePosition(root, event, planeId, planeEntities)
                            ?: root.convertPositionFrom(plane.position, null)
                        previewEntity.components[TransformComponent::class.java]?.apply {
                            setPosition(localHitPosition)
                            setQuaternion(root.convertRotationFrom(orientedPlane.rotation, null))
                        }
                        previewEntity.enabled = true
                        status = if (event.isUpEvent()) text[R.string.status_attaching] else text[R.string.status_surface_selected]
                        if (event.isUpEvent()) commitNote(preview, orientedPlane, root.convertPositionTo(localHitPosition, null))
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
                        notes.firstOrNull { it.uuid == noteId }?.let { note ->
                            val saved = note.copy(offsetX = savedPosition.x, offsetY = savedPosition.y, offsetZ = savedPosition.z, updatedAt = System.currentTimeMillis())
                            repository.save(saved)
                            notes = notes.map { if (it.uuid == noteId) saved else it }
                        }
                        status = text[R.string.status_note_moved]
                    },
                ) { value ->
                    val target = value.targetEntity ?: return@detectSpatialDragGesture
                    val entry = findNoteTarget(target, noteEntities) ?: return@detectSpatialDragGesture
                    val noteId = entry.first
                    val noteEntity = entry.second
                    val transform = noteEntity.components[TransformComponent::class.java] ?: return@detectSpatialDragGesture
                    // dragAmount is an incremental per-frame delta. Apply it
                    // to the current local position so movement accumulates;
                    // keep Z fixed so a note stays on its anchored surface.
                    val current = transform.position
                    val next = Vector3(
                        current.x + convertPxToMeter(value.dragAmount.x, spatialDensity, physicalLengthConverter),
                        current.y + convertPxToMeter(-value.dragAmount.y, spatialDensity, physicalLengthConverter),
                        current.z,
                    )
                    transform.setPosition(next)
                    status = text[R.string.status_moving_note]
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
                            if (panel.getParent() != entity) {
                                panel.getParent()?.removeChild(panel)
                                entity.addChild(panel)
                            }
                        setNotePanelFront(panel)
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
                    val fieldColors = TextFieldDefaults.textFieldColors(textColor = palette.fieldText, backgroundColor = palette.fieldSurface, focusedColor = palette.accent, placeholderColor = palette.fieldText.copy(alpha = 0.62f), errorColor = palette.accent)
                    val secondaryButtonColors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0x66727D8D), contentColor = palette.primaryText)
                    val primaryButtonColors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = androidx.compose.ui.graphics.Color(0xFF10202D))
                    val closeButtonColors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0x66C96B6B), contentColor = androidx.compose.ui.graphics.Color(0xFF5D1717))
                    Box(
                        Modifier
                            .width(760.dp)
                            .heightIn(min = 440.dp)
                            .clip(panelShape)
                            .backgroundMaterial(style = Material.Thick)
                            .padding(horizontal = 38.dp, vertical = 28.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(text[R.string.app_title], fontSize = 42.sp, color = palette.primaryText)
                                    Text(text[R.string.app_subtitle], fontSize = 21.sp, color = palette.secondaryText)
                                }
                                Text("${notes.size}/$MAX_NOTES", fontSize = 24.sp, color = palette.primaryText)
                                Button(colors = secondaryButtonColors, onClick = { panelExpanded = !panelExpanded }) { Text(if (panelExpanded) text[R.string.collapse] else text[R.string.expand]) }
                                Button(colors = closeButtonColors, onClick = { panelVisible = false }) { Text(text[R.string.close]) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(colors = if (activeSection == AgendaSection.CREATE) primaryButtonColors else secondaryButtonColors, onClick = { activeSection = AgendaSection.CREATE }) { Text(if (activeSection == AgendaSection.CREATE) text[R.string.create_current] else text[R.string.create]) }
                                Button(colors = if (activeSection == AgendaSection.LIBRARY) primaryButtonColors else secondaryButtonColors, onClick = { activeSection = AgendaSection.LIBRARY }) { Text(if (activeSection == AgendaSection.LIBRARY) text[R.string.library_current] else text[R.string.library]) }
                                Button(colors = if (activeSection == AgendaSection.APPEARANCE) primaryButtonColors else secondaryButtonColors, onClick = { activeSection = AgendaSection.APPEARANCE }) { Text(if (activeSection == AgendaSection.APPEARANCE) text[R.string.settings_current] else text[R.string.settings]) }
                            }
                            Text(status, fontSize = 19.sp, color = palette.secondaryText)
                            when (activeSection) {
                                AgendaSection.CREATE -> {
                                    Text(text[R.string.new_note], fontSize = 28.sp, color = palette.primaryText)
                                    TextField(value = editorTitle, onValueChange = { editorTitle = it.take(48) }, placeholder = { Text(text[R.string.title], color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    TextField(value = editorBody, onValueChange = { editorBody = it.take(500) }, placeholder = { Text(text[R.string.body_placeholder], color = palette.secondaryText) }, colors = fieldColors, singleLine = false, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        TextField(value = editorDueDate, onValueChange = { editorDueDate = it.take(10) }, placeholder = { Text(text[R.string.due_date_placeholder], color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.weight(1f))
                                        TextField(value = editorTags, onValueChange = { editorTags = it.take(64) }, placeholder = { Text(text[R.string.tags_placeholder], color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(colors = if (editorBold) primaryButtonColors else secondaryButtonColors, onClick = { editorBold = !editorBold }) { Text(if (editorBold) text[R.string.bold_on] else text[R.string.bold]) }
                                        Button(colors = if (editorItalic) primaryButtonColors else secondaryButtonColors, onClick = { editorItalic = !editorItalic }) { Text(if (editorItalic) text[R.string.italic_on] else text[R.string.italic]) }
                                        Button(colors = if (editorHighlight) primaryButtonColors else secondaryButtonColors, onClick = { editorHighlight = !editorHighlight }) { Text(if (editorHighlight) text[R.string.highlight_on] else text[R.string.highlight]) }
                                        Button(colors = if (editorChecklist) primaryButtonColors else secondaryButtonColors, onClick = { editorChecklist = !editorChecklist }) { Text(if (editorChecklist) text[R.string.checklist_on] else text[R.string.checklist]) }
                                        Button(colors = if (editorPriority == "high") primaryButtonColors else secondaryButtonColors, onClick = { editorPriority = when (editorPriority) { "normal" -> "high"; "high" -> "low"; else -> "normal" } }) { Text(text.format(R.string.priority, priorityLabel(editorPriority, text))) }
                                    }
                                    val canGenerate = notes.size < MAX_NOTES && previewNote == null
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(colors = primaryButtonColors, enabled = canGenerate, onClick = {
                                            if (editorTitle.isBlank() && editorBody.isBlank()) {
                                                status = text[R.string.status_generate_required]
                                            } else {
                                                val note = SavedNote(UUID.randomUUID(), editorTitle.trim().ifBlank { text[R.string.untitled_note] }, editorBody.trim().ifBlank { editorTitle.trim() }, editorBold, editorItalic, editorHighlight, editorChecklist, tags = editorTags.trim(), dueDate = editorDueDate.trim(), priority = editorPriority)
                                                repository.save(note)
                                                notes = notes + note
                                                previewNote = note
                                                addNoteVisual(root, note, noteEntities, visible = false)
                                                editorTitle = ""
                                                editorBody = ""
                                                editorDueDate = ""
                                                editorPriority = "normal"
                                                editorTags = ""
                                                editingId = null
                                                activeSection = AgendaSection.LIBRARY
                                                status = text[R.string.status_preview_saved]
                                            }
                                        }, modifier = Modifier.weight(1f)) { Text(text[R.string.generate_and_place]) }
                                        if (previewNote != null) {
                                            Button(colors = secondaryButtonColors, onClick = {
                                                previewNote?.let { noteEntities.remove(it.uuid)?.destroy(true) }
                                                previewNote = null
                                                status = text[R.string.status_placement_cancelled]
                                            }) { Text(text[R.string.cancel_placement]) }
                                        }
                                        Button(colors = primaryButtonColors, onClick = { saveEditor() }, modifier = Modifier.weight(1f)) { Text(if (editingId == null) text[R.string.save_to_library] else text[R.string.save_changes]) }
                                        Button(colors = secondaryButtonColors, onClick = { startEditing(null) }) { Text(text[R.string.clear]) }
                                    }
                                    Text(
                                        when {
                                            previewNote != null -> text[R.string.hint_preview_active]
                                            notes.size >= MAX_NOTES -> text.format(R.string.hint_note_limit, MAX_NOTES)
                                            editorTitle.isBlank() && editorBody.isBlank() -> text[R.string.hint_fill_content]
                                            else -> text[R.string.hint_generate_place]
                                        },
                                        fontSize = 17.sp,
                                        color = palette.secondaryText,
                                    )
                                }
                                AgendaSection.LIBRARY -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text[R.string.library], fontSize = 28.sp, color = palette.primaryText, modifier = Modifier.weight(1f))
                                        Button(onClick = { showCompleted = !showCompleted }) { Text(if (showCompleted) text[R.string.show_completed] else text[R.string.only_open]) }
                                    }
                                    Text(text.format(R.string.library_summary, notes.count { !it.completed }, notes.count { it.completed }, notes.count { it.dueDate == todayKey() && !it.completed }), fontSize = 18.sp, color = palette.secondaryText)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { activeFilter = AgendaFilter.ALL }) { Text(if (activeFilter == AgendaFilter.ALL) text[R.string.all_current] else text[R.string.all]) }
                                        Button(onClick = { activeFilter = AgendaFilter.TODAY }) { Text(if (activeFilter == AgendaFilter.TODAY) text[R.string.today_current] else text[R.string.today]) }
                                        Button(onClick = { activeFilter = AgendaFilter.PRIORITY }) { Text(if (activeFilter == AgendaFilter.PRIORITY) text[R.string.high_priority_current] else text[R.string.high_priority]) }
                                    }
                                    TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(text[R.string.search_placeholder], color = palette.secondaryText) }, colors = fieldColors, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    visibleNotes.takeLast(5).reversed().forEach { note ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.weight(1f)) {
                                                Text(if (note.completed) "✓ ${note.title.ifBlank { text[R.string.untitled_note] }}" else "${priorityMark(note.priority)}${note.title.ifBlank { text[R.string.untitled_note] }}", fontSize = 20.sp, color = palette.primaryText)
                                                Text(note.body.take(44) + noteMeta(note, text), fontSize = 17.sp, color = palette.secondaryText)
                                            }
                                            Button(onClick = { startEditing(note); activeSection = AgendaSection.CREATE }) { Text(text[R.string.edit]) }
                                            Button(onClick = { val changed = note.copy(completed = !note.completed, updatedAt = System.currentTimeMillis()); repository.save(changed); notes = notes.map { if (it.uuid == note.uuid) changed else it } }) { Text(if (note.completed) text[R.string.restore] else text[R.string.complete]) }
                                            Button(onClick = { jobs += CoroutineScope(Dispatchers.Main).launch { WorldTrackingManager.removeAnchor(note.uuid); repository.remove(note.uuid); noteEntities.remove(note.uuid)?.destroy(true); notes = repository.load(); status = text[R.string.status_note_deleted] } }) { Text(text[R.string.delete]) }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text[R.string.move_note_hint], fontSize = 17.sp, color = palette.secondaryText, modifier = Modifier.weight(1f))
                                        Button(onClick = { notes.forEach { note -> jobs += CoroutineScope(Dispatchers.Main).launch { WorldTrackingManager.removeAnchor(note.uuid) } }; repository.clear(); notes = emptyList(); status = text[R.string.status_wall_cleared] }) { Text(text[R.string.clear_all]) }
                                    }
                                }
                                AgendaSection.APPEARANCE -> {
                                    Text(text[R.string.appearance], fontSize = 28.sp, color = palette.primaryText)
                                    Text(text[R.string.appearance_hint], fontSize = 18.sp, color = palette.secondaryText)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { palette = AgendaWallColors.midnight }) { Text(text[R.string.theme_midnight]) }
                                        Button(onClick = { palette = AgendaWallColors.warm }) { Text(text[R.string.theme_warm]) }
                                        Button(onClick = { palette = AgendaWallColors.ocean }) { Text(text[R.string.theme_ocean]) }
                                    }
                                    Text(text[R.string.spatial_actions], fontSize = 24.sp, color = palette.primaryText)
                                    Text(text[R.string.spatial_actions_hint], fontSize = 18.sp, color = palette.secondaryText)
                                }
                            }
                        }
                    }
                }
            }
            AttachmentPanel("AgendaReopenPanel") {
                PicoTheme { Button(onClick = { panelVisible = true }) { Text(text[R.string.open_wall]) } }
            }
        },
    )
}

@Composable
private fun NoteSurface(note: SavedNote, palette: AgendaPalette, preview: Boolean = false) {
    val text = AgendaText(LocalContext.current)
    PicoTheme {
        Box(Modifier.width(300.dp).background(palette.noteSurface, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note.title.ifBlank { text[R.string.untitled_note] }, fontSize = 22.sp, color = palette.noteInk)
                Text(richText(note, palette), fontSize = 24.sp, color = palette.noteInk)
                if (preview) Text(text[R.string.preview_hint], fontSize = 16.sp, color = palette.noteInk)
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
        // Keep the panel normal unchanged and put it just outside the card's
        // visible local -Z face. Flipping around Y would put the text inside
        // the real wall.
        setPosition(Vector3(0f, 0f, -0.032f))
        setEulerAngles(EulerAngles(0f, 0f, 0f))
        // AttachmentPanel's local X axis is mirrored relative to the card
        // surface. Flip only X so glyphs read normally without changing the
        // panel's front/back normal or moving it into the wall.
        setScaleVector(Vector3(-1f, 1f, 1f))
    }
}

private data class DetectedPlane(
    val position: Vector3,
    val rotation: Quat,
    val orientation: String,
    val semantic: String,
    val area: Float,
)

private fun refreshInteractionPlanes(
    root: Entity,
    detectedPlanes: Map<UUID, DetectedPlane>,
    planeEntities: MutableMap<UUID, Entity>,
) {
    val selected = detectedPlanes.entries
        .sortedWith(
            compareByDescending<Map.Entry<UUID, DetectedPlane>> { it.value.interactionPriority() }
                .thenByDescending { it.value.area }
                .thenBy { it.value.position.length() },
        )
        .take(MAX_INTERACTION_PLANES)
        .associate { it.key to it.value }

    planeEntities.keys.toList().filterNot { it in selected }.forEach { id ->
        planeEntities.remove(id)?.destroy(true)
    }

    selected.forEach { (id, plane) ->
        val entity = planeEntities[id] ?: Entity().apply {
            setName("DetectedSurface-$id")
            components.set(InteractableComponent())
            components.set(CollisionComponent(listOf(ShapeResource.createStaticMesh(MeshResource.loadFromPlaneAnchor(id))), PhysicsMaterialResource()))
            root.addChild(this)
            planeEntities[id] = this
        }
        entity.components[TransformComponent::class.java]?.apply {
            setPosition(root.convertPositionFrom(plane.position, null))
            setQuaternion(root.convertRotationFrom(plane.rotation, null))
        }
    }
}

private fun DetectedPlane.interactionPriority(): Int = when {
    orientation == "VERTICAL" -> 10_000
    semantic in setOf("WALL", "DOOR", "WINDOW", "OPENING", "WALL_ART", "SCREEN") -> 9_000
    semantic in setOf("FLOOR", "TABLE", "CEILING") -> 5_000
    else -> 1_000
}

private fun findNoteTarget(target: Entity, noteEntities: Map<UUID, Entity>): Pair<UUID, Entity>? {
    var current: Entity? = target
    while (current != null) {
        noteEntities.entries.firstOrNull { it.value == current }?.let { return it.key to it.value }
        current = current.getParent()
    }
    return null
}

private fun findPlaneTarget(target: Entity?, planeEntities: Map<UUID, Entity>): UUID? {
    var current = target
    while (current != null) {
        planeEntities.entries.firstOrNull { it.value == current }?.let { return it.key }
        current = current.getParent()
    }
    return null
}

private fun raycastPlanePosition(
    root: Entity,
    event: SpatialPointerInfo,
    planeId: UUID?,
    planeEntities: Map<UUID, Entity>,
): Vector3? {
    val scene = root.scene ?: return null
    val targetPlane = planeId ?: return null
    val rawPosition = event.inputDevicePose.rawPosition
    val rawRotation = event.inputDevicePose.rawRotation.toQuaternion()
    if (rawPosition.x == 0f && rawPosition.y == 0f && rawPosition.z == 0f) return null

    val worldOrigin = Vector3(rawPosition.x, rawPosition.y, rawPosition.z)
    val localOrigin = root.convertPositionFrom(worldOrigin, null)
    val localRotation = root.convertRotationFrom(rawRotation, null)
    val localDirection = localRotation.rotateVector(Vector3(0f, 0f, -1f)).normalize()
    val hits = scene.rayCast(
        localOrigin,
        localDirection,
        10f,
        CollisionCastHitMode.NEAREST,
        CollisionGroup(CollisionGroup.COLLISION_GROUP_DEFAULT),
        root,
    )
    return hits.results.firstOrNull { findPlaneTarget(it.entity, planeEntities) == targetPlane }?.position
}

private fun orientPlaneTowardViewer(plane: DetectedPlane, viewerWorldPosition: Vector3): Quat {
    if (viewerWorldPosition.length() < 1e-4f) return plane.rotation
    val frontNormal = plane.rotation.rotateVector(Vector3(0f, 0f, -1f))
    val toViewer = viewerWorldPosition - plane.position
    if (toViewer.length() < 1e-4f || Vector3.dot(frontNormal, toViewer) >= 0f) {
        return plane.rotation
    }
    // Flip only the surface frame once at placement time. The card and its
    // text remain fixed afterward; they do not track the viewer.
    return plane.rotation.times(EulerAngles(0f, 180f, 0f).toQuat()).normalize()
}

private fun addNoteEntity(
    root: Entity,
    note: SavedNote,
    noteEntities: MutableMap<UUID, Entity>,
    initialPosition: Vector3? = null,
    initialRotation: Quat? = null,
) {
    // PICO's world-anchor sample uses a regular Entity with an AnchorComponent.
    // The returned WorldAnchor pose is applied in the root's local space so the
    // first frame is already at the surface instead of the Stage origin.
    val anchorEntity = Entity().apply {
        setName("NoteAnchor-${note.uuid}")
        components[AnchorComponent::class.java] = AnchorComponent(AnchorTarget.createWorldAnchorTarget(note.uuid))
        components[TransformComponent::class.java]?.apply {
            initialPosition?.let(::setPosition)
            initialRotation?.let(::setQuaternion)
        }
    }
    addNoteVisual(anchorEntity, note, noteEntities)
    root.addChild(anchorEntity)
}

private fun addNoteVisual(root: Entity, note: SavedNote, noteEntities: MutableMap<UUID, Entity>, visible: Boolean = true) {
    val cardMaterial = UnlitMaterial.create().apply { setBaseColor(Color4.fromLinearHex("FFD166FF")) }
    val card = ModelEntity(MeshResource.createBox(Vector3(0.34f, 0.23f, 0.018f)), cardMaterial).apply {
        setName("NoteCard-${note.uuid}")
        enabled = visible
        components[TransformComponent::class.java]?.setPosition(Vector3(note.offsetX, note.offsetY, note.offsetZ))
        components.set(InteractableComponent())
        // Keep a forgiving grab volume around the thin visual card. The
        // collision box is intentionally a little wider/deeper than the mesh
        // so pinch/poke input can acquire the note reliably without changing
        // its rendered size or its surface attachment pose.
        components.set(
            CollisionComponent(
                listOf(ShapeResource.createBox(Vector3(0.38f, 0.27f, 0.05f))),
                PhysicsMaterialResource(),
            ),
        )
        // The card itself stays aligned with the plane normal. Only the text
        // attachment is allowed to face the viewer; rotating this surface
        // entity would make a wall note leave the wall (and a table note stand
        // up) even though its anchor position is correct.
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

private fun todayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

private fun priorityMark(priority: String): String = when (priority) {
    "high" -> "! "
    "low" -> "· "
    else -> ""
}

private fun priorityLabel(priority: String, text: AgendaText): String = when (priority) {
    "high" -> text[R.string.priority_high]
    "low" -> text[R.string.priority_low]
    else -> text[R.string.priority_normal]
}

private fun noteMeta(note: SavedNote, text: AgendaText): String = buildString {
    if (note.dueDate.isNotBlank()) append(text.format(R.string.note_due_date, note.dueDate))
    if (note.tags.isNotBlank()) append("  · #${note.tags.replace(",", " #")}")
}

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
    val dueDate: String = "",
    val priority: String = "normal",
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
            put("title", note.title); put("body", note.body); put("bold", note.bold); put("italic", note.italic); put("highlight", note.highlight); put("checklist", note.checklist); put("completed", note.completed); put("tags", note.tags); put("dueDate", note.dueDate); put("priority", note.priority); put("createdAt", note.createdAt); put("updatedAt", note.updatedAt); put("offsetX", note.offsetX); put("offsetY", note.offsetY); put("offsetZ", note.offsetZ)
        }.toString()).apply()
    }

    fun remove(uuid: UUID) { prefs.edit().remove("note_$uuid").apply() }
    fun clear() { prefs.edit().clear().apply() }

    private fun parse(uuid: UUID, raw: String): SavedNote = runCatching {
        val json = JSONObject(raw)
        SavedNote(uuid, json.optString("title", ""), json.optString("body", ""), json.optBoolean("bold"), json.optBoolean("italic"), json.optBoolean("highlight"), json.optBoolean("checklist"), json.optBoolean("completed"), json.optString("tags"), json.optLong("createdAt", System.currentTimeMillis()), json.optLong("updatedAt", System.currentTimeMillis()), json.optDouble("offsetX", 0.0).toFloat(), json.optDouble("offsetY", 0.0).toFloat(), json.optDouble("offsetZ", 0.0).toFloat(), json.optString("dueDate"), normalizePriority(json.optString("priority", "normal")))
    }.getOrElse {
        // Backward compatibility with the original note_<uuid> = plain text format.
        SavedNote(uuid, "", raw)
    }
}

private fun normalizePriority(priority: String): String = when (priority) {
    "高" -> "high"
    "低" -> "low"
    "普通" -> "normal"
    else -> priority.ifBlank { "normal" }
}
