package org.mlm.mages.di

import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.compose.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.mlm.mages.MatrixService

/**
 * Composable wrapper that provides Koin context to the app.
 */
@Composable
fun KoinApp(
    service: MatrixService,
    dataStore: DataStore<Preferences>,
    content: @Composable () -> Unit
) {
    KoinApplication(
        application = {
            modules(appModules(service, dataStore))
        }
    ) {
        content()
    }
}

/**
 * Initialize Koin for non-Compose contexts (e.g., Android Application class).
 */
fun initKoin(
    service: MatrixService,
    dataStore: DataStore<Preferences>,
    additionalModules: List<Module> = emptyList()
) {
    startKoin {
        modules(appModules(service, dataStore) + additionalModules)
    }
}

/**
 * Stop Koin (useful for testing or cleanup).
 */
fun stopKoinApp() {
    stopKoin()
}