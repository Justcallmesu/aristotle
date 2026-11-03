package io.github.justcallmesu.aristotle.ui.chat

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.AttachmentFiles
import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.AttachmentProblem
import io.github.justcallmesu.aristotle.data.chat.AttachmentRules
import io.github.justcallmesu.aristotle.data.chat.AttachmentStatus
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.Upload
import io.github.justcallmesu.aristotle.data.chat.UserAttachment
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chip thumbnails in the composer, and bubble thumbnails. */
private val CHIP_THUMB: Dp = 40.dp
private val BUBBLE_THUMB: Dp = 96.dp
private val CHIP_TEXT_MAX: Dp = 160.dp

/**
 * The composer's attach button (T16): Photo (the system photo picker, no storage permission), Take photo (the camera
 * app writes into this app's cache through its FileProvider; no CAMERA permission, the app declares none) and File
 * (the document picker). Each result goes to [ComposerActions.attach].
 */
@Composable
fun AttachButton(enabled: Boolean, actions: ComposerActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    var cameraTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentRules.MAX_ATTACHMENTS)) { uris ->
        if (uris.isNotEmpty()) actions.attach(uris.map(Uri::toString), PickKind.Photo)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) actions.attach(uris.map(Uri::toString), PickKind.File)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = cameraTarget
        cameraTarget = null
        if (taken && target != null) actions.attach(listOf(target), PickKind.Camera) else if (target != null) actions.cameraCancelled(target)
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(painter = painterResource(R.drawable.ic_attach_file), contentDescription = stringResource(R.string.attach_open))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuRow(R.drawable.ic_image, R.string.attach_photo) {
                open = false
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            MenuRow(R.drawable.ic_photo_camera, R.string.attach_camera) {
                open = false
                val target = actions.cameraTarget()
                if (target == null) {
                    actions.cameraUnavailable()
                } else {
                    cameraTarget = target
                    try {
                        camera.launch(target.toUri())
                    } catch (e: ActivityNotFoundException) {
                        cameraTarget = null
                        actions.cameraCancelled(target)
                        actions.cameraUnavailable()
                    }
                }
            }
            MenuRow(R.drawable.ic_description, R.string.attach_file) {
                open = false
                files.launch(arrayOf("*/*"))
            }
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(Sizes.toolIcon)) },
        onClick = onClick,
    )
}

/** The picked items above the field: thumbnail or file icon, name, size (or why it cannot go), ✕. */
@Composable
fun AttachmentChips(attachments: List<Attachment>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        attachments.forEach { ComposerChip(it, onRemove) }
    }
}

@Composable
private fun ComposerChip(item: Attachment, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = item.status
    val refused = status is AttachmentStatus.Refused
    Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.s)) {
            Thumb(image = item.image, sha256 = item.prepared?.sha256, size = CHIP_THUMB)
            Column(modifier = Modifier.padding(start = Spacing.s).widthIn(max = CHIP_TEXT_MAX)) {
                Text(
                    text = item.name.ifEmpty { stringResource(R.string.attach_preparing) },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = when (status) {
                    AttachmentStatus.Preparing -> stringResource(R.string.attach_preparing)
                    AttachmentStatus.Ready -> Formatter.formatShortFileSize(context, item.prepared?.size ?: 0)
                    is AttachmentStatus.Refused -> problemText(status.problem)
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (refused) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (status == AttachmentStatus.Preparing) RunningSpinner(modifier = Modifier.padding(start = Spacing.s))
            IconButton(onClick = { onRemove(item.id) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.attach_remove, item.name),
                    modifier = Modifier.size(Sizes.toolIcon),
                )
            }
        }
    }
}

@Composable
private fun problemText(problem: AttachmentProblem): String {
    val context = LocalContext.current
    return when (problem) {
        is AttachmentProblem.TooLarge -> {
            val limit = Formatter.formatShortFileSize(context, problem.limit)
            problem.size?.let { stringResource(R.string.attach_too_large_size, Formatter.formatShortFileSize(context, it), limit) }
                ?: stringResource(R.string.attach_too_large, limit)
        }
        AttachmentProblem.Unreadable -> stringResource(R.string.attach_unreadable)
        AttachmentProblem.OutOfMemory -> stringResource(R.string.attach_too_large_prepare)
    }
}

/** One attachment as a user bubble shows it; [upload] only while this phone is sending it. */
data class BubbleAttachment(val kind: UserAttachment.Kind, val label: String, val sha256: String?, val upload: Upload? = null)

fun UserAttachment.toBubble() = BubbleAttachment(kind, label, sha256)

fun Attachment.toBubble() = BubbleAttachment(
    if (image) UserAttachment.Kind.Image else UserAttachment.Kind.File,
    name,
    prepared?.sha256,
    upload,
)

/**
 * A message's attachments (T16): an image this phone has a thumbnail of shows it, any other image or file is a chip
 * with its name (the relay carries no file reads, so an image sent from the Desktop shows by name).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleAttachments(items: List<BubbleAttachment>, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items.forEach { BubbleItem(it) }
    }
}

@Composable
private fun BubbleItem(item: BubbleAttachment) {
    val thumb = if (item.kind == UserAttachment.Kind.Image) rememberThumbnail(item.sha256) else null
    val description = stringResource(if (item.kind == UserAttachment.Kind.Image) R.string.attach_image else R.string.attach_file_named, item.label)
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(BUBBLE_THUMB).clip(MaterialTheme.shapes.small),
            )
        } else {
            Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = Sizes.chipHeight).padding(horizontal = Spacing.m),
                ) {
                    Icon(
                        painter = painterResource(if (item.kind == UserAttachment.Kind.Image) R.drawable.ic_image else R.drawable.ic_description),
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.chipIcon),
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.s).widthIn(max = CHIP_TEXT_MAX),
                    )
                }
            }
        }
        item.upload?.let { UploadLine(it) }
    }
}

@Composable
private fun UploadLine(upload: Upload) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (upload) {
        Upload.Waiting -> stringResource(R.string.attach_waiting) to colors.onSurfaceVariant
        Upload.Sending -> stringResource(R.string.attach_uploading) to colors.onSurfaceVariant
        Upload.Sent -> stringResource(R.string.attach_uploaded) to colors.onSurfaceVariant
        Upload.Failed -> stringResource(R.string.attach_upload_failed) to colors.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
        if (upload == Upload.Sending) RunningSpinner()
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** A chip's leading square: the thumbnail when there is one, else an image or file icon. */
@Composable
private fun Thumb(image: Boolean, sha256: String?, size: Dp) {
    val thumb = if (image) rememberThumbnail(sha256) else null
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (thumb != null) {
            Image(bitmap = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(MaterialTheme.shapes.small))
        } else {
            Icon(
                painter = painterResource(if (image) R.drawable.ic_image else R.drawable.ic_description),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.fieldIcon),
            )
        }
    }
}

/** The thumbnail this phone kept for the image whose bytes hash to [sha256], read off the main thread. */
@Composable
private fun rememberThumbnail(sha256: String?): ImageBitmap? {
    val cacheDir = LocalContext.current.cacheDir
    val thumb by produceState<ImageBitmap?>(null, sha256) {
        value = sha256?.let { sha ->
            withContext(Dispatchers.IO) {
                AttachmentFiles.thumbFile(cacheDir, sha).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
        }
    }
    return thumb
}
