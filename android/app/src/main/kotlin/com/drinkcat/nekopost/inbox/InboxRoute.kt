package com.drinkcat.nekopost.inbox

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.drinkcat.nekopost.R
import com.drinkcat.nekopost.core.ui.fold.FoldState
import com.drinkcat.nekopost.core.ui.fold.Posture
import com.drinkcat.nekopost.inbox.notify.InboxNotifier
import com.drinkcat.nekopost.inbox.ui.InboxActions
import com.drinkcat.nekopost.inbox.ui.InboxScreen
import com.drinkcat.nekopost.inbox.ui.InboxSubScreen
import com.drinkcat.nekopost.inbox.ui.InboxUiState
import com.drinkcat.nekopost.inbox.ui.InboxUnpaired
import com.drinkcat.nekopost.inbox.ui.KeyboardShortcutsPane
import com.drinkcat.nekopost.inbox.ui.ReceiveDisabledPane
import com.drinkcat.nekopost.lock.LocalAppLock
import com.drinkcat.nekopost.system.LocalExternalLauncher
import com.drinkcat.nekopost.system.SensitiveClipboard
import com.drinkcat.nekopost.system.SystemIntents
import com.drinkcat.nekopost.ui.shell.InboxMemory
import com.drinkcat.nekopost.ui.shell.PaneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stateful 消息 tab: connects [DeviceInbox] (list, detail, read state, live
 * updates) and [InboxMemory] (selection, search, filter, tool) to the
 * stateless [InboxScreen]. Nothing here is lost when the app locks: the
 * content stays composed behind the lock screen.
 *
 * @param appScope outlives this screen (pairing must not stop when the tab changes).
 * @param adjacent the second window opened with 在旁边打开 (no tools, no adjacent of its own).
 */
@Composable
internal fun InboxRoute(
    inbox: DeviceInbox,
    fold: FoldState,
    mode: PaneMode,
    memory: InboxMemory,
    appScope: CoroutineScope,
    envelopeEnabled: Boolean,
    serverHint: String?,
    onOpenSettings: () -> Unit,
    adjacent: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = LocalExternalLauncher.current
    val state by inbox.state.collectAsStateWithLifecycle()
    val lock = LocalAppLock.current
    val locked = lock.state.hidesContent
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    PostureFeedback(fold.posture)

    LaunchedEffect(inbox) { if (inbox.state.value.paired == null) inbox.load() }
    // Live updates while the app is in front (server-sent events); FCM covers the rest.
    LaunchedEffect(inbox, state.canRead, state.deviceId) {
        if (state.canRead) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { inbox.trackForeground() }
    }
    LaunchedEffect(inbox) { inbox.updates.collect { inbox.refresh() } }
    // The open message (also after a notification tap or process death).
    LaunchedEffect(memory.selectedId, state.canRead) {
        val id = memory.selectedId ?: return@LaunchedEffect
        if (state.canRead && state.detail?.id != id) inbox.open(id)
    }

    var notificationsBlocked by remember { mutableStateOf(!InboxNotifier.canPost(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsBlocked = !InboxNotifier.canPost(context)
        onPauseOrDispose { }
    }

    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_500)
            toast = null
        }
    }
    var copiedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedId) {
        if (copiedId != null) {
            delay(5_000)
            copiedId = null
        }
    }

    // ⑫ Unfolding from the cover display with an unread letter waiting: the envelope card.
    var envelopeId by remember { mutableStateOf<String?>(null) }
    var lastPosture by remember { mutableStateOf(fold.posture) }
    LaunchedEffect(fold.posture) {
        val opened = lastPosture == Posture.Cover && (fold.posture == Posture.Flat || fold.posture == Posture.Book)
        if (opened && envelopeEnabled && memory.selectedId == null) envelopeId = state.rows.firstOrNull { !it.read }?.id
        if (fold.posture == Posture.Cover) envelopeId = null
        lastPosture = fold.posture
    }

    // Reading a message for a second marks it read (and clears its notification), once per message.
    val autoRead = remember { mutableSetOf<String>() }
    val shownDetail = state.detail?.takeIf { it.id == memory.selectedId }
    LaunchedEffect(shownDetail?.id, shownDetail?.read, locked) {
        val d = shownDetail ?: return@LaunchedEffect
        if (d.read || locked || d.id in autoRead) return@LaunchedEffect
        delay(1_000)
        autoRead += d.id
        inbox.setRead(d.id, true)
    }

    val copiedText = stringResource(R.string.inbox_code_copied_toast)
    val codeMissing = stringResource(R.string.inbox_code_missing)
    val bodyCopied = stringResource(R.string.inbox_body_copied)
    val clipLabel = stringResource(R.string.inbox_code_clip_label)
    val bodyClipLabel = stringResource(R.string.inbox_body_clip_label)

    if (state.paired == false || (state.revoked && !state.canRead)) {
        if (memory.subScreen == InboxSubScreen.Pair) {
            PairThisPhoneRoute(inbox, serverHint, appScope, onBack = { memory.subScreen = InboxSubScreen.None })
        } else {
            InboxUnpaired(revoked = state.revoked, onPair = { memory.subScreen = InboxSubScreen.Pair })
        }
        return
    }
    if (state.paired == true && !state.canRead) {
        ReceiveDisabledPane(onOpenSettings)
        return
    }

    when (memory.subScreen) {
        InboxSubScreen.Pair -> {
            // Already paired (e.g. the request came from settings after pairing finished).
            LaunchedEffect(Unit) { memory.subScreen = InboxSubScreen.None }
        }
        InboxSubScreen.Sorting -> {
            SortingDesk(inbox, fold) { memory.subScreen = InboxSubScreen.None }
            return
        }
        InboxSubScreen.Watch -> {
            WatchDesk(inbox, state, fold) { memory.subScreen = InboxSubScreen.None }
            return
        }
        InboxSubScreen.DropNote -> {
            DropNote(inbox) { memory.subScreen = InboxSubScreen.None }
            return
        }
        InboxSubScreen.Shortcuts -> {
            KeyboardShortcutsPane(onBack = { memory.subScreen = InboxSubScreen.None })
            return
        }
        InboxSubScreen.None -> Unit
    }

    val configuration = LocalConfiguration.current
    val ui = InboxUiState(
        rows = state.rows,
        unreadCount = state.unreadCount ?: state.rows.count { !it.read },
        query = memory.query,
        searchedQuery = state.searchQuery,
        filter = memory.filter,
        selectedId = memory.selectedId,
        detail = shownDetail,
        detailLoading = state.detailLoading,
        loading = state.loading || state.paired == null,
        refreshing = state.refreshing,
        loadingMore = state.loadingMore,
        hasMore = state.hasMore,
        offlineSince = state.offlineSince,
        error = state.error,
        revoked = state.revoked,
        notificationsBlocked = notificationsBlocked,
        canAdmin = state.canAdmin && !adjacent,
        canSend = state.canSend && !adjacent,
        hardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY,
        toast = toast,
        envelope = envelopeId?.let { id -> state.rows.firstOrNull { it.id == id && !it.read } },
        copiedId = copiedId,
        tools = !adjacent,
    )

    fun open(id: String) {
        envelopeId = null
        memory.selectedId = id
        scope.launch { inbox.open(id) }
    }

    InboxScreen(
        state = ui,
        fold = fold,
        mode = mode,
        listState = memory.listState,
        actions = InboxActions(
            onOpen = ::open,
            onBack = {
                memory.selectedId = null
                inbox.hideDetail()
            },
            onRefresh = { scope.launch { inbox.refresh() } },
            onLoadMore = { scope.launch { inbox.loadMore() } },
            onQueryChange = { memory.query = it },
            onSearch = { scope.launch { inbox.search(memory.query) } },
            onClearSearch = {
                memory.query = ""
                if (state.searchQuery != null) scope.launch { inbox.search("") }
            },
            onFilter = { memory.filter = it },
            onSetRead = { id, read ->
                if (!read) autoRead += id
                scope.launch { inbox.setRead(id, read) }
            },
            onCopyCode = { id ->
                scope.launch {
                    val code = inbox.codeFor(id)
                    if (code == null) {
                        toast = codeMissing
                    } else {
                        SensitiveClipboard.copy(context, clipLabel, code)
                        copiedId = id
                        toast = copiedText
                    }
                }
            },
            onCopyBody = { body ->
                // Bodies usually contain the code: same sensitive, self-clearing clipboard as codes.
                SensitiveClipboard.copy(context, bodyClipLabel, body)
                toast = bodyCopied
            },
            onOpenAdjacent = { id -> if (!adjacent) openAdjacent(context, id) },
            onOpenTool = { memory.subScreen = it },
            onEnableNotifications = {
                launcher.startSettings(context, SystemIntents.appNotificationSettings(context), SystemIntents.appDetails(context))
            },
            onRepair = { memory.subScreen = InboxSubScreen.Pair },
            onDismissEnvelope = { envelopeId = null },
            onPage = { step ->
                val rows = ui.visibleRows
                val index = rows.indexOfFirst { it.id == memory.selectedId }
                rows.getOrNull(index + step)?.let { open(it.id) }
            },
        ),
    )
}
