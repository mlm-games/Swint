use once_cell::sync::Lazy;
use std::sync::Mutex;
use tokio::runtime::Runtime;

use matrix_sdk::{config::SyncSettings, Client as SdkClient};
use matrix_sdk::ruma::OwnedRoomId;
use matrix_sdk::ruma::UserId;

use std::time::{SystemTime, UNIX_EPOCH};

// ---------- Types declared in UDL (dictionary/record equivalents) ----------
#[derive(Clone)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
}

#[derive(Clone)]
pub struct MessageEvent {
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    pub body: String,
    pub timestamp_ms: u64,
}

// ---------- Callback interface declared in UDL ----------
pub trait TimelineObserver {
    fn on_event(&self, event: MessageEvent);
}

// ---------- Runtime ----------
static RT: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime")
});

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

// ---------- Object exported to UniFFI ----------
pub struct Client {
    inner: SdkClient,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
}

impl Client {
    // Matches UDL: interface Client { constructor(string homeserver_url); }
    pub fn new(homeserver_url: String) -> Self {
        let inner = RT.block_on(async {
            SdkClient::builder()
                .homeserver_url(homeserver_url)
                .build()
                .await
                .expect("client")
        });
        Self { inner, guards: Mutex::new(vec![]) }
    }

    pub fn login(&self, username: String, password: String) {
        RT.block_on(async {
            // matrix-sdk 0.14 uses matrix_auth().login_username(...).send().await
            // Accept either full @user:server or just localpart.
            let id_str = if UserId::parse(&username).is_ok() {
                username.as_str()
            } else {
                username.as_str()
            };
            self.inner
                .matrix_auth()
                .login_username(id_str, &password)
                .send()
                .await
                .expect("login");
        });
    }

    pub fn rooms(&self) -> Vec<RoomSummary> {
        RT.block_on(async {
            // joined_rooms() is synchronous (returns Vec<Room>)
            let rooms = self.inner.joined_rooms();
            let mut out = Vec::with_capacity(rooms.len());
            for r in rooms {
                // display_name() is async
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
                    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    // Placeholder: returns empty to keep build stable.
    pub fn recent_events(&self, _room_id: String, _limit: u32) -> Vec<MessageEvent> {
        Vec::new()
    }

    // Placeholder: accepts a Box<dyn TimelineObserver> as UDL expects; no polling yet.
    pub fn observe_room_timeline(&self, _room_id: String, _observer: Box<dyn TimelineObserver>) {
        // When you want live updates, I’ll wire matrix-sdk-ui::Timeline::subscribe() here.
    }

    pub fn send_message(&self, room_id: String, body: String) {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return; };
            if let Some(room) = self.inner.get_room(&room_id) {
                let content = matrix_sdk::ruma::events::room::message::RoomMessageEventContent::text_plain(body);
                let _ = room.send(content).await;
            }
        });
    }

    pub fn shutdown(&self) {
        // Optional: abort self.guards tasks.
    }
}

// Keep the scaffolding include at the VERY END so the definitions above are visible.
uniffi::include_scaffolding!("frair");