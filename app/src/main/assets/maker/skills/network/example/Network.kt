package skill.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// What the API sends back. Defaults everywhere: a missing field doesn't crash the app.
@Serializable
data class Joke(val id: Int = 0, val setup: String = "", val punchline: String = "")

object Api {
    // One client for the whole app.
    private val client = HttpClient(Android) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun randomJoke(): Joke = client.get("https://official-joke-api.appspot.com/random_joke").body()
}

// Loading, failed or loaded: every screen that fetches shows one of the three.
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
}

class JokeViewModel : ViewModel() {
    var state by mutableStateOf<Loadable<Joke>>(Loadable.Loading)
        private set

    init { load() }

    fun load() {
        state = Loadable.Loading
        viewModelScope.launch {
            state = try {
                Loadable.Loaded(Api.randomJoke())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed("Couldn't load a joke. Check the connection and try again.")
            }
        }
    }
}

@Composable
fun JokeScreen(vm: JokeViewModel = viewModel(), modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (val s = vm.state) {
            Loadable.Loading -> CircularProgressIndicator()
            is Loadable.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.message)
                Button(onClick = vm::load) { Text("Try again") }
            }
            is Loadable.Loaded -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(s.value.setup, style = MaterialTheme.typography.headlineSmall)
                Text(s.value.punchline, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = vm::load) { Text("Another one") }
            }
        }
    }
}

// An image from a URL, with a description for screen readers.
@Composable
fun RemoteImage(url: String, description: String, modifier: Modifier = Modifier) {
    AsyncImage(model = url, contentDescription = description, modifier = modifier.size(160.dp))
}
