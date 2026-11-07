package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.resolveEndpoint
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.isPhoneLayout
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: (Rect) -> Unit,
    onPickImage: () -> Unit,
    onToggleGalleryImage: (android.net.Uri) -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    onCameraButtonBoundsChange: (Rect) -> Unit = {},
    onPickFile: () -> Unit,
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.resolveEndpoint(settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    var cameraButtonBounds by remember { mutableStateOf(Rect.Zero) }
    var actionRowHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val phoneLayout = isPhoneLayout()

    FilesPickerSections(
        phoneLayout = phoneLayout,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        attachments = {
            FilesPickerMediaSections(
                phoneLayout = phoneLayout,
                recentPhotos = {
                    RecentGalleryImages(state = state, onToggle = onToggleGalleryImage, onDone = onDismiss)
                },
                workspaceCwd = {
                    WorkspaceCwdButton(
                        workspaces, assistant, conversation,
                        modifier = if (actionRowHeight > 0) Modifier.heightIn(
                            min = phoneWorkspaceFooterHeight(with(density) { actionRowHeight.toDp() }),
                        ) else Modifier,
                        onUpdateConversation = onUpdateConversation,
                    )
                },
                buttons = {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                            .onSizeChanged { if (phoneLayout) actionRowHeight = it.height },
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PhotoCaptureActions(
                            phoneLayout = phoneLayout,
                            cameraModifier = Modifier.onGloballyPositioned {
                                cameraButtonBounds = it.boundsInWindow()
                                onCameraButtonBoundsChange(cameraButtonBounds)
                            },
                            onPickImage = onPickImage,
                            onTakePic = { onTakePic(cameraButtonBounds) },
                        )

                        FilePickButton(onClick = onPickFile)

                        if (provider != null && provider is ProviderSetting.Google) {
                            VideoPickButton(onClick = onPickVideo)

                            AudioPickButton(onClick = onPickAudio)
                        }

                    }

                },
            )
        },
        tools = {
            if (workspaces.isNotEmpty()) {
                WorkspacePickerListItem(
                    assistant = assistant,
                    conversation = conversation,
                    workspaces = workspaces,
                    onUpdateAssistant = onUpdateAssistant,
                    onUpdateConversation = onUpdateConversation,
                    onNavigateToDetail = { id ->
                        onDismiss()
                        navController.navigateWithoutAnchor(Screen.WorkspaceDetail(id))
                    },
                    onNavigateToTerminal = { id ->
                        onDismiss()
                        navController.navigateWithoutAnchor(Screen.WorkspaceTerminal(id))
                    },
                    onNavigateToManage = {
                        onDismiss()
                        navController.navigateWithoutAnchor(Screen.Workspaces)
                    },
                )
            }

            AttachmentManagementTools(phoneLayout) {
                if (settings.mcpServers.isNotEmpty()) {
                    McpPickerListItem(
                        assistant = assistant,
                        servers = settings.mcpServers,
                        mcpManager = mcpManager,
                        onUpdateAssistant = onUpdateAssistant,
                    )
                }

                // 平板保留扩展入口，手机不再在附件面板显示。
                val modeAndLorebookCount =
                    if (assistant.allowConversationPromptInjection) {
                        conversation.modeInjectionIds.size + conversation.lorebookIds.size
                    } else {
                        assistant.modeInjectionIds.size + assistant.lorebookIds.size
                    }
                val activeCount =
                    assistant.quickMessageIds.size +
                        modeAndLorebookCount +
                        assistant.enabledSkills.size
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.Package,
                            contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_tab_extensions))
                    },
                    trailingContent = {
                        if (activeCount > 0) {
                            Text(
                                text = activeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable {
                            onShowInjectionSheetChange(true)
                        },
                )
            }

            if (!phoneLayout) {
                WorkspaceCwdButton(workspaces, assistant, conversation, onUpdateConversation = onUpdateConversation)
            }
        },
    )

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
            onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
        })
    }
}

// 尾区加上 8dp 间距，恰好补齐原按钮行及其 16dp 间距，使按钮行整体上移一行。
internal fun phoneWorkspaceFooterHeight(actionRowHeight: Dp): Dp = actionRowHeight + 8.dp

@Composable
private fun WorkspaceCwdButton(
    workspaces: List<WorkspaceEntity>,
    assistant: Assistant,
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onUpdateConversation: (Conversation) -> Unit,
) {
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
        var showCwdSheet by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showCwdSheet = true },
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Folder01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = conversation.workspaceCwd ?: "/workspace",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showCwdSheet) {
            WorkspaceCwdPickerSheet(
                workspaceId = boundWorkspace.id,
                currentCwd = conversation.workspaceCwd,
                onSelectCwd = { newCwd ->
                    onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                },
                onDismiss = { showCwdSheet = false },
            )
        }
    }
}

@Composable
internal fun FilesPickerMediaSections(
    phoneLayout: Boolean,
    recentPhotos: @Composable () -> Unit,
    workspaceCwd: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    if (phoneLayout) {
        // 媒体区内部使用紧凑间距，工作目录作为按钮行下方的尾部内容。
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recentPhotos()
            buttons()
            workspaceCwd()
        }
    } else {
        // 平板仍由外层列管理原有间距和顺序，工作目录留在工具区。
        buttons()
        recentPhotos()
    }
}

@Composable
internal fun AttachmentManagementTools(phoneLayout: Boolean, content: @Composable () -> Unit) {
    if (!phoneLayout) content()
}

@Composable
internal fun PhotoCaptureActions(
    phoneLayout: Boolean,
    cameraModifier: Modifier = Modifier,
    onPickImage: () -> Unit,
    onTakePic: () -> Unit,
) {
    if (phoneLayout) ImagePickButton(onClick = onPickImage)
    TakePicButton(modifier = cameraModifier, onLaunchCamera = onTakePic)
    if (!phoneLayout) ImagePickButton(onClick = onPickImage)
}

@Composable
internal fun FilesPickerSections(
    phoneLayout: Boolean,
    modifier: Modifier = Modifier,
    attachments: @Composable ColumnScope.() -> Unit,
    tools: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 手机将拍照和最近照片放到下方，平板严格保留原有顺序。
        if (phoneLayout) tools() else attachments()
        HorizontalDivider(Modifier.fillMaxWidth())
        if (phoneLayout) attachments() else tools()
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = stringResource(R.string.assistant_page_workspace),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.assistant_page_workspace))
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { showSheet = true },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                modifier = Modifier.weight(1f),
                onNavigateToQuickMessages = {
                    onDismissAll()
                    navController.navigateWithoutAnchor(Screen.QuickMessages)
                },
                onNavigateToPrompts = {
                    onDismissAll()
                    navController.navigateWithoutAnchor(Screen.Prompts)
                },
                onNavigateToSkills = {
                    onDismissAll()
                    navController.navigateWithoutAnchor(Screen.Skills)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(modifier: Modifier = Modifier, onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(modifier = modifier, icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
