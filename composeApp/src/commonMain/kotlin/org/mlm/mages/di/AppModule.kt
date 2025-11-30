
package org.mlm.mages.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.mlm.mages.MatrixService
import org.mlm.mages.ui.viewmodel.*

val appModule = module {

    // Room
    viewModel { (roomId: String, roomName: String) ->
        RoomViewModel(
            service = get(),
            dataStore = get(),
            roomId = roomId,
            roomName = roomName
        )
    }

    // Rooms list
    viewModel {
        RoomsViewModel(
            service = get(),
            dataStore = get()
        )
    }

    // Security
    viewModel {
        SecurityViewModel(
            service = get()
        )
    }

    // Login
    viewModel {
        LoginViewModel(
            service = get(),
            dataStore = get()
        )
    }

    // Spaces
    viewModel {
        SpacesViewModel(
            service = get()
        )
    }

    // Space Detail
    viewModel { (spaceId: String, spaceName: String) ->
        SpaceDetailViewModel(
            service = get(),
            spaceId = spaceId,
            spaceName = spaceName
        )
    }

    // Space Settings
    viewModel { (spaceId: String) ->
        SpaceSettingsViewModel(
            service = get(),
            spaceId = spaceId
        )
    }

    // Thread
    viewModel { (roomId: String, rootEventId: String) ->
        ThreadViewModel(
            service = get(),
            roomId = roomId,
            rootEventId = rootEventId
        )
    }

    // Room Info
    viewModel { (roomId: String) ->
        RoomInfoViewModel(
            service = get(),
            roomId = roomId
        )
    }

    // Discover
    viewModel {
        DiscoverViewModel(
            service = get()
        )
    }

    // Invites
    viewModel {
        InvitesViewModel(
            service = get()
        )
    }
}

fun appModules(
    service: MatrixService,
    dataStore: DataStore<Preferences>
) = listOf(
    module {
        single { service }
        single { dataStore }
    },
    appModule
)