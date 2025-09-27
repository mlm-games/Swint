use once_cell::sync::Lazy;
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;

use futures_util::StreamExt;
use matrix_sdk::{authentication::matrix::MatrixSession, config::SyncSettings, Client as SdkClient, SessionTokens};
use matrix_sdk::ruma::{OwnedRoomId, UserId, MilliSecondsSinceUnixEpoch};
use matrix_sdk_ui::{eyeball_im::VectorDiff, timeline::{
    EventTimelineItem, RoomExt as _, Timeline, TimelineItem, TimelineItemContent,
}};

uniffi::setup_scaffolding!();

#[derive(Clone, uniffi::Record)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
}

#[derive(Clone, uniffi::Record)]
pub struct MessageEvent {
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    pub body: String,
    pub timestamp_ms: u64,
}

// Callback interface
#[uniffi::export(callback_interface)]
pub trait TimelineObserver: Send + Sync {
    fn on_event(&self, event: MessageEvent);
}

// Tokio runtime (single shared)
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
});

// ---------- Object exported to Kotlin ----------
#[derive(uniffi::Object)]
pub struct Client {
    inner: SdkClient,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
}

#[uniffi::export]
impl Client {
    #[uniffi::constructor]
    pub fn new(homeserver_url: String) -> Self {
        let inner = RT.block_on(async {
            SdkClient::builder()
                .homeserver_url(&homeserver_url)
                .build()
                .await
                .expect("client")
        });
        Self { inner, guards: Mutex::new(vec![]) }
    }

    pub fn login(&self, username: String, password: String) {
        RT.block_on(async {
            // Accept @user:server or localpart; matrix_auth handles both strings.
            let id_str = username.as_str();
            self.inner
                .matrix_auth()
                .login_username(id_str, &password)
                .send()
                .await
                .expect("login");
            // Seed store so rooms() has data
            let _ = self.inner.sync_once(SyncSettings::default()).await;
        });
    }

    pub fn rooms(&self) -> Vec<RoomSummary> {
        RT.block_on(async {
            let rooms = self.inner.joined_rooms();
            let mut out = Vec::with_capacity(rooms.len());
            for r in rooms {
                let name = r.display_name().await.unwrap_or_else(|_| matrix_sdk::RoomDisplayName::Named(matrix_sdk::RoomDisplayName::Aliased(matrix_sdk::RoomDisplayName::Calculated(matrix_sdk::RoomDisplayName::EmptyWas(r.room_id().to_string()).to_string()).to_string()).to_string()));
                out.push(RoomSummary { id: r.room_id().to_string(), name: name.to_string() });
            }
            out
        })
    }

    pub fn start_sliding_sync(&self) {
        let client = self.inner.clone();
        let h = RT.spawn(async move {
            let settings = SyncSettings::default();
            loop {
                if let Err(e) = client.sync_once(settings.clone()).await {
                    eprintln!("sync error: {e}");
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    // Stubs (compile-safe). Wire with matrix-sdk-ui later.
    pub fn recent_events(&self, _room_id: String, _limit: u32) -> Vec<MessageEvent> {
        Vec::new()
    }
    pub fn observe_room_timeline(&self, _room_id: String, _observer: Box<dyn TimelineObserver>) {}

    pub fn send_message(&self, room_id: String, body: String) {
        RT.block_on(async {
            // Simple best-effort sender
            if let Ok(room_id) = matrix_sdk::ruma::OwnedRoomId::try_from(room_id) {
                if let Some(room) = self.inner.get_room(&room_id) {
                    let content = matrix_sdk::ruma::events::room::message::RoomMessageEventContent::text_plain(body);
                    let _ = room.send(content).await;
                }
            }
        });
    }

    pub fn shutdown(&self) {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
    }
}