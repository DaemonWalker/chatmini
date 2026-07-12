package com.chatmini.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chatmini.app.data.ClashConfigGenerator
import com.chatmini.app.data.ProxyConfig
import com.chatmini.app.data.SettingsRepository
import com.chatmini.app.data.SubscriptionParser
import com.chatmini.app.data.UrlItem
import com.chatmini.app.service.ProxyService
import com.chatmini.app.ui.theme.ChatMiniTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class SettingsActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsRepository = (application as ChatMiniApplication).settingsRepository

        setContent {
            ChatMiniTheme {
                SettingsScreen(
                    settingsRepository = settingsRepository,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val proxyState by ProxyService.proxyState.collectAsState()

    val initialSettings = settingsRepository.getSettingsSnapshot()
    var urls by remember { mutableStateOf(initialSettings.urls) }
    var autoStart by remember { mutableStateOf(initialSettings.autoStartProxy) }
    var currentNode by remember { mutableStateOf(initialSettings.currentNode) }
    var lastUrlId by remember { mutableStateOf(initialSettings.lastUrlId) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf<UrlItem?>(null) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        val configFile = File(File(context.filesDir, ProxyConfig.WORK_DIR_NAME), ProxyConfig.CONFIG_FILE_NAME)
        if (configFile.exists()) {
            nodes = ClashConfigGenerator.parseNodeNames(configFile.readText())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.proxy_status),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                ProxyControlSection(
                    proxyState = proxyState,
                    snackbarHostState = snackbarHostState
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            autoStart = !autoStart
                            settingsRepository.saveAutoStartProxy(autoStart)
                        }
                ) {
                    Checkbox(
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            settingsRepository.saveAutoStartProxy(it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_start_proxy))
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.url_list),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        editingUrl = null
                        showUrlDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.add_url))
                }
            }

            items(urls, key = { it.id }) { item ->
                UrlItemCard(
                    item = item,
                    isSelected = lastUrlId == item.id,
                    onSelect = {
                        lastUrlId = item.id
                        settingsRepository.saveLastUrlId(item.id)
                    },
                    onEdit = {
                        editingUrl = item
                        showUrlDialog = true
                    },
                    onDelete = {
                        settingsRepository.deleteUrl(item.id)
                        urls = settingsRepository.getSettingsSnapshot().urls
                        if (lastUrlId == item.id) {
                            lastUrlId = null
                        }
                    }
                )
            }

            item {
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.subscription),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = subscriptionUrl,
                    onValueChange = { subscriptionUrl = it },
                    label = { Text(stringResource(R.string.subscription_url)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isImporting = true
                            val result = importSubscription(context, subscriptionUrl)
                            isImporting = false
                            result.fold(
                                onSuccess = { nodeList ->
                                    nodes = nodeList
                                    if (nodeList.isNotEmpty()) {
                                        currentNode = nodeList.first()
                                        settingsRepository.saveCurrentNode(currentNode)
                                    }
                                    snackbarHostState.showSnackbar("导入成功，共 ${nodeList.size} 个节点")
                                },
                                onFailure = { e ->
                                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                                }
                            )
                        }
                    },
                    enabled = subscriptionUrl.isNotBlank() && !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(R.string.import_subscription))
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.node_list),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                NodeSelector(
                    nodes = nodes,
                    selectedNode = currentNode,
                    onNodeSelected = { node ->
                        currentNode = node
                        settingsRepository.saveCurrentNode(node)
                        ProxyService.switchNode(context, node)
                    }
                )
            }
        }
    }

    if (showUrlDialog) {
        UrlEditDialog(
            item = editingUrl,
            onDismiss = { showUrlDialog = false },
            onSave = { name, url ->
                if (editingUrl == null) {
                    settingsRepository.addUrl(name, url)
                } else {
                    settingsRepository.updateUrl(editingUrl!!.id, name, url)
                }
                urls = settingsRepository.getSettingsSnapshot().urls
                showUrlDialog = false
            }
        )
    }
}

@Composable
private fun ProxyControlSection(
    proxyState: String,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    var previousState by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(proxyState) {
        if (previousState != null && previousState != proxyState) {
            val message = when (proxyState) {
                ProxyService.STATE_RUNNING -> context.getString(R.string.proxy_started)
                ProxyService.STATE_STOPPED -> context.getString(R.string.proxy_stopped)
                ProxyService.STATE_ERROR -> context.getString(R.string.proxy_start_failed)
                else -> null
            }
            message?.let { snackbarHostState.showSnackbar(it) }
        }
        previousState = proxyState
    }

    val (dotColor, statusText) = when (proxyState) {
        ProxyService.STATE_RUNNING -> MaterialTheme.colorScheme.primary to stringResource(R.string.proxy_running)
        ProxyService.STATE_ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.proxy_not_ready)
        else -> MaterialTheme.colorScheme.outline to stringResource(R.string.proxy_stopped)
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.proxy_status) + "：" + statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = { ProxyService.start(context) },
                enabled = proxyState != ProxyService.STATE_RUNNING,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.start_proxy))
            }
            Button(
                onClick = { ProxyService.stop(context) },
                enabled = proxyState != ProxyService.STATE_STOPPED,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.stop_proxy))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeSelector(
    nodes: List<String>,
    selectedNode: String?,
    onNodeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        TextField(
            value = selectedNode ?: stringResource(R.string.no_nodes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.current_node)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (nodes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_nodes)) },
                    onClick = { expanded = false }
                )
            } else {
                nodes.forEach { node ->
                    DropdownMenuItem(
                        text = { Text(node) },
                        onClick = {
                            onNodeSelected(node)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UrlItemCard(
    item: UrlItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Text(item.url, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun UrlEditDialog(
    item: UrlItem?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var url by remember { mutableStateOf(item?.url ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) stringResource(R.string.add_url) else stringResource(R.string.edit_url)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.url_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.url_address)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private suspend fun importSubscription(
    context: Context,
    url: String
): Result<List<String>> = withContext(Dispatchers.IO) {
    try {
        val client = ProxyConfig.createProxyOkHttpClient(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 30
        )

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return@withContext Result.failure(Exception("HTTP ${response.code}"))
        }

        val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
        val nodes = SubscriptionParser.parseBase64Subscription(body)
        if (nodes.isEmpty()) {
            return@withContext Result.failure(Exception("No valid nodes found"))
        }

        val configYaml = ClashConfigGenerator.generateConfig(nodes)
        val workDir = File(context.filesDir, ProxyConfig.WORK_DIR_NAME).apply { mkdirs() }
        File(workDir, ProxyConfig.CONFIG_FILE_NAME).writeText(configYaml)

        Result.success(nodes.map { it.name })
    } catch (e: Exception) {
        Result.failure(e)
    }
}
