@file:OptIn(ExperimentalMaterial3Api::class)

package com.chatmini.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chatmini.app.data.ProxyConfig
import com.chatmini.app.data.SettingsRepository
import com.chatmini.app.service.ProxyService
import com.chatmini.app.ui.theme.ChatMiniTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.net.URL
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private var geckoRuntime: GeckoRuntime? = null
    private var proxyStateReceiver: BroadcastReceiver? = null

    // Pending GeckoView file prompt and its async result.
    private var pendingFilePrompt: PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptDelegate.PromptResponse>? = null
    private var cameraOutputUri: Uri? = null
    private var pendingCameraPrompt: PromptDelegate.FilePrompt? = null

    // Pending GeckoView Android permission callback.
    private var pendingPermissionCallback: PermissionDelegate.Callback? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        handleFilePickerResult(listOfNotNull(uri))
    }

    private val pickMultipleFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        handleFilePickerResult(uris ?: emptyList())
    }

    // Custom contracts that explicitly grant read/write URI permission to the camera app.
    // The stock TakePicture/CaptureVideo contracts do not add FLAG_GRANT_WRITE_URI_PERMISSION,
    // which causes some OEM camera apps to crash or silently fail.
    private val takePictureLauncher = registerForActivityResult(
        TakePictureWithGrant()
    ) { success ->
        handleCaptureResult(success)
    }

    private val takeVideoLauncher = registerForActivityResult(
        CaptureVideoWithGrant()
    ) { success ->
        handleCaptureResult(success)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prompt = pendingCameraPrompt
        pendingCameraPrompt = null
        if (granted && prompt != null) {
            launchCameraCapture(prompt)
        } else {
            pendingFileResult?.complete(prompt?.dismiss() ?: pendingFilePrompt?.dismiss())
            pendingFilePrompt = null
            pendingFileResult = null
        }
    }

    private val mediaPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        pendingPermissionCallback?.let { callback ->
            if (results.all { it.value }) callback.grant() else callback.reject()
            pendingPermissionCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        settingsRepository = (application as ChatMiniApplication).settingsRepository

        if (settingsRepository.getSettingsSnapshot().autoStartProxy) {
            ProxyService.start(this)
        }

        registerProxyStateReceiver()

        setContent {
            ChatMiniTheme {
                MainScreen(settingsRepository = settingsRepository)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proxyStateReceiver?.let { unregisterReceiver(it) }
        // Only stop the proxy when the user is really exiting the app.
        // Configuration changes (e.g. rotation) call onDestroy with isFinishing == false.
        if (isFinishing) {
            ProxyService.stop(this)
        }
    }

    private fun getOrCreateRuntime(): GeckoRuntime {
        return geckoRuntime ?: run {
            val configFile = writeGeckoConfigFile()
            val settings = GeckoRuntimeSettings.Builder()
                .configFilePath(configFile.absolutePath)
                .build()
            GeckoRuntime.create(this, settings).also { geckoRuntime = it }
        }
    }

    private fun writeGeckoConfigFile(): File {
        val configFile = File(filesDir, "geckoview-config.yaml")
        val content = buildString {
            appendLine("args:")
            appendLine("  - --proxy-server=socks://127.0.0.1:${ProxyConfig.SOCKS_PROXY_PORT}")
            appendLine("  - --no-remote")
            appendLine("prefs:")
            appendLine("  network.proxy.type: 1")
            appendLine("  network.proxy.socks: \"127.0.0.1\"")
            appendLine("  network.proxy.socks_port: ${ProxyConfig.SOCKS_PROXY_PORT}")
            appendLine("  network.proxy.socks_remote_dns: true")
        }
        configFile.writeText(content)
        return configFile
    }

    private fun registerProxyStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Proxy state changes can be observed here if needed.
            }
        }
        proxyStateReceiver = receiver
        val filter = IntentFilter(ProxyService.ACTION_PROXY_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    @Composable
    fun MainScreen(settingsRepository: SettingsRepository) {
        val context = LocalContext.current
        val proxyState by ProxyService.proxyState.collectAsState()
        var geckoView by remember { mutableStateOf<GeckoView?>(null) }
        var showMenu by remember { mutableStateOf(false) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    settingsRepository.settings.collect { settings ->
                        val url = settings.lastUrlId?.let { id ->
                            settings.urls.find { it.id == id }?.url
                        } ?: settings.urls.firstOrNull()?.url
                            ?: "https://httpbin.org/get"
                        geckoView?.session?.loadUri(url)
                    }
                }
            }
        }

        val keyboardController = LocalSoftwareKeyboardController.current
        var urlText by remember { mutableStateOf("") }
        var loadProgress by remember { mutableFloatStateOf(0f) }
        var isLoading by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.statusBarsPadding()
                ) {
                    Column {
                        AnimatedVisibility(visible = isLoading) {
                            LinearProgressIndicator(
                                progress = { loadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            val interactionSource = remember { MutableInteractionSource() }
                            BasicTextField(
                                value = urlText,
                                onValueChange = { urlText = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    val target = normalizeUrl(urlText)
                                    urlText = target
                                    isLoading = true
                                    loadProgress = 0.05f
                                    geckoView?.session?.loadUri(target)
                                    keyboardController?.hide()
                                }),
                                interactionSource = interactionSource,
                                decorationBox = { innerTextField ->
                                    OutlinedTextFieldDefaults.DecorationBox(
                                        value = urlText,
                                        innerTextField = innerTextField,
                                        enabled = true,
                                        singleLine = true,
                                        visualTransformation = VisualTransformation.None,
                                        interactionSource = interactionSource,
                                        placeholder = { Text("输入网址") },
                                        trailingIcon = {
                                            if (urlText.isNotEmpty()) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "清除",
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                                        .clickable { urlText = "" }
                                                        .padding(6.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        contentPadding = OutlinedTextFieldDefaults.contentPadding(
                                            start = 12.dp,
                                            end = 8.dp,
                                            top = 4.dp,
                                            bottom = 4.dp
                                        ),
                                        container = {
                                            OutlinedTextFieldDefaults.Container(
                                                enabled = true,
                                                isError = false,
                                                interactionSource = interactionSource
                                            )
                                        }
                                    )
                                }
                            )
                    }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        val view = GeckoView(ctx)
                        val session = GeckoSession()
                        var lastLocationUrl = ""
                        session.navigationDelegate = object : NavigationDelegate {
                            override fun onLocationChange(
                                session: GeckoSession,
                                url: String?,
                                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                                hasUserGesture: Boolean
                            ) {
                                url?.let {
                                    urlText = it
                                    lastLocationUrl = it
                                }
                            }
                        }
                        session.progressDelegate = object : ProgressDelegate {
                            override fun onPageStart(session: GeckoSession, url: String) {
                                isLoading = true
                                loadProgress = 0f
                                url?.let { lastLocationUrl = it }
                            }

                            override fun onProgressChange(session: GeckoSession, progress: Int) {
                                loadProgress = progress / 100f
                            }

                            override fun onPageStop(session: GeckoSession, success: Boolean) {
                                isLoading = false
                                loadProgress = 0f
                                if (!success) return
                                val url = lastLocationUrl
                                if (url.isBlank()) return
                                maybeDownloadFavicon(url)
                            }
                        }
                        session.promptDelegate = createPromptDelegate()
                        session.permissionDelegate = createPermissionDelegate()
                        session.open(getOrCreateRuntime())
                        view.setSession(session)
                        geckoView = view
                        view
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 72.dp, end = 16.dp)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
                contentAlignment = Alignment.BottomEnd
            ) {
                AnimatedVisibility(
                    visible = showMenu,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val settings by settingsRepository.settings.collectAsState()
                    FloatingBallMenu(
                        proxyState = proxyState,
                        urls = settings.urls,
                        onUrlClick = { item ->
                            showMenu = false
                            settingsRepository.saveLastUrlId(item.id)
                            val target = normalizeUrl(item.url)
                            urlText = target
                            isLoading = true
                            loadProgress = 0.05f
                            geckoView?.session?.loadUri(target)
                        },
                        onRefresh = {
                            showMenu = false
                            geckoView?.session?.reload()
                        },
                        onSettings = {
                            showMenu = false
                            startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        onExit = {
                            showMenu = false
                            finish()
                        }
                    )
                }

                FloatingBallButton(
                    onTap = { showMenu = !showMenu },
                    onDrag = { dx, dy ->
                        offsetX += dx
                        offsetY += dy
                    }
                )
            }
        }
    }

    @Composable
    private fun FloatingBallMenu(
        proxyState: String,
        urls: List<com.chatmini.app.data.UrlItem>,
        onUrlClick: (com.chatmini.app.data.UrlItem) -> Unit,
        onRefresh: () -> Unit,
        onSettings: () -> Unit,
        onExit: () -> Unit
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(bottom = 72.dp)
        ) {
            ProxyStatusItem(state = proxyState)
            urls.forEach { item ->
                UrlButton(
                    item = item,
                    onClick = { onUrlClick(item) }
                )
            }
            MenuButton(icon = Icons.Default.Refresh, onClick = onRefresh)
            MenuButton(icon = Icons.Default.Settings, onClick = onSettings)
            MenuButton(icon = Icons.Default.Close, onClick = onExit)
        }
    }

    @Composable
    private fun UrlButton(
        item: com.chatmini.app.data.UrlItem,
        onClick: () -> Unit
    ) {
        val iconBitmap = remember(item.iconPath) {
            item.iconPath?.let { path ->
                File(path).takeIf { it.exists() }?.let {
                    BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap()
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = item.name,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = getDomainFirstLetter(item.url),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProxyStatusItem(state: String) {
        val (dotColor, statusText) = when (state) {
            ProxyService.STATE_RUNNING -> MaterialTheme.colorScheme.primary to stringResource(R.string.proxy_running)
            ProxyService.STATE_ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.proxy_not_ready)
            else -> MaterialTheme.colorScheme.outline to stringResource(R.string.proxy_stopped)
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
            shadowElevation = 4.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.proxy_status) + "：" + statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun MenuButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun FloatingBallButton(
        onTap: () -> Unit,
        onDrag: (Float, Float) -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    private fun maybeDownloadFavicon(currentUrl: String) {
        val normalized = normalizeUrl(currentUrl)
        val currentHost = getHost(normalized) ?: return
        val settings = settingsRepository.getSettingsSnapshot()
        val item = settings.urls.find { getHost(it.url) == currentHost } ?: return
        if (!item.iconPath.isNullOrBlank() && File(item.iconPath).exists()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val faviconUrl = "https://icons.duckduckgo.com/ip3/$currentHost.ico"
            try {
                val client = ProxyConfig.createProxyOkHttpClient()
                val request = Request.Builder().url(faviconUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.d("ChatMiniFavicon", "Failed to download $faviconUrl: HTTP ${response.code}")
                    return@launch
                }
                val body = response.body?.bytes() ?: return@launch
                if (body.isEmpty()) return@launch
                val dir = File(filesDir, "favicons").apply { mkdirs() }
                val file = File(dir, "${item.id}.ico")
                file.writeBytes(body)
                withContext(Dispatchers.Main) {
                    settingsRepository.updateUrlIcon(item.id, file.absolutePath)
                }
                Log.d("ChatMiniFavicon", "Saved favicon for $currentHost to ${file.absolutePath}")
            } catch (e: Exception) {
                Log.d("ChatMiniFavicon", "Failed to download favicon $faviconUrl", e)
            }
        }
    }

    private fun createPromptDelegate(): PromptDelegate {
        return object : PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: PromptDelegate.FilePrompt
            ): GeckoResult<PromptDelegate.PromptResponse>? {
                Log.d("ChatMiniFile", "onFilePrompt type=${prompt.type} capture=${prompt.capture} mime=${prompt.mimeTypes?.contentToString()}")
                // Dismiss any previous pending file prompt to avoid leaks.
                pendingFilePrompt?.let { oldPrompt ->
                    pendingFileResult?.complete(oldPrompt.dismiss())
                }
                pendingFilePrompt = prompt
                val result = GeckoResult<PromptDelegate.PromptResponse>()
                pendingFileResult = result

                when (prompt.capture) {
                    PromptDelegate.FilePrompt.Capture.USER,
                    PromptDelegate.FilePrompt.Capture.ENVIRONMENT,
                    PromptDelegate.FilePrompt.Capture.ANY -> {
                        launchCameraCapture(prompt)
                    }
                    else -> {
                        launchFilePicker(prompt)
                    }
                }
                return result
            }
        }
    }

    private fun createPermissionDelegate(): PermissionDelegate {
        return object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: PermissionDelegate.Callback
            ) {
                pendingPermissionCallback?.reject()
                pendingPermissionCallback = callback

                val toRequest = permissions?.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                        PackageManager.PERMISSION_GRANTED
                }?.toTypedArray() ?: emptyArray()

                if (toRequest.isEmpty()) {
                    callback.grant()
                    pendingPermissionCallback = null
                } else {
                    mediaPermissionsLauncher.launch(toRequest)
                }
            }
        }
    }

    private fun launchFilePicker(prompt: PromptDelegate.FilePrompt) {
        val type = buildPickType(prompt.mimeTypes)
        when (prompt.type) {
            PromptDelegate.FilePrompt.Type.MULTIPLE -> {
                pickMultipleFilesLauncher.launch(arrayOf(type))
            }
            else -> {
                pickFileLauncher.launch(type)
            }
        }
    }

    private fun launchCameraCapture(prompt: PromptDelegate.FilePrompt) {
        Log.d("ChatMiniFile", "launchCameraCapture")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d("ChatMiniFile", "CAMERA permission not granted, requesting")
            pendingCameraPrompt = prompt
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            return
        }

        val mimeTypes = prompt.mimeTypes ?: arrayOf("image/*")
        val isVideo = mimeTypes.any { it.startsWith("video/", ignoreCase = true) }
        try {
            val uri = if (isVideo) {
                getUriForFile(createCameraVideoFile())
            } else {
                getUriForFile(createCameraPhotoFile())
            }
            Log.d("ChatMiniFile", "Camera output uri=$uri")
            cameraOutputUri = uri
            if (isVideo) {
                takeVideoLauncher.launch(uri)
            } else {
                takePictureLauncher.launch(uri)
            }
        } catch (e: Exception) {
            Log.e("ChatMiniFile", "Failed to launch camera capture", e)
            pendingFileResult?.complete(prompt.dismiss())
            pendingFilePrompt = null
            pendingFileResult = null
            cameraOutputUri = null
        }
    }

    private fun handleFilePickerResult(uris: List<Uri>) {
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        if (prompt == null || result == null) return

        if (uris.isEmpty()) {
            result.complete(prompt.dismiss())
            return
        }

        val response = try {
            if (prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE) {
                prompt.confirm(this, uris.toTypedArray())
            } else {
                prompt.confirm(this, uris.first())
            }
        } catch (e: Exception) {
            prompt.dismiss()
        }
        result.complete(response)
    }

    private fun handleCaptureResult(success: Boolean) {
        Log.d("ChatMiniFile", "handleCaptureResult success=$success uri=$cameraOutputUri")
        val prompt = pendingFilePrompt
        val result = pendingFileResult
        val uri = cameraOutputUri
        pendingFilePrompt = null
        pendingFileResult = null
        cameraOutputUri = null
        if (prompt == null || result == null) return

        val response = if (success && uri != null) {
            try {
                prompt.confirm(this, uri)
            } catch (e: Exception) {
                Log.e("ChatMiniFile", "Failed to confirm camera capture", e)
                prompt.dismiss()
            }
        } else {
            prompt.dismiss()
        }
        result.complete(response)
    }

    private fun buildPickType(mimeTypes: Array<String>?): String {
        if (mimeTypes.isNullOrEmpty()) return "*/*"
        val normalized = mimeTypes.map { it.lowercase(Locale.getDefault()) }
        return when {
            normalized.size == 1 -> normalized.first()
            normalized.all { it.startsWith("image/") } -> "image/*"
            normalized.all { it.startsWith("video/") } -> "video/*"
            normalized.all { it.startsWith("audio/") } -> "audio/*"
            else -> "*/*"
        }
    }

    @Throws(IOException::class)
    private fun createCameraPhotoFile(): File {
        val dir = File(filesDir, "camera").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("IMG_${timestamp}_", ".jpg", dir)
    }

    @Throws(IOException::class)
    private fun createCameraVideoFile(): File {
        val dir = File(filesDir, "camera").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("VID_${timestamp}_", ".mp4", dir)
    }

    private fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> "about:blank"
        trimmed.contains("://") -> trimmed
        trimmed.startsWith("about:") -> trimmed
        else -> "https://$trimmed"
    }
}

private fun getDomainFirstLetter(url: String): String {
    return try {
        val host = URL(normalizeUrl(url)).host.lowercase()
        val cleanHost = if (host.startsWith("www.")) host.removePrefix("www.") else host
        cleanHost.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
    } catch (e: Exception) {
        "?"
    }
}

private fun getHost(url: String): String? {
    return try {
        URL(normalizeUrl(url)).host.lowercase().removePrefix("www.")
    } catch (e: Exception) {
        null
    }
}

/**
 * Custom camera contract that grants write/read URI permission to the receiving camera app.
 * The androidx.activity [ActivityResultContracts.TakePicture] does not add
 * [Intent.FLAG_GRANT_WRITE_URI_PERMISSION], which causes crashes on some OEM devices.
 */
private class TakePictureWithGrant : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun getSynchronousResult(context: Context, input: Uri): SynchronousResult<Boolean>? = null

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
        return resultCode == Activity.RESULT_OK
    }
}

/**
 * Custom video capture contract that grants write/read URI permission to the receiving camera app.
 */
private class CaptureVideoWithGrant : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun getSynchronousResult(context: Context, input: Uri): SynchronousResult<Boolean>? = null

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
        return resultCode == Activity.RESULT_OK
    }
}
