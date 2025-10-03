use mime::Mime;
use once_cell::sync::Lazy;
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;

use futures_util::StreamExt;
use thiserror::Error;

use matrix_sdk::{
    executor::spawn, ruma::events::key::verification::request::ToDeviceKeyVerificationRequestEvent,
};
// Matrix SDK core and UI
use matrix_sdk::ruma::{
    api::client::media::get_content_thumbnail::v3::Method as ThumbnailMethod,
    api::client::receipt::create_receipt::v3::ReceiptType, events::typing::SyncTypingEvent,
    DeviceId, EventId, OwnedDeviceId, OwnedRoomId, OwnedUserId, UserId,
};
use matrix_sdk::{
    attachment::AttachmentConfig,
    authentication::matrix::MatrixSession,
    config::SyncSettings,
    media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings},
    ruma::{events::room::MediaSource, OwnedMxcUri},
    Client as SdkClient, Room, SessionTokens,
};
use matrix_sdk::{
    encryption::verification::{SasState as SdkSasState, SasVerification, VerificationRequest},
    ruma::events::receipt::ReceiptThread,
};
use matrix_sdk_ui::{
    eyeball_im::VectorDiff,
    timeline::{EventTimelineItem, RoomExt as _, Timeline, TimelineEventItemId, TimelineItem},
};

use std::panic::AssertUnwindSafe;

// UniFFI macro-first setup
uniffi::setup_scaffolding!();

const BACKOFF_BASE_MS: u64 = 1_000;
const BACKOFF_MAX_MS: u64 = 60_000;
const SEND_MAX_ATTEMPTS: i64 = 10;
const MAX_CONSECUTIVE_SYNC_FAILURES: u32 = 20;

// Types exposed to Kotlin
#[derive(Clone, uniffi::Record)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Syncing,
    Reconnecting { attempt: u32, next_retry_secs: u32 },
}

#[uniffi::export(callback_interface)]
pub trait ConnectionObserver: Send + Sync {
    fn on_connection_change(&self, state: ConnectionState);
}

#[derive(Clone, uniffi::Record)]
pub struct MessageEvent {
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    pub body: String,
    pub timestamp_ms: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct DeviceSummary {
    pub device_id: String,
    pub display_name: String,
    pub ed25519: String,
    pub is_own: bool,
    pub locally_trusted: bool,
}

#[derive(Clone, uniffi::Enum)]
pub enum SyncPhase {
    Idle,
    Running,
    BackingOff,
    Error,
}

#[derive(Clone, uniffi::Record)]
pub struct SyncStatus {
    pub phase: SyncPhase,
    pub message: Option<String>,
}

#[uniffi::export(callback_interface)]
pub trait SyncObserver: Send + Sync {
    fn on_state(&self, status: SyncStatus);
}

#[uniffi::export(callback_interface)]
pub trait TypingObserver: Send + Sync {
    fn on_update(&self, names: Vec<String>);
}

#[uniffi::export(callback_interface)]
pub trait ProgressObserver: Send + Sync {
    fn on_progress(&self, sent: u64, total: Option<u64>);
}

#[derive(Clone, uniffi::Record)]
pub struct DownloadResult {
    pub path: String,
    pub bytes: u64,
}

#[derive(Clone, uniffi::Enum)]
pub enum SasPhase {
    Requested,
    Ready,
    Emojis,
    Confirmed,
    Cancelled,
    Failed,
    Done,
}

#[derive(Clone, uniffi::Record)]
pub struct SasEmojis {
    pub flow_id: String,
    pub other_user: String,
    pub other_device: String,
    pub emojis: Vec<String>,
}

#[uniffi::export(callback_interface)]
pub trait TimelineObserver: Send + Sync {
    fn on_event(&self, event: MessageEvent);
}

#[uniffi::export(callback_interface)]
pub trait VerificationObserver: Send + Sync {
    fn on_phase(&self, flow_id: String, phase: SasPhase);
    fn on_emojis(&self, payload: SasEmojis);
    fn on_error(&self, flow_id: String, message: String);
}

#[uniffi::export(callback_interface)]
pub trait VerificationInboxObserver: Send + Sync {
    fn on_request(&self, flow_id: String, from_user: String, from_device: String);
    fn on_error(&self, message: String);
}

#[derive(Clone, uniffi::Record)]
pub struct MediaCacheStats {
    pub bytes: u64,
    pub files: u64,
}

fn cache_dir(dir: &PathBuf) -> PathBuf {
    dir.join("media_cache")
}

fn ensure_dir(d: &PathBuf) {
    let _ = std::fs::create_dir_all(d);
}

fn file_len(path: &std::path::Path) -> u64 {
    std::fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

fn cache_size_bytes(dir: &PathBuf) -> u64 {
    if !dir.exists() {
        return 0;
    }
    walkdir::WalkDir::new(dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .map(|e| file_len(e.path()))
        .sum()
}

// Runtime
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

// Session persistence
#[derive(Clone, serde::Serialize, serde::Deserialize)]
struct SessionInfo {
    user_id: String,
    device_id: String,
    access_token: String,
    refresh_token: Option<String>,
    homeserver: String,
}

fn default_store_dir() -> PathBuf {
    std::env::temp_dir().join("frair_store")
}

fn session_file(dir: &PathBuf) -> PathBuf {
    dir.join("session.json")
}

fn trust_file(dir: &PathBuf) -> PathBuf {
    dir.join("trusted_devices.json")
}

fn read_trusted(dir: &PathBuf) -> Vec<String> {
    let path = trust_file(dir);
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|txt| serde_json::from_str::<Vec<String>>(&txt).ok())
        .unwrap_or_default()
}

fn write_trusted(dir: &PathBuf, ids: &[String]) -> bool {
    let path = trust_file(dir);
    if let Ok(txt) = serde_json::to_string(ids) {
        return std::fs::write(path, txt).is_ok();
    }
    false
}

fn read_all(path: &str) -> std::io::Result<Vec<u8>> {
    std::fs::read(path)
}

#[derive(Debug, Error, uniffi::Error)]
pub enum FfiError {
    #[error("{0}")]
    Msg(String),
}

impl From<matrix_sdk::Error> for FfiError {
    fn from(e: matrix_sdk::Error) -> Self {
        FfiError::Msg(e.to_string())
    }
}

impl From<std::io::Error> for FfiError {
    fn from(e: std::io::Error) -> Self {
        FfiError::Msg(e.to_string())
    }
}

#[derive(Clone, uniffi::Record)]
pub struct PaginationState {
    pub room_id: String,
    pub prev_batch: Option<String>,
    pub next_batch: Option<String>,
    pub at_start: bool,
    pub at_end: bool,
}

struct VerifFlow {
    sas: SasVerification,
    other_user: OwnedUserId,
    other_device: OwnedDeviceId,
}

type VerifMap = Arc<Mutex<HashMap<String, VerifFlow>>>;

#[derive(uniffi::Object)]
pub struct Client {
    inner: SdkClient,
    store_dir: PathBuf,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
    verifs: VerifMap,
    send_observers: Arc<Mutex<Vec<Arc<dyn SendObserver>>>>,
    send_tx: tokio::sync::mpsc::UnboundedSender<SendUpdate>,
    inbox: Arc<Mutex<HashMap<String, (OwnedUserId, OwnedDeviceId)>>>,
}

#[derive(Clone, uniffi::Enum)]
pub enum SendState {
    Enqueued,
    Sending,
    Sent,
    Retrying,
    Failed,
}

#[derive(Clone, uniffi::Record)]
pub struct SendUpdate {
    pub room_id: String,
    pub txn_id: String,
    pub attempts: u32,
    pub state: SendState,
    pub event_id: Option<String>,
    pub error: Option<String>,
}

#[uniffi::export(callback_interface)]
pub trait SendObserver: Send + Sync {
    fn on_update(&self, update: SendUpdate);
}

#[uniffi::export(callback_interface)]
pub trait TimelineDiffObserver: Send + Sync {
    fn on_insert(&self, event: MessageEvent);
    fn on_update(&self, event: MessageEvent);
    fn on_remove(&self, event_id: String);
    fn on_clear(&self);
    fn on_reset(&self, events: Vec<MessageEvent>);
}

struct QueuedItem {
    id: i64,
    room_id: String,
    body: String,
    txn_id: String,
    attempts: i64,
}

#[uniffi::export]
impl Client {
    #[uniffi::constructor]
    pub fn new(homeserver_url: String) -> Self {
        let store_dir = default_store_dir();

        let inner = RT.block_on(async {
            SdkClient::builder()
                .homeserver_url(&homeserver_url)
                .sqlite_store(&store_dir, None)
                .build()
                .await
                .expect("client")
        });

        let (send_tx, mut send_rx) = tokio::sync::mpsc::unbounded_channel::<SendUpdate>();

        let this = Self {
            inner,
            store_dir,
            guards: Mutex::new(vec![]),
            verifs: Arc::new(Mutex::new(HashMap::new())),
            send_observers: Arc::new(Mutex::new(Vec::new())),
            send_tx,
            inbox: Arc::new(Mutex::new(HashMap::new())),
        };

        // Initialize caches early - fail fast if there's a problem
        if !this.init_caches() {
            panic!("Failed to initialize local caches");
        }

        {
            let observers = this.send_observers.clone();
            let h = RT.spawn(async move {
                while let Some(upd) = send_rx.recv().await {
                    let list: Vec<Arc<dyn SendObserver>> = {
                        let guard = observers.lock().expect("send_observers");
                        guard.iter().cloned().collect()
                    };

                    for obs in list {
                        let upd_clone = upd.clone(); // Clone per observer
                        let _ = std::panic::catch_unwind(AssertUnwindSafe(move || {
                            obs.on_update(upd_clone)
                        }));
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        // Initialize send queue database with WAL mode
        {
            let path = default_store_dir().join("send_queue.sqlite3");
            if let Ok(conn) = rusqlite::Connection::open(&path) {
                let _ = conn.execute_batch(
                    "PRAGMA journal_mode=WAL;
                     PRAGMA synchronous=NORMAL;
                     CREATE TABLE IF NOT EXISTS queued_sends(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        room_id TEXT NOT NULL,
                        body TEXT NOT NULL,
                        txn_id TEXT NOT NULL UNIQUE,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        next_try_ms INTEGER NOT NULL DEFAULT 0
                    );",
                );
            }
        }

        RT.block_on(async {
            match this.inner.whoami().await {
                Ok(_) => {
                    let _ = this.inner.sync_once(SyncSettings::default()).await;
                    return;
                }
                Err(_) => { /* fall through to JSON restore */ }
            }

            let path = session_file(&this.store_dir);
            if let Ok(txt) = tokio::fs::read_to_string(&path).await {
                if let Ok(info) = serde_json::from_str::<SessionInfo>(&txt) {
                    if let Ok(user_id) = info.user_id.parse::<OwnedUserId>() {
                        let session = MatrixSession {
                            meta: matrix_sdk::SessionMeta {
                                user_id,
                                device_id: info.device_id.into(),
                            },
                            tokens: SessionTokens {
                                access_token: info.access_token.clone(),
                                refresh_token: info.refresh_token.clone(),
                            },
                        };

                        match this.inner.restore_session(session).await {
                            Ok(_) => {
                                let _ = this.inner.sync_once(SyncSettings::default()).await;
                                return;
                            }
                            Err(_) => {
                                // Session restore failed - clean up stale data
                                let _ = tokio::fs::remove_file(&path).await;
                                reset_store_dir(&this.store_dir);
                            }
                        }
                    }
                }
            }
        });

        // Token refresh handler
        {
            let inner = this.inner.clone();
            let store = this.store_dir.clone();
            let h = RT.spawn(async move {
                let mut session_rx = inner.subscribe_to_session_changes();

                while let Ok(update) = session_rx.recv().await {
                    match update {
                        matrix_sdk::SessionChange::TokensRefreshed => {
                            if let Some(sess) = inner.matrix_auth().session() {
                                let path = session_file(&store);
                                let info = SessionInfo {
                                    user_id: sess.meta.user_id.to_string(),
                                    device_id: sess.meta.device_id.to_string(),
                                    access_token: sess.tokens.access_token.clone(),
                                    refresh_token: sess.tokens.refresh_token.clone(),
                                    homeserver: inner.homeserver().to_string(),
                                };
                                let _ =
                                    tokio::fs::write(path, serde_json::to_string(&info).unwrap())
                                        .await;
                            }
                        }
                        matrix_sdk::SessionChange::UnknownToken { soft_logout: _ } => {
                            let _ = inner.matrix_auth().refresh_access_token().await;
                        }
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        this
    }

    pub fn observe_sends(&self, observer: Box<dyn SendObserver>) {
        let obs: Arc<dyn SendObserver> = Arc::from(observer);
        self.send_observers.lock().unwrap().push(obs);
    }

    pub fn whoami(&self) -> Option<String> {
        self.inner.user_id().map(|u| u.to_string())
    }

    pub fn login(&self, username: String, password: String) {
        RT.block_on(async {
            let res = self
                .inner
                .matrix_auth()
                .login_username(username.as_str(), &password)
                .send()
                .await
                .expect("login");

            let info = SessionInfo {
                user_id: res.user_id.to_string(),
                device_id: res.device_id.to_string(),
                access_token: res.access_token.clone(),
                refresh_token: res.refresh_token.clone(),
                homeserver: self.inner.homeserver().to_string(),
            };
            let _ = tokio::fs::create_dir_all(&self.store_dir).await;
            let _ = tokio::fs::write(
                session_file(&self.store_dir),
                serde_json::to_string(&info).unwrap(),
            )
            .await;

            let _ = self.inner.sync_once(SyncSettings::default()).await;
        });
    }

    pub fn rooms(&self) -> Vec<RoomSummary> {
        RT.block_on(async {
            let mut out = Vec::new();
            for r in self.inner.joined_rooms() {
                let name = r
                    .display_name()
                    .await
                    .map(|dn| dn.to_string())
                    .unwrap_or_else(|_| r.room_id().to_string());
                out.push(RoomSummary {
                    id: r.room_id().to_string(),
                    name,
                });
            }
            out
        })
    }

    pub fn set_typing(&self, room_id: String, typing: bool) -> bool {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return false;
            };
            // Typing notice stays active ~4s server-side; safe to debounce on client.
            room.typing_notice(typing).await.is_ok()
        })
    }

    pub fn enter_foreground(&self) {}

    /// Send the app to background: stop Sliding Sync supervision. (stub)
    pub fn enter_background(&self) {}

    pub fn recent_events(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return vec![];
            };
            let Some(room) = self.inner.get_room(&room_id) else {
                return vec![];
            };
            let Ok(timeline) = room.timeline().await else {
                return vec![];
            };

            let (items, _stream) = timeline.subscribe().await;
            let mut out: Vec<MessageEvent> = items
                .iter()
                .rev()
                .filter_map(|it| it.as_event().and_then(|ev| map_event(ev, room_id.as_str())))
                .take(limit as usize)
                .collect();
            out.reverse();
            out
        })
    }

    pub fn observe_room_timeline_diffs(
        &self,
        room_id: String,
        observer: Box<dyn TimelineDiffObserver>,
    ) {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return;
        };
        let obs: Arc<dyn TimelineDiffObserver> = Arc::from(observer);

        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&room_id) else {
                return;
            };
            let Ok(tl) = room.timeline().await else {
                return;
            };
            let (items, mut stream) = tl.subscribe().await;

            // First snapshot as a reset
            let initial: Vec<_> = items
                .iter()
                .filter_map(|it| it.as_event().and_then(|ei| map_event(ei, room_id.as_str())))
                .collect();
            obs.on_reset(initial);

            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    match diff {
                        VectorDiff::PushBack { value }
                        | VectorDiff::PushFront { value }
                        | VectorDiff::Insert { value, .. } => {
                            if let Some(ei) = value.as_event() {
                                if let Some(ev) = map_event(ei, room_id.as_str()) {
                                    obs.on_insert(ev);
                                }
                            }
                        }
                        VectorDiff::Set { value, .. } => {
                            if let Some(ei) = value.as_event() {
                                if let Some(ev) = map_event(ei, room_id.as_str()) {
                                    obs.on_update(ev);
                                }
                            }
                        }
                        VectorDiff::Remove { index, .. } => {
                            if let Some(item) = tl.items().await.get(index) {
                                if let Some(ev) = item.as_event() {
                                    if let Some(eid) = ev.event_id().map(|e| e.to_string()) {
                                        obs.on_remove(eid);
                                    }
                                }
                            }
                        }
                        VectorDiff::Clear {} => obs.on_clear(),
                        VectorDiff::Reset { values } => {
                            let events = values
                                .into_iter()
                                .filter_map(|it| {
                                    it.as_event().and_then(|ei| map_event(ei, room_id.as_str()))
                                })
                                .collect();
                            obs.on_reset(events);
                        }
                        VectorDiff::PopBack {}
                        | VectorDiff::PopFront {}
                        | VectorDiff::Truncate { .. }
                        | VectorDiff::Append { .. } => {}
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn start_verification_inbox(&self, observer: Box<dyn VerificationInboxObserver>) {
        let client = self.inner.clone();
        let obs: Arc<dyn VerificationInboxObserver> = Arc::from(observer);
        let inbox = self.inbox.clone();

        let h = RT.spawn(async move {
            // Observe to-device verification requests and forward them
            let handler = client.observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
            let mut sub = handler.subscribe();

            while let Some((ev, ())) = sub.next().await {
                let flow_id = ev.content.transaction_id.to_string();
                let from_user = ev.sender.to_string();
                let from_device = ev.content.from_device.to_string();

                // Remember for later acceptance
                inbox
                    .lock()
                    .unwrap()
                    .insert(flow_id.clone(), (ev.sender, ev.content.from_device.clone()));

                // Best-effort callback (shield UI from panics)
                let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                    obs.on_request(flow_id.clone(), from_user.clone(), from_device.clone());
                }));
            }
        });

        self.guards.lock().unwrap().push(h);
    }

    pub fn check_verification_request(&self, user_id: String, flow_id: String) -> bool {
        RT.block_on(async {
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                return false;
            };

            self.inner
                .encryption()
                .get_verification_request(&uid, &flow_id)
                .await
                .is_some()
        })
    }

    pub fn monitor_connection(&self, observer: Box<dyn ConnectionObserver>) {
        let client = self.inner.clone();
        let obs: Arc<dyn ConnectionObserver> = Arc::from(observer);

        let h = RT.spawn(async move {
            let mut last_state = ConnectionState::Disconnected;
            let mut session_rx = client.subscribe_to_session_changes();

            loop {
                tokio::select! {
                    Ok(change) = session_rx.recv() => {
                        let current = match change {
                            matrix_sdk::SessionChange::TokensRefreshed => ConnectionState::Connected,
                            matrix_sdk::SessionChange::UnknownToken { .. } => ConnectionState::Reconnecting {
                                attempt: 1,
                                next_retry_secs: 5
                            },
                        };

                        if !matches!((&current, &last_state),
                            (ConnectionState::Connected, ConnectionState::Connected) |
                            (ConnectionState::Disconnected, ConnectionState::Disconnected))
                        {
                            obs.on_connection_change(current.clone());
                            last_state = current;
                        }
                    }
                    _ = tokio::time::sleep(Duration::from_secs(30)) => {
                        // Periodic health check
                        let is_active = client.is_active();
                        let current = if is_active {
                            ConnectionState::Connected
                        } else {
                            ConnectionState::Disconnected
                        };

                        if !matches!((&current, &last_state),
                            (ConnectionState::Connected, ConnectionState::Connected) |
                            (ConnectionState::Disconnected, ConnectionState::Disconnected))
                        {
                            obs.on_connection_change(current.clone());
                            last_state = current;
                        }
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn send_message(&self, room_id: String, body: String) {
        RT.block_on(async {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContent as Msg;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return;
            };
            if let Some(timeline) = get_timeline_for(&self.inner, &room_id).await {
                // Local echo + auto encryption if needed.
                let _ = timeline.send(Msg::text_plain(body).into()).await;
            }
        });
    }

    pub fn shutdown(&self) {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
    }

    pub fn logout(&self) -> bool {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        let _ = RT.block_on(async { self.inner.matrix_auth().logout().await });
        let _ = std::fs::remove_file(session_file(&self.store_dir));
        reset_store_dir(&self.store_dir);
        true
    }

    pub fn mark_read(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(room) = self.inner.get_room(&room_id) else {
                return false;
            };
            let Ok(tl) = room.timeline().await else {
                return false;
            };
            // Use the UI helper
            match tl.mark_as_read(ReceiptType::Read).await {
                Ok(_) => true, // success even if no new receipt was sent
                Err(_) => false,
            }
        })
    }

    pub fn mark_read_at(&self, room_id: String, event_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = EventId::parse(event_id) else {
                return false;
            };

            let Some(room) = self.inner.get_room(&room_id) else {
                return false;
            };

            room.send_single_receipt(ReceiptType::Read, ReceiptThread::Unthreaded, eid.to_owned())
                .await
                .is_ok()
        })
    }

    pub fn media_cache_stats(&self) -> MediaCacheStats {
        let dir = cache_dir(&self.store_dir);
        if !dir.exists() {
            return MediaCacheStats { bytes: 0, files: 0 };
        }
        let mut files = 0u64;
        let mut bytes = 0u64;
        for entry in walkdir::WalkDir::new(&dir)
            .into_iter()
            .filter_map(|e| e.ok())
        {
            if entry.file_type().is_file() {
                files += 1;
                bytes += file_len(entry.path());
            }
        }
        MediaCacheStats { bytes, files }
    }

    pub fn media_cache_evict(&self, max_bytes: u64) -> u64 {
        use std::time::UNIX_EPOCH;
        let dir = cache_dir(&self.store_dir);
        if !dir.exists() {
            return 0;
        }

        let mut entries: Vec<(std::path::PathBuf, u64, u64)> = walkdir::WalkDir::new(&dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .filter_map(|e| {
                let p = e.into_path();
                let md = std::fs::metadata(&p).ok()?;
                let mtime = md
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                let size = md.len();
                Some((p, mtime, size))
            })
            .collect();

        entries.sort_by_key(|t| t.1);

        let mut total = entries.iter().map(|e| e.2).sum::<u64>();
        let mut removed = 0u64;
        for (p, _, sz) in entries {
            if total <= max_bytes {
                break;
            }
            let _ = std::fs::remove_file(p);
            total = total.saturating_sub(sz);
            removed = removed.saturating_add(1);
        }
        removed
    }

    pub fn thumbnail_to_cache(
        &self,
        mxc_uri: String,
        width: u32,
        height: u32,
        use_crop: bool,
    ) -> Result<String, FfiError> {
        let dir = cache_dir(&self.store_dir);
        ensure_dir(&dir);

        let mxc: OwnedMxcUri = mxc_uri.into();

        let settings = if use_crop {
            MediaThumbnailSettings::with_method(ThumbnailMethod::Crop, width.into(), height.into())
        } else {
            MediaThumbnailSettings::new(width.into(), height.into())
        };

        let req = MediaRequestParameters {
            source: MediaSource::Plain(mxc.clone()),
            format: MediaFormat::Thumbnail(settings),
        };

        let safe_name = format!(
            "thumb_{}_{}x{}{}.bin",
            mxc,
            width,
            height,
            if use_crop { "_crop" } else { "_scale" }
        );
        let out = dir.join(safe_name);

        let bytes = RT
            .block_on(async { self.inner.media().get_media_content(&req, false).await })
            .map_err(|e| FfiError::Msg(e.to_string()))?;

        std::fs::write(&out, &bytes)?;
        Ok(out.to_string_lossy().to_string())
    }

    pub fn react(&self, room_id: String, event_id: String, emoji: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = EventId::parse(event_id) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false;
            };
            let Some(item) = timeline.item_by_event_id(&eid).await else {
                return false;
            };
            let item_id: TimelineEventItemId = item.identifier();
            timeline.toggle_reaction(&item_id, &emoji).await.is_ok()
        })
    }

    pub fn reply(&self, room_id: String, in_reply_to: String, body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(reply_to) = EventId::parse(in_reply_to) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false;
            };

            let content = MsgNoRel::text_plain(body);
            timeline.send_reply(content, reply_to.to_owned()).await.is_ok()
        })
    }

    pub fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::room::edit::EditedContent;
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = EventId::parse(target_event_id) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false            };

            let Some(item) = timeline.item_by_event_id(&eid).await else {
                return false;
            };
            let item_id = item.identifier();
            let edited = EditedContent::RoomMessage(MsgNoRel::text_plain(new_body));

            timeline.edit(&item_id, edited).await.is_ok()
        })
    }

    pub fn paginate_backwards(&self, room_id: String, count: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false;
            };
            timeline.paginate_backwards(count).await.unwrap_or(false)
        })
    }

    pub fn paginate_forwards(&self, room_id: String, count: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false;
            };
            timeline.paginate_forwards(count).await.unwrap_or(false)
        })
    }

    pub fn redact(&self, room_id: String, event_id: String, reason: Option<String>) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = EventId::parse(event_id) else {
                return false;
            };
            if let Some(room) = self.inner.get_room(&room_id) {
                room.redact(&eid, reason.as_deref(), None).await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return;
        };
        let obs: Arc<dyn TypingObserver> = Arc::from(observer);

        let h = RT.spawn(async move {
            let stream = client.observe_room_events::<SyncTypingEvent, Room>(&room_id);
            let mut sub = stream.subscribe();

            let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
            let mut last: Vec<String> = Vec::new();

            while let Some((ev, room)) = sub.next().await {
                let mut names = Vec::with_capacity(ev.content.user_ids.len());
                for uid in ev.content.user_ids.iter() {
                    if let Some(n) = cache.get(uid) {
                        names.push(n.clone());
                        continue;
                    }
                    let name = match room.get_member(uid).await {
                        Ok(Some(m)) => m
                            .display_name()
                            .map(|s| s.to_string())
                            .unwrap_or_else(|| uid.localpart().to_string()),
                        _ => uid.localpart().to_string(),
                    };
                    cache.insert(uid.to_owned(), name.clone());
                    names.push(name);
                }
                names.sort();
                names.dedup();
                if names != last {
                    last = names.clone();
                    obs.on_update(names);
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn send_attachment_bytes(
        &self,
        room_id: String,
        filename: String,
        mime: String,
        bytes: Vec<u8>,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> bool {
        RT.block_on(async {
            use matrix_sdk_ui::timeline::{AttachmentConfig, AttachmentSource};
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(tl) = get_timeline_for(&self.inner, &rid).await else {
                return false;
            };

            let parsed: Mime = mime.parse().unwrap_or(mime::APPLICATION_OCTET_STREAM);
            // No local echo currently; see docs
            let fut = tl.send_attachment(
                AttachmentSource::Data {
                    filename: filename.clone(),
                    bytes: bytes.clone(),
                },
                parsed,
                AttachmentConfig::default(),
            );
            let res = fut.await;
            if let Some(p) = progress {
                let sz = bytes.len() as u64;
                p.on_progress(sz, Some(sz));
            }
            res.is_ok()
        })
    }

    pub fn send_attachment_from_path(
        &self,
        room_id: String,
        path: String,
        mime: String,
        filename: Option<String>,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> bool {
        match read_all(&path) {
            Ok(bytes) => {
                let name = filename
                    .or_else(|| {
                        std::path::Path::new(&path)
                            .file_name()
                            .and_then(|s| s.to_str())
                            .map(|s| s.to_owned())
                    })
                    .unwrap_or_else(|| "file".to_string());
                self.send_attachment_bytes(room_id, name, mime, bytes, progress)
            }
            Err(e) => {
                eprintln!("read file error {path}: {e}");
                false
            }
        }
    }

    pub fn download_to_path(
        &self,
        mxc_uri: String,
        save_path: String,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> Result<DownloadResult, FfiError> {
        RT.block_on(async {
            use std::io::Write;
            let mxc: OwnedMxcUri = mxc_uri.into();

            let req = MediaRequestParameters {
                source: MediaSource::Plain(mxc),
                format: MediaFormat::File,
            };

            let bytes = self
                .inner
                .media()
                .get_media_content(&req, false)
                .await
                .map_err(|e| FfiError::Msg(format!("download: {e}")))?;

            if let Some(p) = progress.as_ref() {
                let sz = bytes.len() as u64;
                p.on_progress(sz, Some(sz));
            }

            let mut f = std::fs::File::create(&save_path)?;
            f.write_all(&bytes)?;
            Ok(DownloadResult {
                path: save_path,
                bytes: bytes.len() as u64,
            })
        })
    }

    pub fn start_supervised_sync(&self, observer: Box<dyn SyncObserver>) {
        let client = self.inner.clone();
        let obs: Arc<dyn SyncObserver> = Arc::from(observer);

        let h = RT.spawn(async move {
            use rand::Rng;

            let mut backoff_secs: u64 = 1;
            let max_backoff: u64 = 60;
            let mut consecutive_failures: u32 = 0;

            obs.on_state(SyncStatus {
                phase: SyncPhase::Idle,
                message: None,
            });

            loop {
                obs.on_state(SyncStatus {
                    phase: SyncPhase::Running,
                    message: None,
                });

                match client.sync_once(SyncSettings::default()).await {
                    Ok(_) => {
                        consecutive_failures = 0;
                        backoff_secs = 1;
                        continue;
                    }
                    Err(e) => {
                        consecutive_failures += 1;
                        let msg = e.to_string();

                        if consecutive_failures >= MAX_CONSECUTIVE_SYNC_FAILURES {
                            obs.on_state(SyncStatus {
                                phase: SyncPhase::Error,
                                message: Some(format!(
                                    "Connection lost after {} attempts. Restart app to retry.",
                                    consecutive_failures
                                )),
                            });
                            break;
                        }

                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Error,
                            message: Some(msg.clone()),
                        });

                        let jitter = rand::thread_rng().gen_range(0.8f64..1.2f64);
                        let wait = (backoff_secs as f64 * jitter).round() as u64;
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::BackingOff,
                            message: Some(format!(
                                "retrying in {}s (attempt {})",
                                wait, consecutive_failures
                            )),
                        });

                        tokio::time::sleep(Duration::from_secs(wait)).await;
                        backoff_secs = (backoff_secs * 2).min(max_backoff);
                    }
                }
            }
        });

        self.guards.lock().unwrap().push(h);
    }

    pub fn recover_with_key(&self, recovery_key: String) -> bool {
        RT.block_on(async {
            let rec = self.inner.encryption().recovery();
            rec.recover(&recovery_key).await.is_ok()
        })
    }

    pub fn list_my_devices(&self) -> Vec<DeviceSummary> {
        RT.block_on(async {
            let Some(me) = self.inner.user_id() else {
                return vec![];
            };
            let trusted = read_trusted(&self.store_dir);
            let trusted_set: std::collections::HashSet<_> = trusted.iter().cloned().collect();
            let Ok(user_devs) = self.inner.encryption().get_user_devices(me).await else {
                return vec![];
            };

            user_devs
                .devices()
                .map(|dev| {
                    let ed25519 = dev.ed25519_key().map(|k| k.to_base64()).unwrap_or_default();
                    let is_own = self
                        .inner
                        .device_id()
                        .map(|my| my == dev.device_id())
                        .unwrap_or(false);
                    DeviceSummary {
                        device_id: dev.device_id().to_string(),
                        display_name: dev.display_name().unwrap_or_default().to_string(),
                        ed25519,
                        is_own,
                        locally_trusted: trusted_set.contains(&dev.device_id().to_string()),
                    }
                })
                .collect()
        })
    }

    pub fn set_local_trust(&self, device_id: String, verified: bool) -> bool {
        let mut trusted = read_trusted(&self.store_dir);
        if verified {
            if !trusted.contains(&device_id) {
                trusted.push(device_id);
            }
        } else {
            trusted.retain(|d| d != &device_id);
        }
        write_trusted(&self.store_dir, &trusted)
    }

    pub fn start_self_sas(
        &self,
        device_id: String,
        observer: Box<dyn VerificationObserver>,
    ) -> String {
        let obs: Arc<dyn VerificationObserver> = Arc::from(observer);
        RT.block_on(async {
            let Some(me) = self.inner.user_id() else {
                obs.on_error("".into(), "No user".into());
                return "".into();
            };
            let Ok(devices) = self.inner.encryption().get_user_devices(me).await else {
                obs.on_error("".into(), "Devices unavailable".into());
                return "".into();
            };

            let mut target = None;
            for d in devices.devices() {
                if d.device_id().as_str() == device_id {
                    target = Some(d);
                    break;
                }
            }
            let Some(dev) = target else {
                obs.on_error("".into(), "Device not found".into());
                return "".into();
            };

            match dev.request_verification().await {
                Ok(req) => {
                    let flow_id = req.flow_id().to_string();
                    self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                    flow_id
                }
                Err(e) => {
                    obs.on_error("".into(), e.to_string());
                    "".into()
                }
            }
        })
    }

    pub fn start_user_sas(
        &self,
        user_id: String,
        observer: Box<dyn VerificationObserver>,
    ) -> String {
        let obs: Arc<dyn VerificationObserver> = Arc::from(observer);
        RT.block_on(async {
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                obs.on_error("".into(), "Bad user id".into());
                return "".into();
            };
            match self.inner.encryption().get_user_identity(&uid).await {
                Ok(Some(identity)) => match identity.request_verification().await {
                    Ok(req) => {
                        let flow_id = req.flow_id().to_string();
                        self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                        flow_id
                    }
                    Err(e) => {
                        obs.on_error("".into(), e.to_string());
                        "".into()
                    }
                },
                _ => {
                    obs.on_error("".into(), "User identity unavailable".into());
                    "".into()
                }
            }
        })
    }

    pub fn accept_verification(
        &self,
        flow_id: String,
        observer: Box<dyn VerificationObserver>,
    ) -> bool {
        let obs: Arc<dyn VerificationObserver> = Arc::from(observer);
        RT.block_on(async {
            // If we already have a SAS flow, accept it.
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                return f.sas.accept().await.is_ok();
            }

            // Otherwise, this should be an incoming request we saw earlier.
            let (user, _device) = match self.inbox.lock().unwrap().get(&flow_id).cloned() {
                Some(x) => x,
                None => return false,
            };

            // Find the request from the verification machine.
            match self
                .inner
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                Some(req) => {
                    // Accept and then transition to SAS
                    if req.accept().await.is_err() {
                        return false;
                    }
                    self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                    true
                }
                None => false,
            }
        })
    }

    pub fn confirm_verification(&self, flow_id: String) -> bool {
        RT.block_on(async {
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                f.sas.confirm().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn cancel_verification(&self, flow_id: String) -> bool {
        RT.block_on(async {
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                f.sas.cancel().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn is_logged_in(&self) -> bool {
        self.inner.user_id().is_some()
    }

    pub fn enqueue_text(&self, room_id: String, body: String, txn_id: Option<String>) -> String {
        let txn = txn_id.unwrap_or_else(|| format!("frair-{}", now_ms()));

        let path = queue_db_path(&self.store_dir);
        let Ok(conn) = open_queue_db(&path) else {
            return txn;
        };

        let _ = conn.execute(
            "INSERT OR IGNORE INTO queued_sends(room_id, body, txn_id, attempts, next_try_ms)
            VALUES (?1, ?2, ?3, 0, 0)",
            (&room_id, &body, &txn),
        );

        // Notify observers
        let _ = self.send_tx.send(SendUpdate {
            room_id: room_id.clone(),
            txn_id: txn.clone(),
            attempts: 0,
            state: SendState::Enqueued,
            event_id: None,
            error: None,
        });

        txn
    }

    pub fn start_send_worker(&self) {
        let client = self.inner.clone();
        let dir = self.store_dir.clone();
        let tx = self.send_tx.clone();

        let h = RT.spawn(async move {
            loop {
                if let Err(e) = drain_once(&client, &dir, &tx).await {
                    eprintln!("send worker error: {e}");
                    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn cancel_txn(&self, txn_id: String) -> bool {
        let path = queue_db_path(&self.store_dir);
        let Ok(conn) = open_queue_db(&path) else {
            return false;
        };
        conn.execute("DELETE FROM queued_sends WHERE txn_id = ?1", [txn_id])
            .ok()
            .map(|n| n > 0)
            .unwrap_or(false)
    }

    pub fn retry_txn_now(&self, txn_id: String) -> bool {
        let path = queue_db_path(&self.store_dir);
        let Ok(conn) = open_queue_db(&path) else {
            return false;
        };
        conn.execute(
            "UPDATE queued_sends SET next_try_ms = 0 WHERE txn_id = ?1",
            [txn_id],
        )
        .ok()
        .map(|n| n > 0)
        .unwrap_or(false)
    }

    pub fn pending_sends(&self) -> u32 {
        let path = queue_db_path(&self.store_dir);
        let Ok(conn) = open_queue_db(&path) else {
            return 0;
        };

        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM queued_sends", [], |r| {
                r.get::<_, i64>(0)
            })
            .unwrap_or(0);

        count.try_into().unwrap_or(0)
    }

    pub fn cache_messages(&self, room_id: String, messages: Vec<MessageEvent>) -> bool {
        let path = self.store_dir.join("message_cache.sqlite3");

        let result = (|| -> Result<(), rusqlite::Error> {
            let mut conn = rusqlite::Connection::open(path)?;
            let tx = conn.transaction()?;

            for msg in messages {
                tx.execute(
                    "INSERT OR REPLACE INTO messages(event_id, room_id, sender, body, timestamp_ms, received_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    (
                        &msg.event_id,
                        &msg.room_id,
                        &msg.sender,
                        &msg.body,
                        msg.timestamp_ms,
                        now_ms(),
                    ),
                )?;
            }
            tx.commit()
        })();

        result.is_ok()
    }

    pub fn get_cached_messages(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        let path = self.store_dir.join("message_cache.sqlite3");

        let result = (|| -> Result<Vec<MessageEvent>, rusqlite::Error> {
            let conn = rusqlite::Connection::open(path)?;
            let mut stmt = conn.prepare(
                "SELECT event_id, room_id, sender, body, timestamp_ms 
                FROM messages WHERE room_id = ?1 
                ORDER BY timestamp_ms DESC LIMIT ?2",
            )?;

            let messages = stmt
                .query_map([&room_id, &limit.to_string()], |row| {
                    Ok(MessageEvent {
                        event_id: row.get(0)?,
                        room_id: row.get(1)?,
                        sender: row.get(2)?,
                        body: row.get(3)?,
                        timestamp_ms: row.get(4)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?;

            Ok(messages)
        })();

        result.unwrap_or_else(|_| vec![])
    }

    pub fn init_caches(&self) -> bool {
        let cache_db = self.store_dir.join("message_cache.sqlite3");

        match rusqlite::Connection::open(&cache_db) {
            Ok(conn) => {
                let result = conn.execute_batch(
                    "PRAGMA journal_mode=WAL;
                     PRAGMA synchronous=NORMAL;
                     CREATE TABLE IF NOT EXISTS messages(
                        event_id TEXT PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp_ms INTEGER NOT NULL,
                        received_at INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_room_messages ON messages(room_id, timestamp_ms DESC, event_id);
                    
                    CREATE TABLE IF NOT EXISTS pagination_tokens(
                        room_id TEXT PRIMARY KEY,
                        prev_batch TEXT,
                        next_batch TEXT,
                        at_start BOOLEAN DEFAULT 0,
                        at_end BOOLEAN DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    );"
                );
                result.is_ok()
            }
            Err(_) => false,
        }
    }

    pub fn save_pagination_state(&self, state: PaginationState) -> bool {
        let path = self.store_dir.join("message_cache.sqlite3");
        let Ok(conn) = rusqlite::Connection::open(path) else {
            return false;
        };

        conn.execute(
            "INSERT OR REPLACE INTO pagination_tokens(room_id, prev_batch, next_batch, at_start, at_end, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            (
                &state.room_id,
                &state.prev_batch,
                &state.next_batch,
                state.at_start,
                state.at_end,
                now_ms(),
            ),
        )
        .is_ok()
    }

    pub fn get_pagination_state(&self, room_id: String) -> Option<PaginationState> {
        let path = self.store_dir.join("message_cache.sqlite3");
        let conn = rusqlite::Connection::open(path).ok()?;

        conn.query_row(
            "SELECT prev_batch, next_batch, at_start, at_end FROM pagination_tokens WHERE room_id = ?1",
            [&room_id],
            |row| {
                Ok(PaginationState {
                    room_id: room_id.clone(),
                    prev_batch: row.get(0)?,
                    next_batch: row.get(1)?,
                    at_start: row.get(2)?,
                    at_end: row.get(3)?,
                })
            },
        )
        .ok()
    }
}

impl Client {
    fn wait_and_start_sas(
        &self,
        flow_id: String,
        req: VerificationRequest,
        obs: Arc<dyn VerificationObserver>,
    ) {
        let verifs = self.verifs.clone();
        let h = RT.spawn(async move {
            use std::time::{Duration, Instant};
            let deadline = Instant::now() + Duration::from_secs(120);
            obs.on_phase(flow_id.clone(), SasPhase::Requested);

            loop {
                match req.start_sas().await {
                    Ok(Some(sas)) => {
                        obs.on_phase(flow_id.clone(), SasPhase::Ready);
                        attach_sas_stream(verifs.clone(), flow_id.clone(), sas, obs.clone()).await;
                        break;
                    }
                    Ok(None) => {
                        if Instant::now() >= deadline {
                            obs.on_error(flow_id.clone(), "SAS not ready".to_string());
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(800)).await;
                    }
                    Err(e) => {
                        if Instant::now() >= deadline {
                            obs.on_error(flow_id.clone(), format!("SAS failed to start: {e}"));
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(800)).await;
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }
}

// ---------- Helpers ----------

fn open_queue_db(path: &PathBuf) -> Result<rusqlite::Connection, rusqlite::Error> {
    let conn = rusqlite::Connection::open(path)?;
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;")?;
    Ok(conn)
}

fn fetch_due_item(store_dir: &PathBuf, now: i64) -> Result<Option<QueuedItem>, String> {
    use rusqlite::OptionalExtension;
    let path = queue_db_path(store_dir);
    let conn = open_queue_db(&path).map_err(|e| e.to_string())?;
    let mut stmt = conn
        .prepare(
            "SELECT id, room_id, body, txn_id, attempts FROM queued_sends 
             WHERE next_try_ms <= ?1 ORDER BY id LIMIT 1",
        )
        .map_err(|e| e.to_string())?;

    let row = stmt
        .query_row([now], |r| {
            Ok(QueuedItem {
                id: r.get::<_, i64>(0)?,
                room_id: r.get::<_, String>(1)?,
                body: r.get::<_, String>(2)?,
                txn_id: r.get::<_, String>(3)?,
                attempts: r.get::<_, i64>(4)?,
            })
        })
        .optional()
        .map_err(|e| e.to_string())?;

    Ok(row)
}

fn delete_item(store_dir: &PathBuf, id: i64) -> Result<(), String> {
    let path = queue_db_path(store_dir);
    let conn = open_queue_db(&path).map_err(|e| e.to_string())?;
    conn.execute("DELETE FROM queued_sends WHERE id = ?1", [id])
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn backoff_item(store_dir: &PathBuf, id: i64, attempts: i64, now: i64) -> Result<(), String> {
    let backoff = (BACKOFF_BASE_MS
        .saturating_mul(1u64.saturating_mul(attempts.min(10).try_into().unwrap())))
    .min(BACKOFF_MAX_MS) as i64;
    let next = now.saturating_add(backoff);

    let path = queue_db_path(store_dir);
    let conn = open_queue_db(&path).map_err(|e| e.to_string())?;
    conn.execute(
        "UPDATE queued_sends SET attempts = attempts + 1, next_try_ms = ?1 WHERE id = ?2",
        (next, id),
    )
    .map_err(|e| e.to_string())?;
    Ok(())
}

async fn drain_once(
    client: &SdkClient,
    store_dir: &PathBuf,
    tx: &tokio::sync::mpsc::UnboundedSender<SendUpdate>,
) -> Result<(), String> {
    use matrix_sdk::ruma::OwnedTransactionId;
    use std::time::Duration;

    let now = now_unix_ms();
    let due = fetch_due_item(store_dir, now)?;
    let Some(item) = due else {
        tokio::time::sleep(Duration::from_millis(500)).await;
        return Ok(());
    };

    let rid = OwnedRoomId::try_from(item.room_id.clone()).map_err(|_| "bad room id".to_string())?;
    let Some(room) = client.get_room(&rid) else {
        let _ = delete_item(store_dir, item.id);
        let _ = tx.send(SendUpdate {
            room_id: rid.to_string(),
            txn_id: item.txn_id.clone(),
            attempts: item.attempts as u32,
            state: SendState::Failed,
            event_id: None,
            error: Some("room not found".to_string()),
        });
        return Ok(());
    };

    let _ = tx.send(SendUpdate {
        room_id: rid.to_string(),
        txn_id: item.txn_id.clone(),
        attempts: item.attempts as u32,
        state: SendState::Sending,
        event_id: None,
        error: None,
    });

    let txn_id: OwnedTransactionId = item.txn_id.clone().into();

    let content = matrix_sdk::ruma::events::room::message::RoomMessageEventContent::text_plain(
        item.body.clone(),
    );

    match room.send(content).with_transaction_id(txn_id).await {
        Ok(resp) => {
            let _ = delete_item(store_dir, item.id);

            let _ = tx.send(SendUpdate {
                room_id: rid.to_string(),
                txn_id: item.txn_id.clone(),
                attempts: item.attempts as u32,
                state: SendState::Sent,
                event_id: Some(resp.event_id.to_string()),
                error: None,
            });
        }
        Err(err) => {
            if item.attempts + 1 >= SEND_MAX_ATTEMPTS {
                let _ = delete_item(store_dir, item.id);
                let _ = tx.send(SendUpdate {
                    room_id: rid.to_string(),
                    txn_id: item.txn_id.clone(),
                    attempts: (item.attempts + 1) as u32,
                    state: SendState::Failed,
                    event_id: None,
                    error: Some(err.to_string()),
                });
            } else {
                let _ = backoff_item(store_dir, item.id, item.attempts + 1, now);
                let _ = tx.send(SendUpdate {
                    room_id: rid.to_string(),
                    txn_id: item.txn_id.clone(),
                    attempts: (item.attempts + 1) as u32,
                    state: SendState::Retrying,
                    event_id: None,
                    error: Some(err.to_string()),
                });
                tokio::time::sleep(Duration::from_millis(400)).await;
            }
        }
    }

    Ok(())
}

async fn get_timeline_for(client: &SdkClient, room_id: &OwnedRoomId) -> Option<Timeline> {
    let room = client.get_room(room_id)?;
    room.timeline().await.ok()
}

fn map_event(ev: &EventTimelineItem, room_id: &str) -> Option<MessageEvent> {
    let msg = ev.content().as_message()?;
    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev
        .event_id()
        .map(|e| e.to_string())
        .unwrap_or_else(|| format!("local-{ts}"));

    Some(MessageEvent {
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        body: render_message_text(msg),
        timestamp_ms: ts,
    })
}

fn reset_store_dir(dir: &PathBuf) {
    let _ = std::fs::remove_dir_all(dir);
    let _ = std::fs::create_dir_all(dir);
}

async fn attach_sas_stream(
    verifs: VerifMap,
    flow_id: String,
    sas: SasVerification,
    obs: Arc<dyn VerificationObserver>,
) {
    let other_user = sas.other_user_id().to_owned();
    let other_device = sas.other_device().device_id().to_owned();

    verifs.lock().unwrap().insert(
        flow_id.clone(),
        VerifFlow {
            sas: sas.clone(),
            other_user: other_user.clone(),
            other_device: other_device.clone(),
        },
    );

    let mut stream = sas.changes();
    while let Some(state) = stream.next().await {
        match state {
            SdkSasState::KeysExchanged { emojis, .. } => {
                if let Some(short) = emojis {
                    let list: Vec<String> =
                        short.emojis.iter().map(|e| e.symbol.to_string()).collect();
                    obs.on_phase(flow_id.clone(), SasPhase::Emojis);
                    obs.on_emojis(SasEmojis {
                        flow_id: flow_id.clone(),
                        other_user: other_user.to_string(),
                        other_device: other_device.to_string(),
                        emojis: list,
                    });
                }
            }
            SdkSasState::Confirmed => obs.on_phase(flow_id.clone(), SasPhase::Confirmed),
            SdkSasState::Cancelled(_) => {
                obs.on_phase(flow_id.clone(), SasPhase::Cancelled);
                // Clean up after cancellation
                verifs.lock().unwrap().remove(&flow_id);
                break;
            }
            SdkSasState::Done { .. } => {
                obs.on_phase(flow_id.clone(), SasPhase::Done);
                // Clean up after completion
                verifs.lock().unwrap().remove(&flow_id);
                break;
            }
            _ => {}
        }
    }
}

fn queue_db_path(dir: &PathBuf) -> PathBuf {
    dir.join("send_queue.sqlite3")
}

fn now_unix_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let dur = SystemTime::now().duration_since(UNIX_EPOCH).unwrap();
    dur.as_millis() as i64
}

async fn start_v2_fallback_loop(client: SdkClient, obs: Arc<dyn SyncObserver>) {
    use rand::Rng;
    let mut backoff_secs: u64 = 1;
    let max_backoff: u64 = 60;
    let mut consecutive_failures: u32 = 0;

    loop {
        obs.on_state(SyncStatus {
            phase: SyncPhase::Running,
            message: None,
        });

        match client.sync_once(SyncSettings::default()).await {
            Ok(_) => {
                consecutive_failures = 0;
                backoff_secs = 1;
            }
            Err(e) => {
                consecutive_failures += 1;

                if consecutive_failures >= MAX_CONSECUTIVE_SYNC_FAILURES {
                    obs.on_state(SyncStatus {
                        phase: SyncPhase::Error,
                        message: Some(format!(
                            "Connection lost after {} attempts. Restart app to retry.",
                            consecutive_failures
                        )),
                    });
                    break;
                }

                obs.on_state(SyncStatus {
                    phase: SyncPhase::Error,
                    message: Some(e.to_string()),
                });

                let jitter = rand::rng().random_range(0.8f64..1.2f64);
                let wait = (backoff_secs as f64 * jitter).round() as u64;
                obs.on_state(SyncStatus {
                    phase: SyncPhase::BackingOff,
                    message: Some(format!(
                        "retrying in {}s (attempt {})",
                        wait, consecutive_failures
                    )),
                });

                tokio::time::sleep(Duration::from_secs(wait)).await;
                backoff_secs = (backoff_secs * 2).min(max_backoff);
            }
        }
    }
}

fn render_message_text(msg: &matrix_sdk_ui::timeline::Message) -> String {
    let mut s = msg.body().to_owned();
    if msg.is_edited() {
        s.push_str(" \n(edited)");
    }
    s
}
