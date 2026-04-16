package com.landolisp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landolisp.data.SandboxRepository
import kotlinx.coroutines.launch

/**
 * Minimal stand-in for [com.landolisp.ui.ReplScreen] used by
 * [ReplScreenE2ETest]. Mirrors the user-visible flow — type code, tap Send,
 * see "=> result" — without reaching into ReplScreen's internal ViewModel
 * construction. This file lives in `androidTest/` so it has zero impact
 * on the production APK.
 */
@Composable
internal fun MockReplHarness(repo: SandboxRepository) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Code") },
            modifier = Modifier.fillMaxSize(0.5f),
        )
        Button(onClick = {
            val code = input
            scope.launch {
                val r = runCatching { repo.eval(code) }
                output = r.fold(
                    onSuccess = { resp -> resp.value?.let { "=> $it" } ?: "(no value)" },
                    onFailure = { "Error: ${it.message}" },
                )
            }
        }) { Text("Send") }
        Text(output)
    }
}
