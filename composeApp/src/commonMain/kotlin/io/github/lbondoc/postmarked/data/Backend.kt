package io.github.lbondoc.postmarked.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow

// Safe to ship inside the app: this key only says which project to talk to. What a signed-in
// user may read or change is decided by the row-level security rules in supabase/migrations.
private const val SUPABASE_URL = "https://bzixdjhzawvqbmskdtms.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_BfQh9P_bIi3kKVj5YJgaMg_F1J7N_dx"

/** The one connection to the backend. `by lazy` builds it the first time it is used. */
val supabase: SupabaseClient by lazy {
    createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
        install(Auth) // sign-in; keeps the session on the device and refreshes it
        install(Postgrest) // tables and database functions over HTTPS
        install(Storage) {
            // The default two minutes is too short for a 50 MB video on slow hotel Wi-Fi.
            transferTimeout = 10.minutes
        }
    }
}

/** The signed-in user's id, or null when signed out. */
val myUserId: String? get() = supabase.auth.currentUserOrNull()?.id

/**
 * An invite token that arrived from outside the app (a tapped link), waiting to be redeemed
 * once the user is signed in. A StateFlow is a value holder the UI can observe.
 */
val pendingInvite = MutableStateFlow<String?>(null)

/** A sentence fit to show the user. Database functions already raise readable messages. */
fun Throwable.userMessage(): String = when (this) {
    is AuthRestException -> errorDescription
    is RestException -> error
    is HttpRequestException -> "Can't reach the server. Check your connection and try again."
    else -> message ?: "Something went wrong."
}
