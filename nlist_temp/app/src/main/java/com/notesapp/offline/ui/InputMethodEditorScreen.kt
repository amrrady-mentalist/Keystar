package com.notesapp.offline.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.notesapp.offline.data.HsWidgetHost
import com.notesapp.offline.data.LockMode
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.ui.theme.Danger
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * Dedicated customization page for one input method — reached by tapping
 * the Pin Code or Home Screen row (not its toggle) on the main Magic
 * Settings screen. Everything here used to live inline on that screen,
 * gated by `if (store.lockMode == ...)`; it's the same functionality, just
 * moved to its own page per input method so each one gets its own
 * dedicated space instead of the main settings list growing/shrinking
 * around whichever mode happened to be selected.
 */
@Composable
fun InputMethodEditorScreen(
    repo: MagicRepository,
    mode: LockMode,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    var store by remember { mutableStateOf(MagicStore()) }
    var loaded by remember { mutableStateOf(false) }
    var decoyAppsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store = repo.load()
        loaded = true
    }

    fun persist(updated: MagicStore) {
        store = updated
        scope.launch { repo.save(updated) }
    }

    // ---- Classic Lock (Pin Code) background ----------------------------
    val lockBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = copyImageToInternal(context, uri, repo.mediaDir, "lock_bg")
                // Only overwrite the saved setting on an actual successful
                // copy — if it silently failed, leave whatever background
                // was already set (if any) alone rather than blanking it.
                if (path != null) {
                    persist(store.copy(lockBackgroundPath = path))
                }
            }
        }
    }

    // ---- Home Screen disguise -------------------------------------------
    // Single shared picker for every Home Screen-mode image slot (the
    // wallpaper, the disguised Notes icon, and each decoy app's icon) —
    // `pendingIconTarget` says which one the next result should apply to.
    // "wallpaper" and "notes" are the two fixed slots; anything else is
    // taken as the decoy app name being re-skinned.
    var pendingIconTarget by remember { mutableStateOf<String?>(null) }
    // When set, shows the small "Photos / An installed app" chooser for
    // that target instead of jumping straight to the gallery.
    var iconSourceChooserTarget by remember { mutableStateOf<String?>(null) }
    // When set, shows the installed-apps list for that target.
    var appPickerTarget by remember { mutableStateOf<String?>(null) }

    fun applyIconPath(target: String, path: String) {
        when (target) {
            "wallpaper" -> persist(store.copy(homeWallpaperPath = path))
            "notes" -> persist(store.copy(notesIconPath = path))
            else -> persist(store.copy(appIconOverrides = store.appIconOverrides + (target to path)))
        }
    }

    val hsIconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pendingIconTarget
        pendingIconTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val path = copyImageToInternal(context, uri, repo.mediaDir, "hs_${target}")
                if (path != null) applyIconPath(target, path)
            }
        }
    }
    fun launchPhotosPicker(target: String) {
        pendingIconTarget = target
        hsIconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Entry point every "Change icon"/"Icon" button calls — the wallpaper
     *  slot has no meaningful "app icon" equivalent, so it skips the
     *  chooser and goes straight to Photos. */
    fun pickHsIcon(target: String) {
        if (target == "wallpaper") launchPhotosPicker(target) else iconSourceChooserTarget = target
    }

    // ---- Real Android widget picking ----
    var widgetPickerOpen by remember { mutableStateOf(false) }
    // Tracks an allocated-but-not-yet-bound widget id across the two-step
    // bind → (optional) configure flow, so the activity-result callbacks
    // below know what they're finishing.
    var pendingBindInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    var pendingBindId by remember { mutableStateOf(-1) }
    var pendingConfigureProvider by remember { mutableStateOf<String?>(null) }
    var pendingConfigureId by remember { mutableStateOf(-1) }
    // Some widgets' configure/customize screens are marked non-exported —
    // only the system launcher (with special OS privileges) is allowed to
    // launch them; a normal app gets a SecurityException. Shown to the
    // user instead of letting the crash happen.
    var widgetErrorMessage by remember { mutableStateOf<String?>(null) }

    val widgetConfigureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingConfigureId
        val provider = pendingConfigureProvider
        pendingConfigureId = -1
        pendingConfigureProvider = null
        if (result.resultCode == Activity.RESULT_OK && id >= 0 && provider != null) {
            persist(store.copy(homeWidgetProvider = provider, homeWidgetId = id))
        } else if (id >= 0) {
            // User backed out of the widget's own configure screen —
            // release the id rather than leaving an orphaned half-bound
            // widget hanging around.
            HsWidgetHost.get(context).deleteAppWidgetId(id)
        }
    }

    fun finishWidgetBind(info: AppWidgetProviderInfo, id: Int) {
        val configure = info.configure
        if (configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            try {
                pendingConfigureId = id
                pendingConfigureProvider = info.provider.flattenToString()
                widgetConfigureLauncher.launch(intent)
            } catch (e: SecurityException) {
                pendingConfigureId = -1
                pendingConfigureProvider = null
                HsWidgetHost.get(context).deleteAppWidgetId(id)
                widgetErrorMessage = "\"${runCatching { info.loadLabel(context.packageManager) }.getOrDefault("This widget")}\" can't be added — its setup screen is restricted to system launchers only, not regular apps. Try a different widget."
            }
        } else {
            persist(store.copy(homeWidgetProvider = info.provider.flattenToString(), homeWidgetId = id))
        }
    }

    val widgetBindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val info = pendingBindInfo
        val id = pendingBindId
        pendingBindInfo = null
        pendingBindId = -1
        if (result.resultCode == Activity.RESULT_OK && info != null && id >= 0) {
            finishWidgetBind(info, id)
        } else if (id >= 0) {
            HsWidgetHost.get(context).deleteAppWidgetId(id)
        }
    }

    fun pickWidget(info: AppWidgetProviderInfo) {
        widgetPickerOpen = false
        val host = HsWidgetHost.get(context)
        val id = host.allocateAppWidgetId()
        val mgr = AppWidgetManager.getInstance(context)
        // Most widgets from the same app you've already granted, or ones
        // that don't need special permission, bind immediately; anything
        // else needs one-time system consent via the intent below.
        val boundImmediately = try {
            mgr.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (e: SecurityException) {
            false
        }
        if (boundImmediately) {
            finishWidgetBind(info, id)
        } else {
            try {
                pendingBindInfo = info
                pendingBindId = id
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                }
                widgetBindLauncher.launch(intent)
            } catch (e: Exception) {
                pendingBindInfo = null
                pendingBindId = -1
                host.deleteAppWidgetId(id)
                widgetErrorMessage = "\"${runCatching { info.loadLabel(context.packageManager) }.getOrDefault("This widget")}\" couldn't be added. Try a different widget."
            }
        }
    }

    if (!loaded) return

    val screenTitle = if (mode == LockMode.CLASSIC) "Pin Code" else "Home Screen"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp).glassPanel(radius = GlassRadius.lg, tint = fgColor)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fgColor)
            }
            Text(
                screenTitle,
                color = fgColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 18.dp)
            )
            Box(modifier = Modifier.size(40.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            if (mode == LockMode.CLASSIC) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        SectionLabel("Lock screen background", fgColor)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PhotoThumb(path = store.lockBackgroundPath, fgColor = fgColor)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassPill("Change photo", fgColor) {
                                    lockBgPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                if (store.lockBackgroundPath != null) {
                                    GlassPill("Remove", Danger) {
                                        persist(store.copy(lockBackgroundPath = null))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        SectionLabel("Home screen wallpaper", fgColor)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PhotoThumb(path = store.homeWallpaperPath, fgColor = fgColor)
                            GlassPill("Change photo", fgColor) { pickHsIcon("wallpaper") }
                        }

                        SectionLabel("Home screen widget", fgColor, topPadding = 20.dp)
                        Text(
                            "Any real widget from your phone — sits at the top of the first page, in place of the old built-in search bar.",
                            color = fgColor.copy(alpha = 0.34f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPill(
                                if (store.homeWidgetProvider != null) "Change widget" else "Choose widget",
                                fgColor
                            ) { widgetPickerOpen = true }
                            if (store.homeWidgetProvider != null) {
                                GlassPill("Remove", fgColor) {
                                    if (store.homeWidgetId >= 0) {
                                        HsWidgetHost.get(context).deleteAppWidgetId(store.homeWidgetId)
                                    }
                                    persist(store.copy(homeWidgetProvider = null, homeWidgetId = -1))
                                }
                            }
                        }

                        SectionLabel("Disguised Notes icon", fgColor, topPadding = 20.dp)
                        Text(
                            "This is the icon on the fake home screen's last page that actually opens your real notes.",
                            color = fgColor.copy(alpha = 0.34f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PhotoThumb(path = store.notesIconPath, fgColor = fgColor)
                            GlassPill("Change icon", fgColor) { pickHsIcon("notes") }
                        }

                        SectionLabel("Dock apps", fgColor, topPadding = 20.dp)
                        Text(
                            "The 3 fake apps shown either side of the lock button at the bottom of every page.",
                            color = fgColor.copy(alpha = 0.34f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            homeScreenDockApps.forEach { app ->
                                DecoyAppRow(
                                    app = app,
                                    iconPath = store.appIconOverrides[app.name],
                                    displayName = store.appNameOverrides[app.name] ?: app.name,
                                    fgColor = fgColor,
                                    onPickIcon = { pickHsIcon(app.name) },
                                    onNameChange = { newName ->
                                        persist(store.copy(appNameOverrides = store.appNameOverrides + (app.name to newName)))
                                    }
                                )
                            }
                        }

                        SectionLabel("Decoy apps", fgColor, topPadding = 20.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { decoyAppsExpanded = !decoyAppsExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Rename or re-skin any of the ${homeScreenDecoyApps.size} filler apps shown around the disguised Notes icon.",
                                color = fgColor.copy(alpha = 0.34f),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (decoyAppsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (decoyAppsExpanded) "Collapse" else "Expand",
                                tint = fgColor.copy(alpha = 0.56f)
                            )
                        }
                    }
                }

                if (decoyAppsExpanded) {
                    items(homeScreenDecoyApps, key = { it.name }) { app ->
                        DecoyAppRow(
                            app = app,
                            iconPath = store.appIconOverrides[app.name],
                            displayName = store.appNameOverrides[app.name] ?: app.name,
                            fgColor = fgColor,
                            onPickIcon = { pickHsIcon(app.name) },
                            onNameChange = { newName ->
                                persist(store.copy(appNameOverrides = store.appNameOverrides + (app.name to newName)))
                            },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    // Small "where from?" chooser shown before any icon change.
    iconSourceChooserTarget?.let { target ->
        IconSourceChooserDialog(
            fgColor = fgColor,
            onPickPhotos = {
                iconSourceChooserTarget = null
                launchPhotosPicker(target)
            },
            onPickApp = {
                iconSourceChooserTarget = null
                appPickerTarget = target
            },
            onDismiss = { iconSourceChooserTarget = null }
        )
    }

    // Full installed-apps list, shown after "An installed app" is chosen.
    appPickerTarget?.let { target ->
        InstalledAppPickerDialog(
            fgColor = fgColor,
            bgColor = bgColor,
            onPicked = { packageName ->
                appPickerTarget = null
                scope.launch {
                    val path = savePackageIconToInternal(context, packageName, repo.mediaDir, "hs_${target}")
                    if (path != null) applyIconPath(target, path)
                }
            },
            onDismiss = { appPickerTarget = null }
        )
    }

    if (widgetPickerOpen) {
        WidgetPickerDialog(
            fgColor = fgColor,
            bgColor = bgColor,
            onPicked = { info -> pickWidget(info) },
            onDismiss = { widgetPickerOpen = false }
        )
    }

    widgetErrorMessage?.let { message ->
        Dialog(onDismissRequest = { widgetErrorMessage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .padding(20.dp)
            ) {
                Text("Can't add this widget", color = fgColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    message,
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
                GlassPill("OK", fgColor) { widgetErrorMessage = null }
            }
        }
    }
}
