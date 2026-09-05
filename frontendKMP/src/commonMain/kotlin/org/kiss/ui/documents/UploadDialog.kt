package org.kiss.ui.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import org.kiss.data.PersonDto
import org.kiss.data.TagDto
import org.kiss.entity.AutocompleteEntity
import org.kiss.frontendkmp.generated.resources.Res
import org.kiss.frontendkmp.generated.resources.close
import org.kiss.frontendkmp.generated.resources.create
import org.kiss.frontendkmp.generated.resources.new_party_emails
import org.kiss.frontendkmp.generated.resources.new_party_name
import org.kiss.frontendkmp.generated.resources.new_tag_name
import org.kiss.frontendkmp.generated.resources.quick_create_party
import org.kiss.frontendkmp.generated.resources.quick_create_tag
import org.kiss.frontendkmp.generated.resources.upload_collections
import org.kiss.frontendkmp.generated.resources.upload_file
import org.kiss.frontendkmp.generated.resources.upload_parties
import org.kiss.frontendkmp.generated.resources.upload_pick_file
import org.kiss.frontendkmp.generated.resources.upload_tags
import org.kiss.frontendkmp.generated.resources.upload_title
import org.kiss.frontendkmp.generated.resources.upload_title_label
import org.kiss.ui.components.AutocompleteField

/**
 * Upload dialog: pick a file, name it, and attach parties / tags / collections.
 * The dialog closes on submit; post-upload feedback (snackbar/tab switch) is
 * handled by the caller via [UploadState] transitions.
 *
 * The dialog is a pure "pick + metadata" surface — it owns no UploadState
 * lifecycle. It receives the current state only to know when to show a
 * spinner (reading) and when the confirm button is enabled.
 */
@Composable
fun UploadDialog(
    visible: Boolean,
    stagedFile: org.kiss.data.PickedFile?,
    onRequestFilePick: () -> Unit,
    onUpload: (title: String, partyIds: List<Long>, tagIds: List<Long>, collectionIds: List<Long>) -> Unit,
    onQuickCreateParty: (name: String, emails: List<String>, onResult: (PersonDto?) -> Unit) -> Unit,
    onQuickCreateTag: (name: String, onResult: (TagDto?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    partyLoader: suspend (String) -> List<AutocompleteEntity>,
    tagLoader: suspend (String) -> List<AutocompleteEntity>,
    collectionLoader: suspend (String) -> List<AutocompleteEntity>,
) {
    if (!visible) return

    var title by remember { mutableStateOf("") }

    var parties by remember { mutableStateOf<List<AutocompleteEntity>>(emptyList()) }
    var tags by remember { mutableStateOf<List<AutocompleteEntity>>(emptyList()) }
    var collections by remember { mutableStateOf<List<AutocompleteEntity>>(emptyList()) }

    var newPartyName by remember { mutableStateOf("") }
    var newPartyEmails by remember { mutableStateOf("") }
    var creatingParty by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    var creatingTag by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(Res.string.upload_title)) },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = maxHeight),
                ) {
                    if (stagedFile != null) {
                        FilePickerRow(stagedFile, onRequestPick = onRequestFilePick)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(Res.string.upload_title_label)) },
                            placeholder = { Text(stagedFile.name) },
                        )
                        HorizontalDivider()
                        AutoFieldGroup(
                            label = stringResource(Res.string.upload_parties),
                            selected = parties,
                            onSelectedChange = { parties = it },
                            loadOptions = partyLoader,
                        )
                        QuickCreatePartyRow(
                            name = newPartyName,
                            emails = newPartyEmails,
                            creating = creatingParty,
                            onNameChange = { newPartyName = it },
                            onEmailsChange = { newPartyEmails = it },
                            onCreate = {
                                creatingParty = true
                                onQuickCreateParty(newPartyName.trim(), newPartyEmails.split(',')) { created ->
                                    creatingParty = false
                                    if (created != null) {
                                        parties = parties + AutocompleteEntity(created.id, created.name)
                                        newPartyName = ""
                                        newPartyEmails = ""
                                    }
                                }
                            },
                        )
                        AutoFieldGroup(
                            label = stringResource(Res.string.upload_tags),
                            selected = tags,
                            onSelectedChange = { tags = it },
                            loadOptions = tagLoader,
                        )
                        QuickCreateTagRow(
                            name = newTagName,
                            creating = creatingTag,
                            onNameChange = { newTagName = it },
                            onCreate = {
                                creatingTag = true
                                onQuickCreateTag(newTagName.trim()) { created ->
                                    creatingTag = false
                                    if (created != null) {
                                        tags = tags + AutocompleteEntity(created.id, created.name)
                                        newTagName = ""
                                    }
                                }
                            },
                        )
                        AutoFieldGroup(
                            label = stringResource(Res.string.upload_collections),
                            selected = collections,
                            onSelectedChange = { collections = it },
                            loadOptions = collectionLoader,
                        )
                    } else {
                        FilePickerRow(null, onRequestPick = onRequestFilePick)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onUpload(
                        title,
                        parties.map { it.id },
                        tags.map { it.id },
                        collections.map { it.id },
                    )
                },
                enabled = stagedFile != null,
            ) { Text(stringResource(Res.string.upload_title)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )
}

@Composable
private fun AutoFieldGroup(
    label: String,
    selected: List<AutocompleteEntity>,
    onSelectedChange: (List<AutocompleteEntity>) -> Unit,
    loadOptions: suspend (String) -> List<AutocompleteEntity>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        AutocompleteField(
            loadOptions = loadOptions,
            selected = selected,
            onSelectedChange = onSelectedChange,
        )
    }
}

@Composable
private fun QuickCreatePartyRow(
    name: String,
    emails: String,
    creating: Boolean,
    onNameChange: (String) -> Unit,
    onEmailsChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(Res.string.quick_create_party),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.new_party_name)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = emails,
                onValueChange = onEmailsChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.new_party_emails)) },
                modifier = Modifier.weight(1f),
            )
            CreateButton(enabled = name.isNotBlank() && !creating, creating = creating, onCreate = onCreate)
        }
    }
}

@Composable
private fun QuickCreateTagRow(
    name: String,
    creating: Boolean,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(Res.string.quick_create_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.new_tag_name)) },
                modifier = Modifier.weight(1f),
            )
            CreateButton(enabled = name.isNotBlank() && !creating, creating = creating, onCreate = onCreate)
        }
    }
}

@Composable
private fun CreateButton(enabled: Boolean, creating: Boolean, onCreate: () -> Unit) {
    TextButton(onClick = onCreate, enabled = enabled) {
        if (creating) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(Res.string.create))
        }
    }
}

@Composable
private fun FilePickerRow(
    effectiveFile: org.kiss.data.PickedFile?,
    onRequestPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (effectiveFile != null) {
            Text(
                stringResource(Res.string.upload_file, effectiveFile.name, effectiveFile.mimeType),
            )
            TextButton(onClick = onRequestPick) { Text(stringResource(Res.string.close)) }
        } else {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(
                stringResource(Res.string.upload_pick_file),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRequestPick() }
                    .padding(4.dp),
            )
        }
    }
}