package com.landolisp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landolisp.data.SandboxRepository
import com.landolisp.ui.editor.CodeEditor
import com.landolisp.ui.editor.CodeEditorState
import com.landolisp.ui.theme.CodeTextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReplViewModel(
    private val sandbox: SandboxRepository,
) : ViewModel() {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun send(code: String) {
        _running.value = true
        try {
            val resp = runCatching { sandbox.eval(code) }
            _output.value = buildString {
                if (_output.value.isNotEmpty()) {
                    append(_output.value)
                    append("\n\n")
                }
                append("> ")
                append(code.trim())
                append('\n')
                resp.fold(
                    onSuccess = { r ->
                        if (r.stdout.isNotEmpty()) append(r.stdout)
                        if (r.stderr.isNotEmpty()) {
                            if (r.stdout.isNotEmpty()) append('\n')
                            append(r.stderr)
                        }
                        r.value?.let { append("\n=> ").append(it) }
                        r.condition?.let { append("\n[").append(it.type).append("] ").append(it.message) }
                    },
                    onFailure = { append("Error: ").append(it.message) },
                )
            }
        } finally {
            _running.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplScreen() {
    val vm: ReplViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ReplViewModel(SandboxRepository()) }
        },
    )
    val output by vm.output.collectAsState()
    val running by vm.running.collectAsState()
    val scope = rememberCoroutineScope()
    val editorState = remember { mutableStateOf(TextFieldValue("")) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Sandbox") })
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                CodeEditor(
                    state = CodeEditorState(
                        text = editorState.value,
                        onTextChange = { editorState.value = it },
                    ),
                    modifier = Modifier.padding(12.dp),
                    onSubmit = {
                        scope.launch { vm.send(editorState.value.text) }
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !running && editorState.value.text.isNotBlank(),
                    onClick = { scope.launch { vm.send(editorState.value.text) } },
                ) { Text(if (running) "Sending…" else "Send") }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = if (output.isEmpty()) "Output will appear here." else output,
                        style = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
    }
}
