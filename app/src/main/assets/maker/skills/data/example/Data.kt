package skill.data

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- the database: one table of items ----

@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val done: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY done, created DESC")
    fun all(): Flow<List<Item>>

    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // One database for the whole app.
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app.db")
                .build().also { instance = it }
        }
    }
}

// ---- settings: DataStore, one file for the whole app ----

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(private val context: Context) {
    private val hideDone = booleanPreferencesKey("hide_done")
    val hideDoneFlow: Flow<Boolean> = context.settingsStore.data.map { it[hideDone] ?: false }
    suspend fun setHideDone(on: Boolean) { context.settingsStore.edit { it[hideDone] = on } }
}

// ---- the ViewModel: what the screen shows, and what it can change ----

class ItemsViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).items()
    private val settings = Settings(app)

    val items: StateFlow<List<Item>> = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hideDone: StateFlow<Boolean> = settings.hideDoneFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun add(name: String) = viewModelScope.launch { if (name.isNotBlank()) dao.insert(Item(name = name.trim())) }
    fun toggle(item: Item) = viewModelScope.launch { dao.update(item.copy(done = !item.done)) }
    fun remove(item: Item) = viewModelScope.launch { dao.delete(item) }
    fun setHideDone(on: Boolean) = viewModelScope.launch { settings.setHideDone(on) }
}

// ---- the screen ----

@Composable
fun ItemsScreen(vm: ItemsViewModel = viewModel(), modifier: Modifier = Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val hideDone by vm.hideDone.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("New item") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { vm.add(text); text = "" }, enabled = text.isNotBlank()) { Text("Add") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide done", modifier = Modifier.weight(1f))
            Switch(checked = hideDone, onCheckedChange = { vm.setHideDone(it) })
        }
        val shown = if (hideDone) items.filter { !it.done } else items
        if (shown.isEmpty()) {
            Text("Nothing here yet: add your first item above.")
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        leadingContent = { Checkbox(checked = item.done, onCheckedChange = { vm.toggle(item) }) },
                        trailingContent = { IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${item.name}") } },
                    )
                }
            }
        }
    }
}
