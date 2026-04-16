package com.landolisp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landolisp.R
import com.landolisp.data.LessonRepository
import com.landolisp.data.model.LessonSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LessonListViewModel(
    private val repo: LessonRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<LessonListState>(LessonListState.Loading)
    val state: StateFlow<LessonListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.lessons().collect { list ->
                _state.value = if (list.isEmpty()) LessonListState.Empty else LessonListState.Loaded(list.groupBy { it.track })
            }
        }
    }
}

sealed interface LessonListState {
    data object Loading : LessonListState
    data object Empty : LessonListState
    data class Loaded(val byTrack: Map<String, List<LessonSummary>>) : LessonListState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonListScreen(
    onLessonClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val vm: LessonListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LessonListViewModel(LessonRepository(context.applicationContext)) }
        },
    )
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        when (val s = state) {
            LessonListState.Loading -> Centered { CircularProgressIndicator() }
            LessonListState.Empty -> Centered {
                Text(stringResource(R.string.state_empty_lessons), style = MaterialTheme.typography.bodyLarge)
            }
            is LessonListState.Loaded -> LessonList(s.byTrack, onLessonClick)
        }
    }
}

@Composable
private fun LessonList(
    byTrack: Map<String, List<LessonSummary>>,
    onLessonClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        byTrack.forEach { (track, lessons) ->
            item(key = "header-$track") {
                Text(
                    text = track.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(lessons, key = { it.id }) { lesson ->
                LessonCard(lesson, onLessonClick)
            }
        }
    }
}

@Composable
private fun LessonCard(
    lesson: LessonSummary,
    onLessonClick: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLessonClick(lesson.id) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${lesson.order}. ${lesson.title}",
                style = MaterialTheme.typography.titleLarge,
            )
            lesson.estimatedMinutes?.let { mins ->
                Text(
                    text = "~$mins min",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
