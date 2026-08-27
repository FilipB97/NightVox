package pl.nightvox.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pl.nightvox.NightVoxApp
import pl.nightvox.ui.calibration.CalibrationViewModel
import pl.nightvox.ui.clip.ClipsViewModel
import pl.nightvox.ui.home.HomeViewModel
import pl.nightvox.ui.onboarding.OnboardingViewModel
import pl.nightvox.ui.sessions.SessionsViewModel
import pl.nightvox.ui.settings.SettingsViewModel

private val CreationExtras.app: NightVoxApp
    get() = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NightVoxApp

/** Jedna fabryka dla wszystkich ViewModeli — graf jest ręczny, więc i to jest ręczne. */
val NightVoxViewModelFactory = viewModelFactory {
    initializer { HomeViewModel(app) }
    initializer { SettingsViewModel(app.container) }
    initializer { SessionsViewModel(app.container) }
    initializer { ClipsViewModel(app.container) }
    initializer { CalibrationViewModel(app) }
    initializer { OnboardingViewModel(app.container) }
}
