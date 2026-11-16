package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.lbondoc.postmarked.data.supabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MIN_PASSWORD = 6 // Supabase's default minimum

class SignInViewModel : ScreenModel() {
    // On success there is nothing to do here: App() is watching the session and swaps screens.
    fun signIn(email: String, password: String) = run {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    fun createAccount(name: String, email: String, password: String) = run {
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject { put("name", name.trim()) } // becomes the profile's display name
        }
        // No session after sign-up means the project still requires confirming the email first.
        if (supabase.auth.currentSessionOrNull() == null) {
            error = "Account created. Confirm it from the email we sent, then sign in."
        }
    }
}

@Composable
fun SignInScreen(vm: SignInViewModel = viewModel { SignInViewModel() }) {
    // rememberSaveable keeps what was typed across screen rotation.
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Postmarked", style = MaterialTheme.typography.displaySmall)
            Text(if (creating) "Create your account" else "Sign in", style = MaterialTheme.typography.titleMedium)
            if (creating) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) },
                    label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { if (creating) Text("At least $MIN_PASSWORD characters") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { if (creating) vm.createAccount(name, email, password) else vm.signIn(email, password) },
                enabled = !vm.busy && email.isNotBlank() && password.length >= MIN_PASSWORD && (!creating || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (creating) "Create account" else "Sign in") }
            TextButton(onClick = { creating = !creating }) {
                Text(if (creating) "I already have an account" else "Create an account")
            }
        }
    }
}
