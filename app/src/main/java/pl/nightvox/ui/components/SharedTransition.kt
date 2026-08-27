package pl.nightvox.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Uchwyty potrzebne do przejścia współdzielonego, podawane przez CompositionLocal.
 *
 * Alternatywą byłoby przepchnięcie dwóch zakresów przez sygnatury wszystkich ekranów po
 * drodze. CompositionLocal trzyma to poza sygnaturami, a przy okazji daje bezpieczne
 * zachowanie tam, gdzie zakresów nie ma — w testach komponentów i w podglądach: modyfikator
 * po prostu nic nie robi zamiast się wywalić.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Immutable
class SharedScopes(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

/**
 * Oznacza element jako wspólny dla dwóch ekranów — przy przejściu przepływa z jednego na
 * drugi zamiast zniknąć i pojawić się gdzie indziej.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedWith(key: String): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.shared) {
        this@sharedWith.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = scopes.visibility,
        )
    }
}

/** Klucz tarczy odtwarzania: wiersz listy i przycisk w szczegółach klipu to ta sama rzecz. */
fun clipPlayKey(clipId: String) = "clip-play-$clipId"
