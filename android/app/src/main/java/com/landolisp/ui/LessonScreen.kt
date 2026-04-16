package com.landolisp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landolisp.data.LessonRepository
import com.landolisp.data.SandboxRepository
import com.landolisp.data.model.Lesson
import com.landolisp.data.model.Section
import com.landolisp.ui.editor.CodeEditor
import com.landolisp.ui.editor.CodeEditorState
import com.landolisp.ui.markdown.MarkdownText
import com.landolisp.ui.theme.CodeTextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LessonViewModel(
    private val lessonRepo: LessonRepository,
    private val sandbox: SandboxRepository,
    private val lessonId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<LessonUiState>(LessonUiState.Loading)
    val state: StateFlow<LessonUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runCatching { lessonRepo.loadLesson(lessonId) }
                .map { LessonUiState.Loaded(it) }
                .getOrElse { LessonUiState.Error(it.message ?: "load failed") }
        }
    }

    /**
     * Run [code] in the sandbox and return the formatted result. Errors are folded into the
     * returned string so the UI doesn't have to discriminate.
     */
    suspend fun run(code: String): String = runCatching {
        val resp = sandbox.eval(code)
        buildString {
            if (resp.stdout.isNotEmpty()) append(resp.stdout)
            if (resp.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(resp.stderr)
            }
            resp.value?.let {
                if (isNotEmpty()) append('\n')
                append("=> $it")
            }
            resp.condition?.let {
                if (isNotEmpty()) append('\n')
                append("[${it.type}] ${it.message}")
            }
        }
    }.getOrElse { "Error: ${it.message}" }
}

sealed interface LessonUiState {
    data object Loading : LessonUiState
    data class Error(val message: String) : LessonUiState
    data class Loaded(val lesson: Lesson) : LessonUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(
    lessonId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LessonViewModel = viewModel(
        key = "lesson-$lessonId",
        factory = viewModelFactory {
            initializer {
                LessonViewModel(
                    lessonRepo = LessonRepository(context.applicationContext),
                    sandbox = SandboxRepository(),
                    lessonId = lessonId,
                )
            }
        },
    )
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val title = (state as? LessonUiState.Loaded)?.lesson?.title ?: "Lesson"
                Text(title)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
        when (val s = state) {
            LessonUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is LessonUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Error: ${s.message}")
            }
            is LessonUiState.Loaded -> LessonContent(s.lesson, vm)
        }
    }
}

@Composable
private fun LessonContent(lesson: Lesson, vm: LessonViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        lesson.sections.forEach { section ->
            when (section) {
                is Section.Prose -> MarkdownText(markdown = section.markdown)
                is Section.Example -> ExampleSection(section, vm, lesson.completionSymbols)
                is Section.Exercise -> ExerciseSection(section, vm, lesson.completionSymbols)
            }
        }
    }
}

@Composable
private fun ExampleSection(
    section: Section.Example,
    vm: LessonViewModel,
    completionSymbols: List<String>,
) {
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember(section.code) {
        mutableStateOf(TextFieldValue(section.code))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CodeEditor(
                state = CodeEditorState(
                    text = state.value,
                    onTextChange = { state.value = it },
                    completionSymbols = completionSymbols,
                ),
            )
            section.expected?.let {
                Text("Expected: $it", style = MaterialTheme.typography.labelMedium)
            }
            section.explain?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        output = vm.run(state.value.text)
                        running = false
                    }
                },
            ) { Text(if (running) "Running…" else "Run") }
            if (output.isNotEmpty()) OutputBlock(output)
        }
    }
}

@Composable
private fun ExerciseSection(
    section: Section.Exercise,
    vm: LessonViewModel,
    completionSymbols: List<String>,
) {
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember(section.starter) {
        mutableStateOf(TextFieldValue(section.starter))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(section.prompt, style = MaterialTheme.typography.titleLarge)
            section.hint?.let {
                Text("Hint: $it", style = MaterialTheme.typography.bodyMedium)
            }
            CodeEditor(
                state = CodeEditorState(
                    text = state.value,
                    onTextChange = { state.value = it },
                    completionSymbols = completionSymbols,
                ),
            )
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        // TODO(B4): submit user code, then evaluate each test.call and compare
                        // against test.equals using a structural comparator (probably another
                        // sandbox eval producing equalp). For now: just run the code.
                        output = vm.run(state.value.text)
                        running = false
                    }
                },
            ) { Text(if (running) "Submitting…" else "Submit") }
            if (output.isNotEmpty()) OutputBlock(output)
        }
    }
}

@Composable
private fun OutputBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.padding(12.dp),
        )
    }
}
