package com.speechnotes.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.speechnotes.app.MainActivity
import com.speechnotes.app.data.SpeechnotesViewModel
import com.speechnotes.app.ui.books.BookReaderScreen
import com.speechnotes.app.ui.books.BooksRecycleBinScreen
import com.speechnotes.app.ui.books.BooksScreen
import com.speechnotes.app.ui.editor.NoteEditorScreen
import com.speechnotes.app.ui.notes.NotesListScreen
import com.speechnotes.app.ui.notes.NotebooksScreen
import com.speechnotes.app.ui.notes.RecycleBinScreen
import com.speechnotes.app.ui.onboarding.OnboardingScreen
import com.speechnotes.app.ui.settings.SettingsHomeScreen
import com.speechnotes.app.ui.theme.SpeechnotesTheme

/**
 * Root navigation: three tabs (Notes / Books / Settings) with the editor,
 * recycle bin and notebooks as pushed destinations, exactly mirroring the
 * iOS app's TabView + NavigationStack structure.
 */
@Composable
fun SpeechnotesApp(intakeFlow: StateFlow<MainActivity.Intake?> = MainActivity.intakeEvents) {
    val vm: SpeechnotesViewModel = viewModel(factory = SpeechnotesViewModel.Factory)
    val activity = LocalContext.current as? com.speechnotes.app.MainActivity
    androidx.compose.runtime.LaunchedEffect(vm) { activity?.attachPlayer(vm) }
    val themeState by vm.themeState.collectAsState()
    val onboarded by vm.hasOnboarded.collectAsState()
    SpeechnotesTheme(state = themeState) {
        if (!onboarded) {
            OnboardingScreen(onDone = { vm.setOnboarded(true) })
        } else {
            AppNavHost(vm, intakeFlow)
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    NOTES("notes", "Notes", Icons.Default.Note),
    BOOKS("books", "Books", Icons.Default.Book),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@Composable
private fun AppNavHost(
    vm: SpeechnotesViewModel,
    intakeFlow: StateFlow<MainActivity.Intake?>,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Consume inbound intents: text becomes a note, a file routes to books
    // (epub/pdf/audio) or a note (txt/md). Cleared after handling so a
    // rotation doesn't re-import.
    val intake by intakeFlow.collectAsState()
    LaunchedEffect(intake) {
        val event = intake ?: return@LaunchedEffect
        when (event) {
            is MainActivity.Intake.ImportText -> {
                vm.addNoteFromText(event.text)
                navController.navigateTab(Tab.NOTES)
            }
            is MainActivity.Intake.OpenUri -> {
                // The URI's own extension is unreliable (MEDIASTORE hands out
                // bare ids); let the ViewModel resolve the real filename.
                val ext = vm.extensionOf(event.uri)
                when (ext) {
                    "epub", "pdf", "m4b", "m4a", "mp4", "mp3" -> {
                        vm.importBook(event.uri)
                        navController.navigateTab(Tab.BOOKS)
                    }
                    else -> {
                        val imported = vm.models.importTextFrom(event.uri)
                        if (imported != null && vm.addNoteFromText(imported.second)) {
                            navController.navigateTab(Tab.NOTES)
                        }
                    }
                }
            }
        }
        MainActivity.consumeIntake()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (currentRoute in Tab.entries.map { it.route }) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { navController.navigateTab(tab) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Tab.NOTES.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Tab.NOTES.route) {
                    NotesListScreen(
                        vm = vm,
                        onOpenNote = { id -> navController.navigate("note/$id") },
                        onOpenRecycleBin = { navController.navigate("recycle") },
                        onOpenNotebooks = { navController.navigate("notebooks") },
                    )
                }
                composable(Tab.BOOKS.route) {
                    BooksScreen(
                        vm = vm,
                        onOpenReader = { id -> navController.navigate("book/$id") },
                        onOpenRecycleBin = { navController.navigate("book-recycle") },
                    )
                }
                composable(Tab.SETTINGS.route) {
                    SettingsHomeScreen(
                        vm = vm,
                        onOpen = { navController.navigate(it) },
                        onOpenLogs = { navController.navigate("logs") },
                    )
                }
                // Settings subpages (Unit 3): one route per area so each
                // page's state collection and pollers only run while shown.
                composable("settings/speech") {
                    com.speechnotes.app.ui.settings.SpeechSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/notes") {
                    com.speechnotes.app.ui.settings.NotesSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/appearance") {
                    com.speechnotes.app.ui.settings.AppearanceSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/stats") {
                    com.speechnotes.app.ui.settings.StatsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/bookdrop") {
                    com.speechnotes.app.ui.settings.BookDropSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable("settings/backup") {
                    com.speechnotes.app.ui.settings.BackupSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/storage") {
                    com.speechnotes.app.ui.settings.StorageSettingsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("settings/about") {
                    com.speechnotes.app.ui.settings.AboutScreen(onBack = { navController.popBackStack() })
                }
                composable("logs") {
                    com.speechnotes.app.ui.settings.LogsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("book/{bookId}") { entry ->
                    val bookId = entry.arguments?.getString("bookId") ?: return@composable
                    // Opening a binned book un-bins it (iOS markOpened).
                    vm.books.unbinIfDeleted(bookId)
                    BookReaderScreen(
                        vm = vm,
                        bookId = bookId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("note/{noteId}") { entry ->
                    NoteEditorScreen(
                        vm = vm,
                        noteId = entry.arguments?.getString("noteId") ?: return@composable,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("recycle") {
                    RecycleBinScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("book-recycle") {
                    BooksRecycleBinScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("notebooks") {
                    NotebooksScreen(vm = vm, onBack = { navController.popBackStack() })
                }
            }
        }

        // The global mini-player floats above the nav bar on tab routes;
        // elsewhere (editor, reader) it hugs the screen bottom instead of
        // hovering 80dp up over that screen's own controls.
        //
        // It MUST live in a BottomCenter-aligned box: the enclosing Box
        // defaults to TopStart, so a bottom padding alone left the bar
        // measured from the TOP of the screen — it drew behind/above the
        // top app bar and read as "there is no mini player at all". This is
        // the iOS .safeAreaInset(edge: .bottom) equivalent.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            MiniPlayerOverlay(
                vm = vm,
                onJumpToNote = {
                    navController.navigateTab(Tab.NOTES)
                    vm.player.nowPlayingNoteId.value?.let { id -> navController.navigate("note/$id") }
                },
                onJumpToBook = { navController.navigateTab(Tab.BOOKS) },
                // Land on the Storage subpage itself, not the settings root —
                // the jump exists to reach the exported-audio transport.
                onJumpToExports = { navController.navigate("settings/storage") },
                modifier = Modifier
                    .padding(bottom = if (currentRoute in Tab.entries.map { it.route }) 80.dp else 12.dp)
                    .imePadding(),
            )
        }
    }
}

private fun androidx.navigation.NavHostController.navigateTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
