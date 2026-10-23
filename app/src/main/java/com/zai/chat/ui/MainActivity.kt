package com.zai.chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zai.chat.data.local.preferences.JwtParser
import com.zai.chat.data.local.preferences.SettingsDataStore
import com.zai.chat.data.local.preferences.TokenManager
import com.zai.chat.network.auth.AuthEvent
import com.zai.chat.network.auth.AuthEventManager
import com.zai.chat.ui.debug.ComponentGalleryScreen
import com.zai.chat.ui.screens.chat.ChatScreen
import com.zai.chat.ui.screens.chat.ChatViewModel
import com.zai.chat.ui.screens.drawer.DrawerChatList
import com.zai.chat.ui.screens.drawer.DrawerViewModel
import com.zai.chat.ui.screens.session.ManageSessionScreen
import com.zai.chat.ui.screens.session.SessionMode
import com.zai.chat.ui.screens.session.SessionUiEvent
import com.zai.chat.ui.screens.session.SessionViewModel
import com.zai.chat.ui.screens.settings.SettingsScreen
import com.zai.chat.ui.screens.settings.SettingsViewModel
import com.zai.chat.ui.screens.terminal.TerminalScreen
import com.zai.chat.ui.screens.terminal.TerminalViewModel
import com.zai.chat.ui.theme.ZaiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var authEventManager: AuthEventManager
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settingsDataStore.themeMode.collectAsState(initial = "OLED")
            val fontScale by settingsDataStore.fontScale.collectAsState(initial = 1f)

            val navController = rememberNavController()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val sessionViewModel: SessionViewModel = hiltViewModel()
            val drawerViewModel: DrawerViewModel = hiltViewModel()

            var showGallery by remember { mutableStateOf(false) }

            // Handle background 401 token expirations gracefully via navigation
            LaunchedEffect(Unit) {
                authEventManager.events.collect { event ->
                    when (event) {
                        is AuthEvent.TokenExpired -> {
                            sessionViewModel.onEvent(SessionUiEvent.SetMode(SessionMode.BackgroundExpired))
                            navController.navigate("session_management") {
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }

            // Auto-prompt on cold launch if no token exists OR the stored one
            // is already past its exp claim. Previously an expired JWT passed
            // this check, the app opened straight into the chat surface, and
            // every request silently 403'd — the user was stranded with no
            // re-auth affordance ("can't hit the servers").
            LaunchedEffect(Unit) {
                val stored = tokenManager.getStoredToken()
                val locallyExpired = JwtParser.parse(stored)?.isExpired == true
                if (stored.isNullOrBlank() || locallyExpired) {
                    sessionViewModel.onEvent(SessionUiEvent.SetMode(SessionMode.ActiveManagement))
                    navController.navigate("session_management") {
                        launchSingleTop = true
                    }
                }
            }

            LaunchedEffect(drawerState.currentValue) {
                if (drawerState.currentValue == DrawerValue.Open) {
                    drawerViewModel.refresh()
                }
            }

            ZaiTheme(themeMode = themeMode, fontScale = fontScale) {
                when {
                    showGallery -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ComponentGalleryScreen(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 32.dp)
                            )
                            TextButton(
                                onClick = { showGallery = false },
                                modifier = Modifier.padding(16.dp)
                            ) { Text("← Back") }
                        }
                    }
                    else -> ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = drawerState.isOpen,
                        drawerContent = {
                            DrawerChatList(
                                viewModel = drawerViewModel,
                                onSelectChat = { chatId ->
                                    scope.launch { drawerState.close() }
                                    navController.navigate("chat?chatId=$chatId") {
                                        launchSingleTop = true
                                    }
                                },
                                onNewChat = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("chat") {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSettings = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("settings")
                                },
                                onOpenTerminal = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("terminal")
                                }
                            )
                        }
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = "chat"
                        ) {
                            composable(
                                route = "chat?chatId={chatId}",
                                arguments = listOf(
                                    navArgument("chatId") {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    }
                                )
                            ) { backStackEntry ->
                                val chatViewModel: ChatViewModel = hiltViewModel(backStackEntry)
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    onOpenSettings = { navController.navigate("settings") },
                                    onNewChat = { navController.navigate("chat") },
                                    onOpenTerminal = { navController.navigate("terminal") },
                                    onOpenGallery = { showGallery = true }
                                )
                            }
                            composable("settings") {
                                val settingsViewModel: SettingsViewModel = hiltViewModel()
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    onBack = { navController.popBackStack() },
                                    onManageSession = {
                                        sessionViewModel.onEvent(SessionUiEvent.SetMode(SessionMode.ActiveManagement))
                                        navController.navigate("session_management")
                                    },
                                    onOpenTerminal = { navController.navigate("terminal") }
                                )
                            }
                            composable("session_management") {
                                ManageSessionScreen(
                                    viewModel = sessionViewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable("terminal") {
                                val terminalViewModel: TerminalViewModel = hiltViewModel()
                                TerminalScreen(
                                    viewModel = terminalViewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
