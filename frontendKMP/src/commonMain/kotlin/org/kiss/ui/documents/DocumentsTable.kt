package org.kiss.ui.documents

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.kiss.entity.DocumentType
import org.kiss.entity.DocumentRowDto
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.column_created
import org.kiss.frontendkmp.generated.resources.column_last_update
import org.kiss.frontendkmp.generated.resources.column_pages
import org.kiss.frontendkmp.generated.resources.column_parties
import org.kiss.frontendkmp.generated.resources.column_tags
import org.kiss.frontendkmp.generated.resources.column_title
import org.kiss.frontendkmp.generated.resources.column_type
import org.kiss.frontendkmp.generated.resources.no_documents
import org.kiss.frontendkmp.generated.resources.sorted_ascending
import org.kiss.frontendkmp.generated.resources.sorted_descending
import org.kiss.ui.components.AutocompleteField
import org.kiss.ui.components.EntityChip
import org.kiss.ui.components.HoverTooltip
import org.kiss.utils.formatDate

private data class ColumnSpec(
    val key: String,
    val labelRes: StringResource,
    val width: Dp,
    val numeric: Boolean = false,
    val sortable: Boolean = true,
)

private val COLUMNS = listOf(
    ColumnSpec("createdAt", Res.string.column_created, 170.dp),
    ColumnSpec("updatedAt", Res.string.column_last_update, 170.dp),
    ColumnSpec("title", Res.string.column_title, 320.dp),
    ColumnSpec("type", Res.string.column_type, 200.dp),
    ColumnSpec("pages", Res.string.column_pages, 90.dp, numeric = true),
    ColumnSpec("parties", Res.string.column_parties, 250.dp, sortable = false),
    ColumnSpec("tags", Res.string.column_tags, 250.dp, sortable = false),
)

private val TABLE_WIDTH: Dp = COLUMNS.fold(0.dp) { acc, column -> acc + column.width }
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 92.dp

private val TYPE_ICONS = mapOf(
    DocumentType.DOCUMENT to Icons.Outlined.Description,
    DocumentType.IMAGE to Icons.Outlined.Image,
    DocumentType.PDF to Icons.Outlined.PictureAsPdf,
)

private enum class SortDirection { ASC, DESC }

private fun columnKeyOf(key: String): SortKey = when (key) {
    "createdAt" -> SortKey.CREATED_AT
    "updatedAt" -> SortKey.UPDATED_AT
    "title" -> SortKey.TITLE
    "type" -> SortKey.TYPE
    "pages" -> SortKey.PAGES
    else -> SortKey.CREATED_AT
}

@Composable
fun DocumentsTable(viewModel: DocumentsViewModel) {
    val visibleRows by viewModel.rows.collectAsStateWithLifecycle()
    val sortKey by viewModel.sortKey.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val userSortActive by viewModel.userSortActive.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val partyFilter by viewModel.partyFilter.collectAsStateWithLifecycle()
    val tagFilter by viewModel.tagFilter.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val referenceData = viewModel.referenceData

    // A column is "active" only when the user explicitly chose it. During a
    // relevance-ordered search the default Created header stays inactive.
    fun columnActive(k: SortKey) = userSortActive && sortKey == k

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        stickyHeader(key = "header") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(horizontalState)) {
                    Row(
                        modifier = Modifier
                            .width(TABLE_WIDTH)
                            .height(HEADER_HEIGHT),
                    ) {
                        COLUMNS.forEach { column ->
                            val key = column.key
                            val label = stringResource(column.labelRes)
                            when (key) {
                                "type" -> TypeHeaderCell(
                                    label = label,
                                    width = column.width,
                                    active = columnActive(SortKey.TYPE) || typeFilter.isNotEmpty(),
                                    direction = if (sortAscending) SortDirection.ASC else SortDirection.DESC,
                                    onSort = { viewModel.toggleSort(SortKey.TYPE) },
                                    filter = {
                                        AutocompleteField(
                                            loadOptions = { q -> referenceData.getDocumentTypes(q) },
                                            selected = typeFilter,
                                            onSelectedChange = viewModel::setTypeFilter,
                                        )
                                    },
                                )

                                "parties" -> FilterHeaderCell(label, column.width) {
                                    AutocompleteField(
                                        loadOptions = { q -> referenceData.getParties(q) },
                                        selected = partyFilter,
                                        onSelectedChange = viewModel::setPartyFilter,
                                    )
                                }

                                "tags" -> FilterHeaderCell(label, column.width) {
                                    AutocompleteField(
                                        loadOptions = { q -> referenceData.getTags(q) },
                                        selected = tagFilter,
                                        onSelectedChange = viewModel::setTagFilter,
                                    )
                                }

                                else -> SortableHeaderCell(
                                    label = label,
                                    width = column.width,
                                    numeric = column.numeric,
                                    active = columnActive(columnKeyOf(key)),
                                    direction = if (sortAscending) SortDirection.ASC else SortDirection.DESC,
                                    onSort = { viewModel.toggleSort(columnKeyOf(key)) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        itemsIndexed(visibleRows, key = { _, row -> row.id }) { index, row ->
            TableRowContent(
                row = row,
                zebra = index % 2 == 1,
                horizontalState = horizontalState,
                onClick = { viewModel.openPreview(row) },
                onDownload = { viewModel.download(row) },
            )
        }

        if (visibleRows.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.no_documents),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SortableHeaderCell(
    label: String,
    width: Dp,
    numeric: Boolean,
    active: Boolean,
    direction: SortDirection,
    onSort: () -> Unit,
) {
    Box(
        modifier = Modifier.width(width).fillMaxHeight(),
        contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        SortableLabel(label, active, direction, onSort)
    }
}

@Composable
private fun SortableLabel(
    label: String,
    active: Boolean,
    direction: SortDirection,
    onSort: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onSort).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active) {
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = stringResource(
                    if (direction == SortDirection.ASC) Res.string.sorted_ascending else Res.string.sorted_descending,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (direction == SortDirection.DESC) 180f else 0f),
            )
        }
    }
}

@Composable
private fun TypeHeaderCell(
    label: String,
    width: Dp,
    active: Boolean,
    direction: SortDirection,
    onSort: () -> Unit,
    filter: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SortableLabel(label, active, direction, onSort)
        filter()
    }
}

@Composable
private fun FilterHeaderCell(
    label: String,
    width: Dp,
    filter: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        filter()
    }
}

@Composable
private fun TableRowContent(
    row: DocumentRowDto,
    zebra: Boolean,
    horizontalState: ScrollState,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(horizontalState)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(TABLE_WIDTH)
                .height(ROW_HEIGHT)
                .background(
                    if (zebra) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surface,
                )
                .clickable(onClick = onClick),
        ) {
            COLUMNS.forEach { column ->
                when (column.key) {
                    "createdAt" -> TextCell(column.width, formatDate(row.createdAt))
                    "updatedAt" -> TextCell(column.width, formatDate(row.updatedAt))
                    "title" -> TextCell(column.width, row.title)
                    "type" -> TypeCell(column.width, row.type)
                    "pages" -> TextCell(column.width, row.pages.toString(), numeric = true)
                    "parties" -> ChipRowCell(column.width, row.parties.map { it.name })
                    "tags" -> ChipRowCell(column.width, row.tags.map { it.name })
                }
            }
            IconButton(onClick = onDownload, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Download ${row.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TextCell(width: Dp, text: String, numeric: Boolean = false) {
    Box(
        modifier = Modifier.width(width).fillMaxHeight(),
        contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun TypeCell(width: Dp, type: DocumentType) {
    Box(
        modifier = Modifier.width(width).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val icon: ImageVector = TYPE_ICONS.getValue(type)
        HoverTooltip(text = type.name) {
            Icon(
                icon,
                contentDescription = type.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChipRowCell(width: Dp, names: List<String>) {
    Row(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        names.forEach { name ->
            EntityChip(name)
        }
    }
}