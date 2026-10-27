package skill.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Note(val id: Int, val title: String)

// The app's state, shared by every screen and kept across rotation.
class NotesViewModel : ViewModel() {
    private val _notes = MutableStateFlow(listOf<Note>())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private val _compact = MutableStateFlow(false)
    val compact: StateFlow<Boolean> = _compact.asStateFlow()

    fun add(title: String) = _notes.update { list -> list + Note((list.maxOfOrNull { it.id } ?: 0) + 1, title) }
    fun find(id: Int): Note? = _notes.value.firstOrNull { it.id == id }
    fun setCompact(on: Boolean) { _compact.value = on }
}

// Two tabs at the bottom (notes, settings), and a detail screen on top of the list.
@Composable
fun ScreensApp(vm: NotesViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (route == "list" || route == "settings") {
                NavigationBar {
                    listOf("list" to "Notes", "settings" to "Settings").forEach { (r, label) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = {
                                nav.navigate(r) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (r == "list") Icons.AutoMirrored.Filled.List else Icons.Default.Settings, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "list", modifier = Modifier.padding(padding)) {
            composable("list") { ListScreen(vm, onOpen = { id -> nav.navigate("detail/$id") }) }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
                DetailScreen(vm.find(e.arguments?.getInt("id") ?: -1), onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: NotesViewModel, onOpen: (Int) -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Notes") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.add("Note ${notes.size + 1}") }) { Icon(Icons.Default.Add, contentDescription = "Add a note") }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No notes yet: tap + to add one")
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(notes, key = { it.id }) { note ->
                    ListItem(headlineContent = { Text(note.title) }, modifier = Modifier.clickable { onOpen(note.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(note: Note?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title ?: "Not found") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text(if (note == null) "This note was deleted." else "Note number ${note.id}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: NotesViewModel) {
    val compact by vm.compact.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        ListItem(
            headlineContent = { Text("Compact list") },
            trailingContent = { Switch(checked = compact, onCheckedChange = vm::setCompact) },
            modifier = Modifier.padding(padding),
        )
    }
}
