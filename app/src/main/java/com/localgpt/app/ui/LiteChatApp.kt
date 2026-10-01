package com.localgpt.app.ui

import androidx.compose.foundation.clickable
import com.localgpt.app.data.ChatConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.localgpt.app.ui.theme.success
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localgpt.app.core.server.OpenAiServer
import com.localgpt.app.ui.about.AboutScreen
import com.localgpt.app.ui.characters.CharactersScreen
import com.localgpt.app.ui.chat.ChatScreen
import com.localgpt.app.ui.chat.ChatViewModel
import com.localgpt.app.ui.history.HistoryScreen
import com.localgpt.app.ui.logs.LogsScreen
import com.localgpt.app.ui.models.ModelsScreen
import com.localgpt.app.ui.sampler.SamplerScreen
import com.localgpt.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.util.Locale

object NavRoute {
    const val CHAT = "chat"
    const val CHARACTERS = "characters"
    const val SKILLS = "skills"
    const val SAMPLER = "sampler"
    const val MODELS = "models"
    const val LOGS = "logs"
    const val HISTORY = "history"
    const val MCP = "mcp"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

data class DrawerMenuItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

data class DrawerMenuSection(
    val title: String,
    val items: List<DrawerMenuItem>,
)

/**
 * Root Application Container with Swipeable Modal Navigation Drawer (Sidebar).
 * Replicating ChatterUI's modular menu organization, mode toggle, and design structure.
 */
@Composable
fun LiteChatApp(
    viewModel: ChatViewModel,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavRoute.CHAT

    val settings by viewModel.settings.collectAsState()
    val activePersona by viewModel.activePersona.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val loadedModelPath by viewModel.loadedModelPath.collectAsState()
    val activeBackend by viewModel.activeBackendState.collectAsState()
    val conversations = viewModel.conversations.value
    val freeDiskGb = viewModel.freeDiskGb()

    val isServerRunning = serverStatus is OpenAiServer.Status.Running
    val isLocalMode = settings.modelSource == ChatConstants.SOURCE_LOCAL

    val menuSections =
        remember(isLocalMode) {
            listOf(
                DrawerMenuSection(
                    title = "MAIN",
                    items = listOf(
                        DrawerMenuItem(NavRoute.CHAT, "Chat", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
                        DrawerMenuItem(NavRoute.CHARACTERS, "Characters", Icons.Filled.Psychology, Icons.Outlined.Psychology),
                        DrawerMenuItem(NavRoute.HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
                    )
                ),
                DrawerMenuSection(
                    title = "AI ENGINE & TOOLS",
                    items = listOf(
                        DrawerMenuItem(NavRoute.MODELS, if (isLocalMode) "Models Hub" else "API", Icons.Filled.Memory, Icons.Outlined.Memory),
                        // "Generation Parameters" + "Formatting" merged into one Chat Settings
                        // screen (SamplerScreen already hosts both tabs).
                        DrawerMenuItem(NavRoute.SAMPLER, "Chat Settings", Icons.Filled.Tune, Icons.Outlined.Tune),
                        DrawerMenuItem(NavRoute.SKILLS, "Skills Hub", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
                        DrawerMenuItem(NavRoute.MCP, "MCP Servers", Icons.Filled.Build, Icons.Outlined.Build),
                    )
                ),
                DrawerMenuSection(
                    title = "SYSTEM & LOGS",
                    items = listOf(
                        DrawerMenuItem(NavRoute.LOGS, "Telemetry Logs", Icons.Filled.Terminal, Icons.Outlined.Terminal),
                        DrawerMenuItem(NavRoute.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
                        DrawerMenuItem(NavRoute.ABOUT, "About", Icons.Filled.Info, Icons.Outlined.Info),
                    )
                ),
            )
        }

    fun navigateTo(route: String) {
        scope.launch { drawerState.close() }
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true, // Edge swipe opens drawer
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(290.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp)
                            .statusBarsPadding(),
                ) {
                    // ── 1. Top Header Row (Logo + Brand + Close Button) ──
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.SmartToy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "LiteChat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (isLocalMode) "On-Device AI Engine" else "Remote API Engine",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                )
                            }
                        }

                        IconButton(
                            onClick = { scope.launch { drawerState.close() } },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Drawer", modifier = Modifier.size(18.dp))
                        }
                    }

                    // ── 2. Active Persona & Mode Toggle (consistent with menu items) ──
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        label = {
                            Column {
                                Text(
                                    text = activePersona.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (isLocalMode) "LiteRT Local · ${settings.backend}" else "Remote API Endpoint",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                )
                            }
                        },
                        badge = {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        selected = false,
                        onClick = { navigateTo(NavRoute.CHARACTERS) },
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                unselectedContainerColor = Color.Transparent,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )

                    // Segmented Mode Switcher (compact, inline style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Local button
                        Surface(
                            onClick = { viewModel.setModelSource(ChatConstants.SOURCE_LOCAL) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isLocalMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = if (isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Local",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isLocalMode) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        // Remote button
                        Surface(
                            onClick = { viewModel.setModelSource(ChatConstants.SOURCE_REMOTE) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isLocalMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = if (!isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Remote",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (!isLocalMode) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (!isLocalMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // ── 3. Categorized Route Navigation List ──
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        menuSections.forEachIndexed { sIndex, section ->
                            item(key = "section_${section.title}") {
                                if (sIndex > 0) {
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                                    letterSpacing = 0.6.sp,
                                )
                            }
                            items(section.items, key = { it.route }) { item ->
                                val isSelected = currentRoute == item.route
                                NavigationDrawerItem(
                                    icon = {
                                        Icon(
                                            if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            item.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    badge = {
                                        when (item.route) {
                                            // Server status dot now lives on Models Hub / API,
                                            // which hosts the Server & Benchmark section.
                                            NavRoute.MODELS -> {
                                                if (isServerRunning) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.success,
                                                        shape = CircleShape,
                                                        modifier = Modifier.size(8.dp),
                                                    ) {}
                                                }
                                            }
                                            NavRoute.HISTORY -> {
                                                if (conversations.isNotEmpty()) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        modifier = Modifier.padding(end = 4.dp),
                                                    ) {
                                                        Text(
                                                            text = "${conversations.size}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    selected = isSelected,
                                    onClick = { navigateTo(item.route) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors =
                                        NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                            unselectedContainerColor = Color.Transparent,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }

                    // ── 4. Drawer Footer Card (Storage & Specs) ──
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "LiteChat v1.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                String.format(Locale.ROOT, "%.1f GB Free", freeDiskGb),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoute.CHAT,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(NavRoute.CHAT) {
                    ChatScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateToCharacters = {
                            navController.navigate(NavRoute.CHARACTERS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToModels = {
                            navController.navigate(NavRoute.MODELS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.CHARACTERS) {
                    CharactersScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onStartChatWithCharacter = {
                            navController.navigate(NavRoute.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.SKILLS) {
                    com.localgpt.app.ui.skills.SkillsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.MCP) {
                    com.localgpt.app.ui.mcp.McpScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.SAMPLER) {
                    SamplerScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.MODELS) {
                    ModelsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.LOGS) {
                    LogsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
                composable(NavRoute.HISTORY) {
                    HistoryScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                        onOpenConversation = {
                            navController.navigate(NavRoute.CHAT) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(NavRoute.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.ABOUT) {
                    AboutScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
            }
        }
    }
}
