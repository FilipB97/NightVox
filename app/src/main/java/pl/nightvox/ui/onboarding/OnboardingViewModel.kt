package pl.nightvox.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.nightvox.AppContainer

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    /**
     * `null` dopóki DataStore nie odpowie.
     *
     * Trasa startowa nawigacji jest ustalana raz, przy pierwszej kompozycji, więc nie wolno
     * jej zgadywać: gdybyśmy zaczęli od `false`, każdy start aplikacji mignąłby powitaniem.
     */
    val completed: StateFlow<Boolean?> = container.settingsStore.settings
        .map { it.onboardingCompleted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(onboardingCompleted = true) }
            onDone()
        }
    }
}
