package mihon.feature.volumes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asks whether the entries of a manga are chapters or volumes and, for volumes, which chapters each volume collects.
 */
@Composable
fun VolumeFormatDialog(
    mangaId: Long,
    onDismissRequest: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<VolumeFormatViewModel, VolumeFormatViewModel.Factory> {
        create(mangaId = mangaId)
    }
    // Read again each time the dialog opens so that edits left without saving are dropped
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.volumeFormat_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormatOption(
                    selected = state.format == ContentFormat.CHAPTERS,
                    title = stringResource(MR.strings.volumeFormat_chapters),
                    summary = stringResource(MR.strings.volumeFormat_chapters_summary),
                    onClick = { viewModel.setFormat(ContentFormat.CHAPTERS) },
                )
                FormatOption(
                    selected = state.format == ContentFormat.VOLUMES,
                    title = stringResource(MR.strings.volumeFormat_volumes),
                    summary = stringResource(MR.strings.volumeFormat_volumes_summary),
                    onClick = { viewModel.setFormat(ContentFormat.VOLUMES) },
                )
                if (state.format == ContentFormat.VOLUMES) {
                    VolumeMapping(
                        state = state,
                        onQueryChange = viewModel::setQuery,
                        onSearch = viewModel::search,
                        onEditRow = viewModel::editRow,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.isLoaded && !state.isSearching,
                onClick = { viewModel.save(onSaved = onDismissRequest) },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun FormatOption(
    selected: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VolumeMapping(
    state: VolumeFormatViewModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onEditRow: (volume: String, from: String, to: String) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = { Text(text = stringResource(MR.strings.volumeFormat_searchAs)) },
        supportingText = { Text(text = stringResource(MR.strings.volumeFormat_searchHint)) },
        singleLine = true,
        enabled = !state.isSearching,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        trailingIcon = {
            IconButton(onClick = onSearch, enabled = !state.isSearching) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Search,
                    contentDescription = stringResource(MR.strings.action_search),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.isSearching) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text = stringResource(MR.strings.volumeFormat_searching), style = MaterialTheme.typography.bodySmall)
        return
    }

    val sources = listOfNotNull(
        state.wikipediaPage?.let { "Wikipedia ($it)" },
        state.fandomPage?.let { "Fandom (${it.substringAfter("/wiki/").replace('_', ' ')})" },
        state.mangaUpdatesId?.let { "MangaUpdates" },
    )
    if (sources.isNotEmpty() && state.hasSearched) {
        Text(
            text = stringResource(MR.strings.volumeFormat_source, sources.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.foundNothing) {
        Text(
            text = stringResource(MR.strings.volumeFormat_notFound),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (state.missingVolumes.isNotEmpty() && state.hasSearched) {
        Text(
            text = stringResource(MR.strings.volumeFormat_missing, state.missingVolumes.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    state.rows.forEach { row ->
        VolumeRow(row = row, onEdit = { from, to -> onEditRow(row.volume, from, to) })
    }
}

@Composable
private fun VolumeRow(
    row: VolumeFormatViewModel.Row,
    onEdit: (from: String, to: String) -> Unit,
) {
    val isMissing = row.toChapters() == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.strings.volumeFormat_volume, row.volume),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            row.source?.let { source ->
                Text(
                    text = when (source) {
                        VolumeSource.MANGAUPDATES -> "MangaUpdates"
                        VolumeSource.WIKIPEDIA -> "Wikipedia"
                        VolumeSource.FANDOM -> "Fandom"
                        VolumeSource.MANUAL -> stringResource(MR.strings.action_edit)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ChapterField(
            value = row.from,
            label = stringResource(MR.strings.volumeFormat_from),
            isError = isMissing,
            onValueChange = { onEdit(it, row.to) },
            modifier = Modifier.weight(1f),
        )
        ChapterField(
            value = row.to,
            label = stringResource(MR.strings.volumeFormat_to),
            isError = isMissing,
            onValueChange = { onEdit(row.from, it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChapterField(
    value: String,
    label: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(5)) },
        label = { Text(text = label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
