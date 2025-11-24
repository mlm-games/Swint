use mime::Mime;
use once_cell::sync::Lazy;
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;
use uniffi::{Enum, Record};

use futures_util::{StreamExt, TryStreamExt};
use thiserror::Error;

use matrix_sdk::{
    Client as SdkClient, OwnedServerName, Room, RoomMemberships, SessionTokens,
    authentication::{matrix::MatrixSession, oauth::OAuthError},
    config::SyncSettings,
    media::{MediaFormat, MediaRequestParameters, MediaRetentionPolicy, MediaThumbnailSettings},
    ruma::{
        OwnedMxcUri, OwnedRoomAliasId, OwnedRoomOrAliasId,
        api::client::{
            directory::get_public_rooms_filtered,
            push::{Pusher, PusherIds, PusherInit, PusherKind},
        },
        directory::Filter,
        events::room::MediaSource,
        push::HttpPusherData,
    },
};
use matrix_sdk::{
    encryption::BackupDownloadStrategy,
    ruma::{
        DeviceId, EventId, OwnedDeviceId, OwnedRoomId, OwnedUserId, UserId,
        api::client::{
            media::get_content_thumbnail::v3::Method as ThumbnailMethod,
            receipt::create_receipt::v3::ReceiptType,
        },
        events::call::invite::OriginalSyncCallInviteEvent,
        events::receipt::SyncReceiptEvent,
        events::typing::SyncTypingEvent,
    },
};
use matrix_sdk::{
    encryption::EncryptionSettings,
    ruma::{
        self,
        events::{
            key::verification::request::ToDeviceKeyVerificationRequestEvent,
            room::message::{MessageType, SyncRoomMessageEvent},
        },
        owned_device_id,
    },
};
use matrix_sdk::{
    encryption::verification::{SasState as SdkSasState, SasVerification, VerificationRequest},
    ruma::events::receipt::ReceiptThread,
};
use matrix_sdk_ui::{
    encryption_sync_service::{EncryptionSyncService, WithLocking},
    eyeball_im::{self, VectorDiff},
    notification_client::{
        NotificationClient, NotificationEvent, NotificationProcessSetup, NotificationStatus,
    },
    room_list_service::{RoomList, filters},
    sync_service::{State, SyncService},
    timeline::{
        EventSendState, EventTimelineItem, MsgLikeContent, MsgLikeKind, RoomExt as _, Timeline,
        TimelineEventItemId, TimelineItem, TimelineItemContent,
    },
};

use std::panic::AssertUnwindSafe;

// UniFFI macro-first setup
uniffi::setup_scaffolding!();

// Types exposed to Kotlin
#[derive(Clone, Record)]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Copy, Enum)]
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

#[derive(Clone, Record)]
pub struct MessageEvent {
    pub item_id: String,
    pub event_id: String,
    pub room_id: String,
    pub sender: String,
    pub body: String,
    pub timestamp_ms: u64,
    pub send_state: Option<SendState>,
    pub txn_id: Option<String>,
    pub reply_to_event_id: Option<String>,
    pub reply_to_sender: Option<String>,
    pub reply_to_body: Option<String>,
    pub attachment: Option<AttachmentInfo>,
}

#[derive(Clone, Enum)]
pub enum AttachmentKind {
    Image,
    Video,
    File,
}

#[derive(Clone, Record)]
pub struct AttachmentInfo {
    pub kind: AttachmentKind,
    pub mxc_uri: String,
    pub mime: Option<String>,
    pub size_bytes: Option<u64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u64>,
    /// Provided when available, else UI uses `mxc_uri` to request a thumbnail.
    pub thumbnail_mxc_uri: Option<String>,
}

#[derive(Clone, Record)]
pub struct DeviceSummary {
    pub device_id: String,
    pub display_name: String,
    pub ed25519: String,
    pub is_own: bool,
    pub locally_trusted: bool,
}

#[derive(Clone, Enum)]
pub enum SyncPhase {
    Idle,
    Running,
    BackingOff,
    Error,
}

#[derive(Clone, Record)]
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
pub trait ReceiptsObserver: Send + Sync {
    fn on_changed(&self);
}

#[derive(Clone, Record)]
pub struct CallInvite {
    pub room_id: String,
    pub sender: String,
    pub call_id: String,
    pub is_video: bool,
    pub ts_ms: u64,
}

#[uniffi::export(callback_interface)]
pub trait CallObserver: Send + Sync {
    fn on_invite(&self, invite: CallInvite); // Optional future: on_hangup, on_answer…
}

#[uniffi::export(callback_interface)]
pub trait ProgressObserver: Send + Sync {
    fn on_progress(&self, sent: u64, total: Option<u64>);
}

#[derive(Clone, Record)]
pub struct DownloadResult {
    pub path: String,
    pub bytes: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct RenderedNotification {
    pub room_id: String,
    pub event_id: String,
    pub room_name: String,
    pub sender: String,
    pub body: String,
    pub is_noisy: bool,
    pub has_mention: bool,
}

#[derive(Clone, uniffi::Record)]
pub struct UnreadStats {
    pub messages: u64,
    pub notifications: u64,
    pub mentions: u64,
}

enum RoomListCmd {
    SetUnreadOnly(bool),
}

#[derive(uniffi::Record, Clone)]
pub struct OwnReceipt {
    pub event_id: Option<String>,
    pub ts_ms: Option<u64>,
}

#[derive(Clone, Enum)]
pub enum SasPhase {
    Requested,
    Ready,
    Emojis,
    Confirmed,
    Cancelled,
    Failed,
    Done,
}

#[derive(Clone, Record)]
pub struct SasEmojis {
    pub flow_id: String,
    pub other_user: String,
    pub other_device: String,
    pub emojis: Vec<String>,
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
pub struct RoomListEntry {
    pub room_id: String,
    pub name: String,
    pub unread: u64,
    pub last_ts: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct DirectoryUser {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
}

#[derive(Clone, uniffi::Record)]
pub struct PublicRoom {
    pub room_id: String,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub alias: Option<String>,
    pub avatar_url: Option<String>,
    pub member_count: u64,
    pub world_readable: bool,
    pub guest_can_join: bool,
}

#[derive(Clone, uniffi::Record)]
pub struct PublicRoomsPage {
    pub rooms: Vec<PublicRoom>,
    pub next_batch: Option<String>,
    pub prev_batch: Option<String>,
}

#[uniffi::export(callback_interface)]
pub trait RoomListObserver: Send + Sync {
    fn on_reset(&self, items: Vec<RoomListEntry>);
    fn on_update(&self, item: RoomListEntry);
}

fn cache_dir(dir: &PathBuf) -> PathBuf {
    dir.join("media_cache")
}

fn ensure_dir(d: &PathBuf) {
    let _ = std::fs::create_dir_all(d);
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
    send_observers: Arc<Mutex<HashMap<u64, Arc<dyn SendObserver>>>>,
    send_obs_counter: AtomicU64,
    send_tx: tokio::sync::mpsc::UnboundedSender<SendUpdate>,
    inbox: Arc<Mutex<HashMap<String, (OwnedUserId, OwnedDeviceId)>>>,
    sync_service: Arc<Mutex<Option<Arc<SyncService>>>>,
    subs_counter: AtomicU64,
    timeline_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    typing_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    connection_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    inbox_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    receipts_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    room_list_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
    room_list_cmds: Mutex<HashMap<u64, tokio::sync::mpsc::UnboundedSender<RoomListCmd>>>,
    send_handles_by_txn: Mutex<HashMap<String, matrix_sdk::send_queue::SendHandle>>,
    call_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
}

#[derive(Clone, Enum)]
pub enum SendState {
    Enqueued,
    Sending,
    Sent,
    Retrying,
    Failed,
}

#[derive(Clone, Record)]
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
    fn on_remove(&self, item_id: String);
    fn on_clear(&self);
    fn on_reset(&self, events: Vec<MessageEvent>);
}

#[uniffi::export]
impl Client {
    #[uniffi::constructor]
    pub fn new(homeserver_url: String, store_dir: String) -> Self {
        let store_dir_path = std::path::PathBuf::from(&store_dir);
        let _ = std::fs::create_dir_all(&store_dir_path);

        let inner = RT.block_on(async {
            SdkClient::builder()
                .homeserver_url(&homeserver_url)
                .sqlite_store(&store_dir_path, None)
                .with_encryption_settings(EncryptionSettings {
                    auto_enable_cross_signing: true,
                    auto_enable_backups: true,
                    backup_download_strategy: BackupDownloadStrategy::OneShot,
                    ..Default::default()
                })
                .build()
                .await
                .expect("client")
        });

        let (send_tx, mut send_rx) = tokio::sync::mpsc::unbounded_channel::<SendUpdate>();
        let this = Self {
            inner,
            store_dir: store_dir_path,
            guards: Mutex::new(vec![]),
            verifs: Arc::new(Mutex::new(HashMap::new())),
            send_observers: Arc::new(Mutex::new(HashMap::new())),
            send_obs_counter: AtomicU64::new(0),
            send_tx,
            inbox: Arc::new(Mutex::new(HashMap::new())),
            sync_service: Arc::new(Mutex::new(None)),
            subs_counter: AtomicU64::new(0),
            timeline_subs: Mutex::new(HashMap::new()),
            typing_subs: Mutex::new(HashMap::new()),
            connection_subs: Mutex::new(HashMap::new()),
            inbox_subs: Mutex::new(HashMap::new()),
            receipts_subs: Mutex::new(HashMap::new()),
            room_list_subs: Mutex::new(HashMap::new()),
            room_list_cmds: Mutex::new(HashMap::new()),
            send_handles_by_txn: Mutex::new(HashMap::new()),
            call_subs: Mutex::new(HashMap::new()),
        };

        {
            let client = this.inner.clone();
            let svc_slot = this.sync_service.clone();
            let h = RT.spawn(async move {
                // Wait until we have a session
                loop {
                    if client.user_id().is_some() {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(250)).await;
                }
                let service = SyncService::builder(client.clone())
                    .build()
                    .await
                    .expect("SyncService");
                let mut g = svc_slot.lock().unwrap();
                g.replace(Arc::new(service));
            });
            this.guards.lock().unwrap().push(h);
        }

        {
            let observers = this.send_observers.clone();
            let h = RT.spawn(async move {
                while let Some(upd) = send_rx.recv().await {
                    let list: Vec<Arc<dyn SendObserver>> = {
                        let guard = observers.lock().expect("send_observers");
                        guard.values().cloned().collect()
                    };
                    for obs in list {
                        let upd_clone = upd.clone();
                        let _ = std::panic::catch_unwind(AssertUnwindSafe(move || {
                            obs.on_update(upd_clone)
                        }));
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        RT.block_on(async {
            match this.inner.whoami().await {
                Ok(_) => {
                    let _ = this.inner.sync_once(SyncSettings::default()).await;
                    if this.sync_service.lock().unwrap().is_none() {
                        if let Ok(service) = SyncService::builder(this.inner.clone()).build().await
                        {
                            this.sync_service.lock().unwrap().replace(service.into());
                        }
                    }
                }
                Err(_) => {
                    let path = session_file(&this.store_dir);
                    if let Ok(txt) = tokio::fs::read_to_string(&path).await {
                        if let Ok(info) = serde_json::from_str::<SessionInfo>(&txt) {
                            if let Ok(user_id) = info.user_id.parse::<OwnedUserId>() {
                                let session = MatrixSession {
                                    meta: matrix_sdk::SessionMeta {
                                        user_id,
                                        device_id: info.device_id.clone().into(),
                                    },
                                    tokens: SessionTokens {
                                        access_token: info.access_token.clone(),
                                        refresh_token: info.refresh_token.clone(),
                                    },
                                };
                                if this.inner.restore_session(session).await.is_ok() {
                                    let _ = this.inner.sync_once(SyncSettings::default()).await;
                                    if this.sync_service.lock().unwrap().is_none() {
                                        if let Ok(service) =
                                            SyncService::builder(this.inner.clone()).build().await
                                        {
                                            this.sync_service
                                                .lock()
                                                .unwrap()
                                                .replace(service.into());
                                        }
                                    }
                                } else {
                                    let _ = tokio::fs::remove_file(&path).await;
                                    reset_store_dir(&this.store_dir);
                                }
                            }
                        }
                    }
                }
            }
        });

        {
            let inner = this.inner.clone();
            let store = this.store_dir.clone();
            let h = RT.spawn(async move {
                let mut session_rx = inner.subscribe_to_session_changes();
                while let Ok(update) = session_rx.recv().await {
                    if let matrix_sdk::SessionChange::TokensRefreshed = update {
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
                                tokio::fs::write(path, serde_json::to_string(&info).unwrap()).await;
                        }
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        {
            let client = this.inner.clone();
            let h = RT.spawn(async move {
                if let Some(mut stream) = client.encryption().room_keys_received_stream().await {
                    while let Some(batch) = stream.next().await {
                        let Ok(infos) = batch else { continue };
                        use std::collections::HashMap;
                        let mut by_room: HashMap<OwnedRoomId, Vec<String>> = HashMap::new();
                        for info in infos {
                            by_room
                                .entry(info.room_id.clone())
                                .or_default()
                                .push(info.session_id.clone());
                        }
                        for (rid, sessions) in by_room {
                            if let Some(room) = client.get_room(&rid) {
                                if let Ok(tl) = room.timeline().await {
                                    tl.retry_decryption(sessions).await;
                                }
                            }
                        }
                    }
                }
            });
            this.guards.lock().unwrap().push(h);
        }

        this
    }

    pub fn whoami(&self) -> Option<String> {
        self.inner.user_id().map(|u| u.to_string())
    }

    pub fn login(
        &self,
        username: String,
        password: String,
        device_display_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let mut req = self
                .inner
                .matrix_auth()
                .login_username(username.as_str(), &password);
            if let Some(name) = device_display_name.as_ref() {
                req = req.initial_device_display_name(name);
            }

            let res = req.send().await.map_err(|e| FfiError::Msg(e.to_string()))?;

            let info = SessionInfo {
                user_id: res.user_id.to_string(),
                device_id: res.device_id.to_string(),
                access_token: res.access_token.clone(),
                refresh_token: res.refresh_token.clone(),
                homeserver: self.inner.homeserver().to_string(),
            };

            tokio::fs::create_dir_all(&self.store_dir).await?;
            tokio::fs::write(
                session_file(&self.store_dir),
                serde_json::to_string(&info).unwrap(),
            )
            .await?;

            // Warm caches once; ignore errors deliberately (don’t block login success)
            let _ = self.inner.sync_once(SyncSettings::default()).await;

            Ok(())
        })
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

    pub fn enter_foreground(&self) {
        if let Some(svc) = self.sync_service.lock().unwrap().as_ref().cloned() {
            let _ = RT.block_on(async { svc.start().await });
        }
    }

    /// Send the app to background: stop Sliding Sync supervision. (stub)
    pub fn enter_background(&self) {
        if let Some(svc) = self.sync_service.lock().unwrap().as_ref().cloned() {
            let _ = RT.block_on(async { svc.stop().await });
        }
    }

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
    ) -> u64 {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TimelineDiffObserver> = Arc::from(observer);

        let id = self.next_sub_id();
        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&room_id) else {
                return;
            };
            let Ok(tl) = room.timeline().await else {
                return;
            };

            // Wrap Timeline in Arc immediately after obtaining it
            let tl = Arc::new(tl);

            let (items, mut stream) = tl.subscribe().await;

            // Initial snapshot
            let initial: Vec<_> = items
                .iter()
                .filter_map(|it| {
                    let uid = it.unique_id().0.to_string();
                    it.as_event()
                        .and_then(|ei| map_event_with_uid(ei, room_id.as_str(), &uid))
                })
                .collect();
            obs.on_reset(initial);

            for it in items.iter() {
                if let Some(ev) = it.as_event() {
                    if let Some(eid) = missing_reply_event_id(ev) {
                        // Now clone the Arc (simpler syntax)
                        let tlc = tl.clone();
                        RT.spawn(async move {
                            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                        });
                    }
                }
            }

            let mut items = items.clone();

            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    match diff {
                        VectorDiff::Append { values } => {
                            for value in values {
                                items.push_back(value.clone());
                                let uid = value.unique_id().0.to_string();
                                if let Some(ei) = value.as_event() {
                                    if let Some(eid) = missing_reply_event_id(ei) {
                                        let tlc = tl.clone();
                                        RT.spawn(async move {
                                            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                                        });
                                    }
                                    if let Some(ev) = map_event_with_uid(ei, room_id.as_str(), &uid)
                                    {
                                        obs.on_insert(ev);
                                    }
                                }
                            }
                        }
                        VectorDiff::PushBack { value } => {
                            items.push_back(value.clone());
                            let uid = value.unique_id().0.to_string();
                            if let Some(ei) = value.as_event() {
                                if let Some(eid) = missing_reply_event_id(ei) {
                                    let tlc = tl.clone();
                                    RT.spawn(async move {
                                        let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                                    });
                                }
                                if let Some(ev) = map_event_with_uid(ei, room_id.as_str(), &uid) {
                                    obs.on_insert(ev);
                                }
                            }
                        }
                        VectorDiff::PushFront { value } => {
                            items.push_front(value.clone());
                            let uid = value.unique_id().0.to_string();
                            if let Some(ei) = value.as_event() {
                                if let Some(eid) = missing_reply_event_id(ei) {
                                    let tlc = tl.clone();
                                    RT.spawn(async move {
                                        let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                                    });
                                }
                                if let Some(ev) = map_event_with_uid(ei, room_id.as_str(), &uid) {
                                    obs.on_insert(ev);
                                }
                            }
                        }
                        VectorDiff::PopFront => {
                            if let Some(item) = items.pop_front() {
                                obs.on_remove(item.unique_id().0.to_string());
                            }
                        }
                        VectorDiff::PopBack => {
                            if let Some(item) = items.pop_back() {
                                obs.on_remove(item.unique_id().0.to_string());
                            }
                        }
                        VectorDiff::Insert { index, value } => {
                            items.insert(index, value.clone());
                            let uid = value.unique_id().0.to_string();
                            if let Some(ei) = value.as_event() {
                                if let Some(eid) = missing_reply_event_id(ei) {
                                    let tlc = tl.clone();
                                    RT.spawn(async move {
                                        let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                                    });
                                }
                                if let Some(ev) = map_event_with_uid(ei, room_id.as_str(), &uid) {
                                    obs.on_insert(ev);
                                }
                            }
                        }
                        VectorDiff::Set { index, value } => {
                            if index < items.len() {
                                items[index] = value.clone();
                            }
                            let uid = value.unique_id().0.to_string();
                            if let Some(ei) = value.as_event() {
                                if let Some(eid) = missing_reply_event_id(ei) {
                                    let tlc = tl.clone();
                                    RT.spawn(async move {
                                        let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                                    });
                                }
                                if let Some(ev) = map_event_with_uid(ei, room_id.as_str(), &uid) {
                                    obs.on_update(ev);
                                }
                            }
                        }
                        VectorDiff::Remove { index } => {
                            if index < items.len() {
                                let uid = items[index].unique_id().0.to_string();
                                items.remove(index);
                                obs.on_remove(uid);
                            }
                        }
                        VectorDiff::Truncate { length } => {
                            while items.len() > length {
                                if let Some(item) = items.pop_back() {
                                    obs.on_remove(item.unique_id().0.to_string());
                                }
                            }
                        }
                        VectorDiff::Clear => {
                            items.clear();
                            obs.on_clear();
                        }
                        VectorDiff::Reset { values } => {
                            items = values.clone();
                            let events = values
                                .iter()
                                .filter_map(|it| {
                                    let uid = it.unique_id().0.to_string();
                                    it.as_event().and_then(|ei| {
                                        if let Some(eid) = missing_reply_event_id(ei) {
                                            let tlc = tl.clone();
                                            RT.spawn(async move {
                                                let _ =
                                                    tlc.fetch_details_for_event(eid.as_ref()).await;
                                            });
                                        }
                                        map_event_with_uid(ei, room_id.as_str(), &uid)
                                    })
                                })
                                .collect::<Vec<_>>();
                            obs.on_reset(events);
                        }
                    }
                }
            }
        });

        self.timeline_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_room_timeline(&self, sub_id: u64) -> bool {
        if let Some(h) = self.timeline_subs.lock().unwrap().remove(&sub_id) {
            h.abort();
            true
        } else {
            false
        }
    }

    pub fn start_verification_inbox(&self, observer: Box<dyn VerificationInboxObserver>) -> u64 {
        let client = self.inner.clone();
        let obs: Arc<dyn VerificationInboxObserver> = Arc::from(observer);
        let inbox = self.inbox.clone();

        let id = self.next_sub_id();
        let h = RT.spawn(async move {
            let td_handler = client.observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
            let mut td_sub = td_handler.subscribe();

            // In‑room requests arrive as a room message with msgtype = m.key.verification.request
            let ir_handler = client.observe_events::<SyncRoomMessageEvent, Room>();
            let mut ir_sub = ir_handler.subscribe();

            loop {
                tokio::select! {
                    maybe = td_sub.next() => {
                        if let Some((ev, ())) = maybe {
                            let flow_id    = ev.content.transaction_id.to_string(); // to‑device uses txn id
                            let from_user  = ev.sender.to_string();
                            let from_device= ev.content.from_device.to_string();

                            inbox.lock().unwrap().insert(
                                flow_id.clone(),
                                (ev.sender, ev.content.from_device.clone()),
                            );

                            let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                                obs.on_request(flow_id, from_user, from_device);
                            }));
                        } else { break; }
                    }

                    // in‑room
                    maybe = ir_sub.next() => {
                        if let Some((ev, _room)) = maybe {
                            if let SyncRoomMessageEvent::Original(o) = ev {
                                if let MessageType::VerificationRequest(_c) = &o.content.msgtype {
                                    // in‑room flow_id is the event_id
                                    let flow_id   = o.event_id.to_string();
                                    let from_user = o.sender.to_string();

                                    // We only need the user later; device can be a placeholder
                                    inbox.lock().unwrap().insert(
                                        flow_id.clone(),
                                        (o.sender.clone(), owned_device_id!("inroom")),
                                    );

                                    let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                                        obs.on_request(flow_id, from_user, String::new());
                                    }));
                                }
                            }
                        } else { break; }
                    }
                }
            }
        });

        self.inbox_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_verification_inbox(&self, sub_id: u64) -> bool {
        if let Some(h) = self.inbox_subs.lock().unwrap().remove(&sub_id) {
            h.abort();
            true
        } else {
            false
        }
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

    pub fn monitor_connection(&self, observer: Box<dyn ConnectionObserver>) -> u64 {
        let client = self.inner.clone();
        let obs: Arc<dyn ConnectionObserver> = Arc::from(observer);

        let id = self.next_sub_id();
        let h = RT.spawn(async move {
            let mut last_state = ConnectionState::Disconnected;
            let mut session_rx = client.subscribe_to_session_changes();

            loop {
                tokio::select! {
                    Ok(change) = session_rx.recv() => {
                        let current = match change {
                            matrix_sdk::SessionChange::TokensRefreshed => ConnectionState::Connected,
                            matrix_sdk::SessionChange::UnknownToken { .. } => ConnectionState::Reconnecting { attempt: 1, next_retry_secs: 5 },
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
                        let is_active = client.is_active();
                        let current = if is_active { ConnectionState::Connected } else { ConnectionState::Disconnected };
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

        self.connection_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_connection(&self, sub_id: u64) -> bool {
        if let Some(h) = self.connection_subs.lock().unwrap().remove(&sub_id) {
            h.abort();
            true
        } else {
            false
        }
    }

    pub fn observe_sends(&self, observer: Box<dyn SendObserver>) -> u64 {
        let id = self
            .send_obs_counter
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1);
        self.send_observers
            .lock()
            .unwrap()
            .insert(id, Arc::from(observer));
        id
    }

    pub fn unobserve_sends(&self, id: u64) -> bool {
        self.send_observers.lock().unwrap().remove(&id).is_some()
    }

    pub fn send_message(&self, room_id: String, body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContent as Msg;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else {
                return false;
            };

            match timeline.send(Msg::text_plain(body.clone()).into()).await {
                Ok(handle) => {
                    if let Some(latest) = timeline.latest_event().await {
                        if latest.event_id().is_none() {
                            if let Some(txn) = latest.transaction_id() {
                                self.send_handles_by_txn
                                    .lock()
                                    .unwrap()
                                    .insert(txn.to_string(), handle.clone());
                            }
                        }
                    }
                    true
                }
                Err(_) => false,
            }
        })
    }

    pub fn shutdown(&self) {
        if let Some(svc) = self.sync_service.lock().unwrap().as_ref().cloned() {
            let _ = RT.block_on(async { svc.stop().await });
        }
        for (_, h) in self.timeline_subs.lock().unwrap().drain() {
            h.abort();
        }
        for (_, h) in self.typing_subs.lock().unwrap().drain() {
            h.abort();
        }
        for (_, h) in self.connection_subs.lock().unwrap().drain() {
            h.abort();
        }
        for (_, h) in self.inbox_subs.lock().unwrap().drain() {
            h.abort();
        }
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        for (_, h) in self.receipts_subs.lock().unwrap().drain() {
            h.abort();
        }
        for (_, h) in self.room_list_subs.lock().unwrap().drain() {
            h.abort();
        }
        for (_, h) in self.call_subs.lock().unwrap().drain() {
            h.abort();
        }
    }

    pub fn logout(&self) -> bool {
        self.shutdown();
        let _ = RT.block_on(async { self.inner.logout().await });
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

    /// Configure the SDK's media retention policy and apply it immediately.
    /// Any `None` will keep the SDK default for that parameter.
    pub fn set_media_retention_policy(
        &self,
        max_cache_size_bytes: Option<u64>,
        max_file_size_bytes: Option<u64>,
        last_access_expiry_secs: Option<u64>,
        cleanup_frequency_secs: Option<u64>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            use std::time::Duration;
            let mut policy = MediaRetentionPolicy::new();
            if max_cache_size_bytes.is_some() {
                policy = policy.with_max_cache_size(max_cache_size_bytes);
            }
            if max_file_size_bytes.is_some() {
                policy = policy.with_max_file_size(max_file_size_bytes);
            }
            if last_access_expiry_secs.is_some() {
                policy = policy
                    .with_last_access_expiry(last_access_expiry_secs.map(Duration::from_secs));
            }
            if cleanup_frequency_secs.is_some() {
                policy =
                    policy.with_cleanup_frequency(cleanup_frequency_secs.map(Duration::from_secs));
            }

            self.inner
                .media()
                .set_media_retention_policy(policy)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            // Apply right away.
            self.inner
                .media()
                .clean_up_media_cache()
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Run a cleanup of the SDK's media cache with the current policy.
    pub fn media_cache_clean(&self) -> Result<(), FfiError> {
        RT.block_on(async {
            self.inner
                .media()
                .clean_up_media_cache()
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
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
            .block_on(async { self.inner.media().get_media_content(&req, true).await })
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

    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) -> u64 {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TypingObserver> = Arc::from(observer);
        let id = self.next_sub_id();

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

        self.typing_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_typing(&self, sub_id: u64) -> bool {
        if let Some(h) = self.typing_subs.lock().unwrap().remove(&sub_id) {
            h.abort();
            true
        } else {
            false
        }
    }

    pub fn observe_receipts(&self, room_id: String, observer: Box<dyn ReceiptsObserver>) -> u64 {
        let client = self.inner.clone();
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::from(observer);
        let id = self.next_sub_id();
        let h = RT.spawn(async move {
            let stream = client.observe_room_events::<SyncReceiptEvent, Room>(&rid);
            let mut sub = stream.subscribe();
            while let Some((_ev, _room)) = sub.next().await {
                let _ = std::panic::catch_unwind(AssertUnwindSafe(|| obs.on_changed()));
            }
        });
        self.receipts_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_receipts(&self, sub_id: u64) -> bool {
        if let Some(h) = self.receipts_subs.lock().unwrap().remove(&sub_id) {
            h.abort();
            true
        } else {
            false
        }
    }

    pub fn dm_peer_user_id(&self, room_id: String) -> Option<String> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };
            let Some(me) = self.inner.user_id() else {
                return None;
            };
            if let Ok(members) = room.members(RoomMemberships::ACTIVE).await {
                for m in members {
                    if m.user_id() != me {
                        return Some(m.user_id().to_string());
                    }
                }
            }
            None
        })
    }

    pub fn is_event_read_by(&self, room_id: String, event_id: String, user_id: String) -> bool {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = EventId::parse(&event_id) else {
                return false;
            };
            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                return false;
            };
            let Some(tl) = get_timeline_for(&self.inner, &rid).await else {
                return false;
            };
            let latest_opt = tl.latest_user_read_receipt_timeline_event_id(&uid).await;
            let Some(latest) = latest_opt else {
                return false;
            };
            // Compare positions within current items
            let items = tl.items().await;
            let mut idx_latest = None;
            let mut idx_mine = None;
            for (i, it) in items.iter().enumerate() {
                if let Some(ev) = it.as_event() {
                    if let Some(e) = ev.event_id() {
                        if e == &latest {
                            idx_latest = Some(i);
                        }
                        if e == &eid {
                            idx_mine = Some(i);
                        }
                    }
                }
                if idx_latest.is_some() && idx_mine.is_some() {
                    break;
                }
            }
            matches!((idx_mine, idx_latest), (Some(i_m), Some(i_l)) if i_l >= i_m)
        })
    }

    pub fn start_call_inbox(&self, observer: Box<dyn CallObserver>) -> u64 {
        let client = self.inner.clone();
        let obs: Arc<dyn CallObserver> = Arc::from(observer);
        let id = self.next_sub_id();
        let h = RT.spawn(async move {
            let handler = client.observe_events::<OriginalSyncCallInviteEvent, Room>();
            let mut sub = handler.subscribe();
            while let Some((ev, room)) = sub.next().await {
                let call_id = ev.content.call_id.to_string();
                let is_video = ev.content.offer.sdp.contains("m=video");
                let ts: u64 = ev.origin_server_ts.0.into();
                let invite = CallInvite {
                    room_id: room.room_id().to_string(),
                    sender: ev.sender.to_string(),
                    call_id,
                    is_video,
                    ts_ms: ts,
                };
                let _ = std::panic::catch_unwind(AssertUnwindSafe(|| obs.on_invite(invite)));
            }
        });
        self.call_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn stop_call_inbox(&self, token: u64) -> bool {
        if let Some(h) = self.call_subs.lock().unwrap().remove(&token) {
            h.abort();
            true
        } else {
            false
        }
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
        use matrix_sdk_ui::timeline::{AttachmentConfig, AttachmentSource};
        RT.block_on(async {
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Some(tl) = get_timeline_for(&self.inner, &rid).await else {
                return false;
            };

            // Parse MIME (fallback to application/octet-stream)
            let parsed: Mime = mime.parse().unwrap_or(mime::APPLICATION_OCTET_STREAM);

            // Stream directly from disk (AttachmentSource::File reads size + filename)
            let fut = tl.send_attachment(
                std::path::PathBuf::from(&path),
                parsed,
                AttachmentConfig::default(),
            );
            let res = fut.await;

            if let Some(p) = progress {
                if let Ok(md) = std::fs::metadata(&path) {
                    let sz = md.len();
                    p.on_progress(sz, Some(sz));
                }
            }
            res.is_ok()
        })
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
                .get_media_content(&req, true)
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
        let obs: Arc<dyn SyncObserver> = Arc::from(observer);
        let svc_slot = self.sync_service.clone();
        let h = RT.spawn(async move {
            use futures_util::StreamExt;
            use std::time::Duration;

            obs.on_state(SyncStatus {
                phase: SyncPhase::Idle,
                message: None,
            });

            // Wait until it starts (session may be restoring)
            let svc = loop {
                if let Some(s) = { svc_slot.lock().unwrap().as_ref().cloned() } {
                    break s;
                }
                tokio::time::sleep(Duration::from_millis(200)).await;
            };

            let mut st = svc.state();
            let _ = svc.start().await;

            while let Some(state) = st.next().await {
                match state {
                    State::Idle => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Idle,
                            message: None,
                        });
                    }
                    State::Running => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Running,
                            message: None,
                        });
                    }
                    State::Offline => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::BackingOff,
                            message: Some("Offline".into()),
                        });
                    }
                    State::Terminated => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Error,
                            message: Some("Sync terminated".into()),
                        });
                        let _ = svc.start().await;
                    }
                    State::Error => {
                        obs.on_state(SyncStatus {
                            phase: SyncPhase::Error,
                            message: Some("Sync error".to_string()),
                        });
                        // Restart on error as docs suggested.
                        let _ = svc.start().await;
                    }
                }
            }
        });

        self.guards.lock().unwrap().push(h);
    }

    fn next_sub_id(&self) -> u64 {
        self.subs_counter
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1)
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
        other_user_id: Option<String>,
        observer: Box<dyn VerificationObserver>,
    ) -> bool {
        let obs: Arc<dyn VerificationObserver> = Arc::from(observer);
        RT.block_on(async {
            // Prefer explicit other user; else look up from inbox
            let user_opt = if let Some(uid) = other_user_id {
                uid.parse::<OwnedUserId>().ok()
            } else {
                self.inbox
                    .lock()
                    .unwrap()
                    .get(&flow_id)
                    .map(|p| p.0.clone())
            };
            let Some(user) = user_opt else {
                return false;
            };

            // Already-running SAS?
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                return f.sas.accept().await.is_ok();
            }

            // Pending request?
            if let Some(req) = self
                .inner
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                if req.accept().await.is_err() {
                    return false;
                }
                self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                return true;
            }

            // A verification may already exist; sas() can appear a moment later after START
            if let Some(verification) = self
                .inner
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                if let Some(sas) = verification.clone().sas() {
                    return sas.accept().await.is_ok();
                }
                // Short retry for sas() to materialize
                for _ in 0..5 {
                    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
                    if let Some(sas) = verification.clone().sas() {
                        return sas.accept().await.is_ok();
                    }
                }
            }

            false
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
            // Cancel an active SAS if we have it cached
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                return f.sas.cancel().await.is_ok();
            }
            // Else try to resolve via crypto and cancel there as a best‑effort
            let user = match self
                .inbox
                .lock()
                .unwrap()
                .get(&flow_id)
                .map(|p| p.0.clone())
            {
                Some(u) => u,
                None => match self.inner.user_id() {
                    Some(me) => me.to_owned(),
                    None => return false,
                },
            };
            if let Some(verification) = self
                .inner
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                if let Some(sas) = verification.sas() {
                    return sas.cancel().await.is_ok();
                }
            }
            false
        })
    }

    pub fn cancel_verification_request(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
    ) -> bool {
        RT.block_on(async {
            let user = if let Some(uid) = other_user_id {
                match uid.parse::<OwnedUserId>() {
                    Ok(u) => u,
                    Err(_) => return false,
                }
            } else if let Some((u, _)) = self.inbox.lock().unwrap().get(&flow_id).cloned() {
                u
            } else {
                return false;
            };

            if let Some(req) = self
                .inner
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                return req.cancel().await.is_ok();
            }
            if let Some(verification) = self
                .inner
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                if let Some(sas) = verification.sas() {
                    return sas.cancel().await.is_ok();
                }
            }
            false
        })
    }

    pub fn is_logged_in(&self) -> bool {
        self.inner.user_id().is_some()
    }

    pub fn enqueue_text(&self, room_id: String, body: String, txn_id: Option<String>) -> String {
        let client_txn = txn_id.unwrap_or_else(|| format!("mages-{}", now_ms()));

        let tx = self.send_tx.clone();
        let client = self.inner.clone();
        let mut handles = self.send_handles_by_txn.lock().unwrap().clone();
        let txn_id = client_txn.clone();
        RT.spawn(async move {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContent as Msg;
            // Emit "Sending" (best-effort continuity with previous observer).
            let _ = tx.send(SendUpdate {
                room_id: room_id.clone(),
                txn_id: txn_id.clone(),
                attempts: 0,
                state: SendState::Sending,
                event_id: None,
                error: None,
            });

            let Ok(rid) = OwnedRoomId::try_from(room_id.clone()) else {
                let _ = tx.send(SendUpdate {
                    room_id,
                    txn_id: txn_id,
                    attempts: 0,
                    state: SendState::Failed,
                    event_id: None,
                    error: Some("bad room id".into()),
                });
                return;
            };

            if let Some(timeline) = get_timeline_for(&client, &rid).await {
                match timeline.send(Msg::text_plain(body.clone()).into()).await {
                    Ok(handle) => {
                        // Map protocol txn id (if we can see it now) -> handle, for future precise retry.
                        if let Some(latest) = timeline.latest_event().await {
                            if latest.event_id().is_none() {
                                if let Some(proto_txn) = latest.transaction_id() {
                                    handles.insert(proto_txn.to_string(), handle);
                                }
                            }
                        }
                        let _ = tx.send(SendUpdate {
                            room_id: rid.to_string(),
                            txn_id: txn_id,
                            attempts: 0,
                            state: SendState::Sent,
                            event_id: None,
                            error: None,
                        });
                    }
                    Err(e) => {
                        let _ = tx.send(SendUpdate {
                            room_id: rid.to_string(),
                            txn_id: txn_id,
                            attempts: 0,
                            state: SendState::Failed,
                            event_id: None,
                            error: Some(e.to_string()),
                        });
                    }
                }
            } else {
                let _ = tx.send(SendUpdate {
                    room_id: rid.to_string(),
                    txn_id: txn_id,
                    attempts: 0,
                    state: SendState::Failed,
                    event_id: None,
                    error: Some("room/timeline not found".into()),
                });
            }
        });

        client_txn
    }

    pub fn retry_by_txn(&self, _room_id: String, txn_id: String) -> bool {
        RT.block_on(async {
            if let Some(handle) = self
                .send_handles_by_txn
                .lock()
                .unwrap()
                .get(&txn_id)
                .cloned()
            {
                handle.unwedge().await.is_ok()
            } else {
                false
            }
        })
    }

    pub fn start_send_worker(&self) {
        // No-op: SDK handles sending/retries internally; worker removed.
    }

    pub fn cancel_txn(&self, _txn_id: String) -> bool {
        // Not needed without a custom queue
        false
    }

    pub fn retry_txn_now(&self, _txn_id: String) -> bool {
        // Same.
        false
    }

    pub fn pending_sends(&self) -> u32 {
        // No queue anymore.
        0
    }

    /// Register/Update HTTP pusher for UnifiedPush/Matrix gateway (e.g. ntfy)
    pub fn register_unifiedpush(
        &self,
        app_id: String,
        pushkey: String,
        gateway_url: String,
        device_display_name: String,
        lang: String,
        profile_tag: Option<String>,
    ) -> bool {
        RT.block_on(async {
            let init = PusherInit {
                ids: PusherIds::new(app_id.into(), pushkey.into()),
                kind: PusherKind::Http(HttpPusherData::new(gateway_url.into())),
                app_display_name: "Mages".into(),
                device_display_name,
                profile_tag,
                lang,
            };
            let pusher: Pusher = init.into();
            self.inner.pusher().set(pusher).await.is_ok()
        })
    }

    /// Unregister HTTP pusher by ids
    pub fn unregister_unifiedpush(&self, app_id: String, pushkey: String) -> bool {
        RT.block_on(async {
            let ids = PusherIds::new(app_id.into(), pushkey.into());
            self.inner.pusher().delete(ids).await.is_ok()
        })
    }

    /// One-shot sync used when a push arrives; small timeout and no backoff
    pub fn wake_sync_once(&self, timeout_ms: u32) -> bool {
        RT.block_on(async {
            use std::time::Duration;
            let settings =
                SyncSettings::default().timeout(Duration::from_millis(timeout_ms as u64));
            self.inner.sync_once(settings).await.is_ok()
        })
    }

    pub fn render_notification(
        &self,
        room_id: String,
        event_id: String,
    ) -> Result<Option<RenderedNotification>, FfiError> {
        RT.block_on(async {
            let rid = ruma::OwnedRoomId::try_from(room_id.clone())
                .map_err(|_| FfiError::Msg("bad room_id".into()))?;
            let eid = ruma::OwnedEventId::try_from(event_id.clone())
                .map_err(|_| FfiError::Msg("bad event_id".into()))?;

            // We prefer SingleProcess on Android
            let sync = {
                let g = self.sync_service.lock().unwrap();
                g.as_ref()
                    .cloned()
                    .ok_or_else(|| FfiError::Msg("SyncService not ready".into()))?
            };

            let nc = NotificationClient::new(
                self.inner.clone(),
                NotificationProcessSetup::SingleProcess { sync_service: sync },
            )
            .await
            .map_err(|e| FfiError::Msg(format!("notif client: {e}")))?;

            match nc.get_notification(&rid, &eid).await {
                Ok(NotificationStatus::Event(item)) => {
                    // Title
                    let room_name = item.room_computed_display_name.clone();

                    // Sender + fallback body (for regular messages)
                    let mut sender = item
                        .sender_display_name
                        .clone()
                        .unwrap_or_else(|| item.event.sender().localpart().to_string());
                    let mut body = String::from("New event");
                    if let NotificationEvent::Timeline(ev) = &item.event {
                        if let ruma::events::AnySyncTimelineEvent::MessageLike(
                            ruma::events::AnySyncMessageLikeEvent::RoomMessage(m),
                        ) = ev.as_ref()
                        {
                            if let Some(orig) = m.as_original() {
                                sender = item
                                    .sender_display_name
                                    .clone()
                                    .unwrap_or_else(|| orig.sender.localpart().to_string());
                                // Use plain/fallback text
                                body = orig.content.body().to_string();
                            }
                        }
                    }

                    Ok(Some(RenderedNotification {
                        room_id: rid.to_string(),
                        event_id: eid.to_string(),
                        room_name,
                        sender,
                        body,
                        is_noisy: item.is_noisy.unwrap_or(false),
                        has_mention: item.has_mention.unwrap_or(false),
                    }))
                }
                Ok(NotificationStatus::EventFilteredOut) => Ok(None),
                Ok(NotificationStatus::EventNotFound) => Ok(None),
                Err(e) => Err(FfiError::Msg(format!("notif fetch: {e}"))),
            }
        })
    }

    pub fn room_unread_stats(&self, room_id: String) -> Option<UnreadStats> {
        RT.block_on(async {
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };
            Some(UnreadStats {
                messages: room.num_unread_messages(),
                notifications: room.num_unread_notifications(),
                mentions: room.num_unread_mentions(),
            })
        })
    }

    pub fn own_last_read(&self, room_id: String) -> OwnReceipt {
        RT.block_on(async {
            use matrix_sdk_ui::timeline::Timeline;
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id.clone()) else {
                return OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                };
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                };
            };
            let Ok(tl) = room.timeline().await else {
                return OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                };
            };
            let me = self.inner.user_id().to_owned().unwrap();
            let eid = tl.latest_user_read_receipt_timeline_event_id(me).await;
            // Try to map to a timestamp if the event is currently known to timeline
            let ts = if let Some(ref e) = eid {
                let items = tl.items().await;
                items.iter().find_map(|it| {
                    it.as_event()
                        .and_then(|ev| ev.event_id().map(|id| id == e))
                        .and_then(|eq| if eq { Some(()) } else { None })
                        .and_then(|_| it.as_event().map(|ev| ev.timestamp().0.into()))
                })
            } else {
                None
            };
            OwnReceipt {
                event_id: eid.map(|e| e.to_string()),
                ts_ms: ts,
            }
        })
    }

    pub fn observe_own_receipt(&self, room_id: String, observer: Box<dyn ReceiptsObserver>) -> u64 {
        // Reuse the existing callback interface to notify "changed";
        // when it fires, Kotlin can call own_last_read() to pull details.
        let client = self.inner.clone();
        let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn ReceiptsObserver> = Arc::from(observer);
        let id = self.next_sub_id();

        let h = RT.spawn(async move {
            let stream =
                client.observe_room_events::<SyncReceiptEvent, matrix_sdk::room::Room>(&rid);
            let mut sub = stream.subscribe();
            while let Some((_ev, _room)) = sub.next().await {
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| obs.on_changed()));
            }
        });
        self.receipts_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn mark_fully_read_at(&self, room_id: String, event_id: String) -> bool {
        RT.block_on(async {
            use ReceiptThread;
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = ruma::OwnedEventId::try_from(event_id) else {
                return false;
            };
            if let Some(room) = self.inner.get_room(&rid) {
                room.send_single_receipt(ReceiptType::FullyRead, ReceiptThread::Unthreaded, eid)
                    .await
                    .is_ok()
            } else {
                false
            }
        })
    }

    /// Run a short encryption sync if a permit is available (used on push).
    pub fn encryption_catchup_once(&self) -> bool {
        RT.block_on(async {
            let svc_opt = { self.sync_service.lock().unwrap().as_ref().cloned() };
            let Some(svc) = svc_opt else {
                return false;
            };
            let Some(permit) = svc.try_get_encryption_sync_permit() else {
                return false;
            };
            match EncryptionSyncService::new(self.inner.clone(), None, WithLocking::Yes).await {
                Ok(enc) => enc.run_fixed_iterations(100, permit).await.is_ok(),
                Err(_) => false,
            }
        })
    }

    pub fn observe_room_list(&self, observer: Box<dyn RoomListObserver>) -> u64 {
        use futures_util::StreamExt;
        use std::panic::AssertUnwindSafe;

        let obs: std::sync::Arc<dyn RoomListObserver> = std::sync::Arc::from(observer);
        let svc_slot = self.sync_service.clone();
        let id = self.next_sub_id();

        let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<RoomListCmd>();
        let id_for_map = id;
        self.room_list_cmds
            .lock()
            .unwrap()
            .insert(id_for_map, cmd_tx);

        let h = RT.spawn(async move {
        // Wait for sync service
        let svc = {
            let mut attempts = 0;
            loop {
                if let Some(s) = { svc_slot.lock().unwrap().as_ref().cloned() } {
                    break s;
                }
                attempts += 1;
                if attempts > 100 {
                    eprintln!("observe_room_list: SyncService not available after timeout");
                    return;
                }
                tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            }
        };

        let rls = svc.room_list_service();
        let all = match rls.all_rooms().await {
            Ok(list) => list,
            Err(e) => {
                eprintln!("observe_room_list: failed to get all_rooms: {e}");
                return;
            }
        };

        let (stream, controller) = all.entries_with_dynamic_adapters(50);
        tokio::pin!(stream);

        controller.set_filter(Box::new(
            matrix_sdk_ui::room_list_service::filters::new_filter_non_left(),
        ));

        loop {
            tokio::select! {
                // Apply filter updates.
                Some(cmd) = cmd_rx.recv() => {
                    match cmd {
                        RoomListCmd::SetUnreadOnly(unread_only) => {
                            if unread_only {
                                controller.set_filter(Box::new(filters::new_filter_all(vec![
                                    Box::new(filters::new_filter_non_left()),
                                    Box::new(filters::new_filter_unread()),
                                ])));
                            } else {
                                controller.set_filter(Box::new(filters::new_filter_non_left()));
                            }
                        }
                    }
                }
                // Forward room list diffs.
                Some(diffs) = stream.next() => {
                    for diff in diffs {
                        match diff {
                            VectorDiff::Reset { values } => {
                                let snapshot: Vec<_> = values
                                    .iter()
                                    .map(|room| RoomListEntry {
                                        room_id: room.room_id().to_string(),
                                        name: room
                                            .cached_display_name()
                                            .map(|n| n.to_string())
                                            .unwrap_or_else(|| room.room_id().to_string()),
                                        unread: room.unread_notification_counts().notification_count
                                            as u64,
                                        last_ts: 0,
                                    })
                                    .collect();

                                let obs_clone = obs.clone();
                                let _ = std::panic::catch_unwind(AssertUnwindSafe(move || {
                                    obs_clone.on_reset(snapshot)
                                }));
                            }
                            VectorDiff::Set { value, .. }
                            | VectorDiff::Insert { value, .. }
                            | VectorDiff::PushBack { value }
                            | VectorDiff::PushFront { value } => {
                                let entry = RoomListEntry {
                                    room_id: value.room_id().to_string(),
                                    name: value
                                        .cached_display_name()
                                        .map(|n| n.to_string())
                                        .unwrap_or_else(|| value.room_id().to_string()),
                                    unread: value.unread_notification_counts().notification_count
                                        as u64,
                                    last_ts: 0,
                                };

                                let obs_clone = obs.clone();
                                let _ = std::panic::catch_unwind(AssertUnwindSafe(move || {
                                    obs_clone.on_update(entry)
                                }));
                            }
                            _ => {}
                        }
                    }
                }
                else => break,
            }
        }
    });

        self.room_list_subs.lock().unwrap().insert(id, h);
        id
    }
    pub fn unobserve_room_list(&self, token: u64) -> bool {
        self.room_list_cmds.lock().unwrap().remove(&token);
        if let Some(h) = self.room_list_subs.lock().unwrap().remove(&token) {
            h.abort();
            true
        } else {
            false
        }
    }

    pub fn search_users(
        &self,
        search_term: String,
        limit: u64,
    ) -> Result<Vec<DirectoryUser>, FfiError> {
        RT.block_on(async {
            let resp = self
                .inner
                .search_users(&search_term, limit)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?; // matrix-sdk 0.14's helper
            let out = resp
                .results
                .into_iter()
                .map(|u| DirectoryUser {
                    user_id: u.user_id.to_string(),
                    display_name: u.display_name,
                    avatar_url: u.avatar_url.map(|mxc| mxc.to_string()),
                })
                .collect();
            Ok(out)
        })
    }

    pub fn public_rooms(
        &self,
        server: Option<String>,
        search: Option<String>,
        limit: u32,
        since: Option<String>,
    ) -> Result<PublicRoomsPage, FfiError> {
        RT.block_on(async {
            // Parse server name if provided
            let server_name: Option<OwnedServerName> = match server {
                Some(s) => {
                    Some(OwnedServerName::try_from(s).map_err(|e| FfiError::Msg(e.to_string()))?)
                }
                None => None,
            };

            // If a search term exists, use get_public_rooms_filtered; else use get_public_rooms.
            if let Some(term) = search.filter(|s| !s.trim().is_empty()) {
                let mut req = get_public_rooms_filtered::v3::Request::new();
                let mut f = Filter::new();
                f.generic_search_term = Some(term);
                req.filter = f;
                if let Some(s) = since.as_deref() {
                    req.since = Some(s.to_owned());
                }
                if limit > 0 {
                    req.limit = Some(limit.into());
                }
                if let Some(ref sn) = server_name {
                    req.server = Some(sn.clone());
                }

                let resp = self
                    .inner
                    .public_rooms_filtered(req)
                    .await
                    .map_err(|e| FfiError::Msg(e.to_string()))?;

                let rooms = resp
                    .chunk
                    .into_iter()
                    .map(|r| PublicRoom {
                        room_id: r.room_id.to_string(),
                        name: r.name,
                        topic: r.topic,
                        alias: r.canonical_alias.map(|a| a.to_string()),
                        avatar_url: r.avatar_url.map(|mxc| mxc.to_string()),
                        member_count: r.num_joined_members.into(),
                        world_readable: r.world_readable,
                        guest_can_join: r.guest_can_join,
                    })
                    .collect();

                Ok(PublicRoomsPage {
                    rooms,
                    next_batch: resp.next_batch,
                    prev_batch: resp.prev_batch,
                })
            } else {
                // Simple directory (no server-side filter)
                let resp = self
                    .inner
                    .public_rooms(Some(limit), since.as_deref(), server_name.as_deref())
                    .await
                    .map_err(|e| FfiError::Msg(e.to_string()))?;

                let rooms = resp
                    .chunk
                    .into_iter()
                    .map(|r| PublicRoom {
                        room_id: r.room_id.to_string(),
                        name: r.name,
                        topic: r.topic,
                        alias: r.canonical_alias.map(|a| a.to_string()),
                        avatar_url: r.avatar_url.map(|mxc| mxc.to_string()),
                        member_count: r.num_joined_members.into(),
                        world_readable: r.world_readable,
                        guest_can_join: r.guest_can_join,
                    })
                    .collect();

                Ok(PublicRoomsPage {
                    rooms,
                    next_batch: resp.next_batch,
                    prev_batch: resp.prev_batch,
                })
            }
        })
    }

    pub fn join_by_id_or_alias(&self, id_or_alias: String) -> bool {
        RT.block_on(async {
            let Ok(target) = OwnedRoomOrAliasId::try_from(id_or_alias) else {
                return false;
            };
            self.inner
                .join_room_by_id_or_alias(&target, &[])
                .await
                .is_ok()
        })
    }

    pub fn resolve_room_id(&self, id_or_alias: String) -> Result<String, FfiError> {
        RT.block_on(async {
            if id_or_alias.starts_with('!') {
                return Ok(id_or_alias);
            }
            if id_or_alias.starts_with('#') {
                let alias = OwnedRoomAliasId::try_from(id_or_alias)
                    .map_err(|e| FfiError::Msg(e.to_string()))?;
                let resp = self
                    .inner
                    .resolve_room_alias(&alias)
                    .await
                    .map_err(|e| FfiError::Msg(e.to_string()))?;
                return Ok(resp.room_id.to_string());
            }
            Err(FfiError::Msg("not a room id or alias".into()))
        })
    }

    // Ensure a DM exists with a user: reuse if present, else create one.
    pub fn ensure_dm(&self, user_id: String) -> Result<String, FfiError> {
        RT.block_on(async {
            let uid = user_id
                .parse::<OwnedUserId>()
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            if let Some(room) = self.inner.get_dm_room(&uid) {
                return Ok(room.room_id().to_string());
            }
            let room = self
                .inner
                .create_dm(&uid)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(room.room_id().to_string())
        })
    }

    /// Download full media into the SDK's cache dir and return its path.
    /// filename_hint is used to derive a friendly name/extension.
    pub fn download_to_cache_file(
        &self,
        mxc_uri: String,
        filename_hint: Option<String>,
    ) -> Result<DownloadResult, FfiError> {
        let dir = cache_dir(&self.store_dir);
        ensure_dir(&dir);

        // Safe-ish filename
        fn sanitize(name: &str) -> String {
            let mut s = String::with_capacity(name.len());
            for ch in name.chars() {
                if ch.is_ascii_alphanumeric() || "-_.".contains(ch) {
                    s.push(ch);
                } else {
                    s.push('_');
                }
            }
            s.trim_matches('_').to_string()
        }
        let hint = filename_hint
            .as_ref()
            .map(|h| sanitize(h))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "file.bin".to_string());
        let fname = format!("dl_{}_{}", now_ms(), hint);
        let out = dir.join(fname);

        let mxc: OwnedMxcUri = mxc_uri.into();
        let req = MediaRequestParameters {
            source: MediaSource::Plain(mxc),
            format: MediaFormat::File,
        };

        let bytes = RT
            .block_on(async { self.inner.media().get_media_content(&req, true).await })
            .map_err(|e| FfiError::Msg(format!("download: {e}")))?;

        std::fs::write(&out, &bytes)?;
        Ok(DownloadResult {
            path: out.to_string_lossy().to_string(),
            bytes: bytes.len() as u64,
        })
    }

    pub fn room_list_set_unread_only(&self, token: u64, unread_only: bool) -> bool {
        if let Some(tx) = self.room_list_cmds.lock().unwrap().get(&token).cloned() {
            tx.send(RoomListCmd::SetUnreadOnly(unread_only)).is_ok()
        } else {
            false
        }
    }

    pub fn fetch_notification(
        &self,
        room_id: String,
        event_id: String,
    ) -> Result<Option<RenderedNotification>, FfiError> {
        let rid = ruma::OwnedRoomId::try_from(room_id).map_err(|e| FfiError::Msg(e.to_string()))?;
        let eid =
            ruma::OwnedEventId::try_from(event_id).map_err(|e| FfiError::Msg(e.to_string()))?;

        let sync = {
            let g = self.sync_service.lock().unwrap();
            g.as_ref()
                .cloned()
                .ok_or_else(|| FfiError::Msg("SyncService not ready".into()))?
        };

        let nc = RT
            .block_on(async {
                NotificationClient::new(
                    self.inner.clone(),
                    NotificationProcessSetup::SingleProcess { sync_service: sync },
                )
                .await
            })
            .map_err(|e| FfiError::Msg(e.to_string()))?;

        let status = RT
            .block_on(async { nc.get_notification(&rid, &eid).await })
            .map_err(|e| FfiError::Msg(e.to_string()))?;

        match status {
            NotificationStatus::Event(item) => {
                let room_name = item.room_computed_display_name.clone();

                let mut sender = item
                    .sender_display_name
                    .clone()
                    .unwrap_or_else(|| item.event.sender().localpart().to_string());

                let mut body = String::from("New event");
                if let NotificationEvent::Timeline(ev) = &item.event {
                    if let ruma::events::AnySyncTimelineEvent::MessageLike(
                        ruma::events::AnySyncMessageLikeEvent::RoomMessage(m),
                    ) = ev.as_ref()
                    {
                        if let Some(orig) = m.as_original() {
                            sender = item
                                .sender_display_name
                                .clone()
                                .unwrap_or_else(|| orig.sender.localpart().to_string());
                            body = orig.content.body().to_string();
                        }
                    }
                }

                Ok(Some(RenderedNotification {
                    room_id: rid.to_string(),
                    event_id: eid.to_string(),
                    room_name,
                    sender,
                    body,
                    is_noisy: item.is_noisy.unwrap_or(false),
                    has_mention: item.has_mention.unwrap_or(false),
                }))
            }
            NotificationStatus::EventFilteredOut => Ok(None),
            NotificationStatus::EventNotFound => Ok(None),
        }
    }

    /// SSO with built-in loopback server. Opens a browser and completes login.
    pub fn login_sso_loopback(
        &self,
        opener: Box<dyn UrlOpener>,
        device_name: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            self.inner
                .matrix_auth()
                .login_sso(move |sso_url: String| async move {
                    let _ = opener.open(sso_url);
                    Ok(())
                })
                .initial_device_display_name(device_name.as_deref().unwrap_or("Mages"))
                .send()
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            if let Some(sess) = self.inner.matrix_auth().session() {
                tokio::fs::create_dir_all(&self.store_dir).await?;
                let info = SessionInfo {
                    user_id: sess.meta.user_id.to_string(),
                    device_id: sess.meta.device_id.to_string(),
                    access_token: sess.tokens.access_token.clone(),
                    refresh_token: sess.tokens.refresh_token.clone(),
                    homeserver: self.inner.homeserver().to_string(),
                };
                tokio::fs::write(
                    session_file(&self.store_dir),
                    serde_json::to_string(&info).unwrap(),
                )
                .await?;
            }

            let _ = self.inner.sync_once(SyncSettings::default()).await;

            Ok(())
        })
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

async fn get_timeline_for(client: &SdkClient, room_id: &OwnedRoomId) -> Option<Timeline> {
    let room = client.get_room(room_id)?;
    room.timeline().await.ok()
}

fn map_event(ev: &EventTimelineItem, room_id: &str) -> Option<MessageEvent> {
    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev.event_id().map(|e| e.to_string()).unwrap_or_default();
    let txn_id = ev.transaction_id().map(|t| t.to_string());
    let send_state = match ev.send_state() {
        Some(EventSendState::NotSentYet { .. }) => Some(SendState::Sending),
        Some(EventSendState::SendingFailed { .. }) => Some(SendState::Failed),
        Some(EventSendState::Sent { .. }) => Some(SendState::Sent),
        None => None,
    };

    let mut reply_to_event_id: Option<String> = None;
    let mut reply_to_sender: Option<String> = None;
    let mut reply_to_body: Option<String> = None;
    let mut attachment: Option<AttachmentInfo> = None;
    let body;

    match ev.content() {
        TimelineItemContent::MsgLike(ml) => {
            // Reply details
            if let Some(details) = &ml.in_reply_to {
                reply_to_event_id = Some(details.event_id.to_string());
                if let matrix_sdk_ui::timeline::TimelineDetails::Ready(embed) = &details.event {
                    reply_to_sender = Some(embed.sender.to_string());
                    if let Some(m) = embed.content.as_message() {
                        reply_to_body = Some(m.body().to_owned());
                    }
                }
            }

            if let Some(msg) = ml.as_message() {
                use matrix_sdk::ruma::events::room::message::MessageType as MT;
                let mt = msg.msgtype();
                match mt {
                    MT::Image(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (w, h, size, mime, thumb) = if let Some(info) = &c.info {
                            (
                                info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::Image,
                                mxc_uri,
                                mime,
                                size_bytes: size,
                                width: w,
                                height: h,
                                duration_ms: None,
                                thumbnail_mxc_uri: thumb,
                            });
                        }
                    }
                    MT::Video(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (w, h, size, mime, dur, thumb) = if let Some(info) = &c.info {
                            (
                                info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.duration.map(|d| d.as_millis() as u64),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None, None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::Video,
                                mxc_uri: mxc_uri.clone(),
                                mime,
                                size_bytes: size,
                                width: w,
                                height: h,
                                duration_ms: dur,
                                thumbnail_mxc_uri: thumb.or_else(|| Some(mxc_uri.clone())),
                            });
                        }
                    }
                    MT::File(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (size, mime, thumb) = if let Some(info) = &c.info {
                            (
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::File,
                                mxc_uri,
                                mime,
                                size_bytes: size,
                                width: None,
                                height: None,
                                duration_ms: None,
                                thumbnail_mxc_uri: thumb,
                            });
                        }
                    }
                    _ => {}
                }

                let raw = msg.body();
                body = if reply_to_event_id.is_some() {
                    strip_reply_fallback(raw)
                } else {
                    render_message_text(&msg)
                };
            } else {
                body = render_timeline_text(ev);
            }
        }
        _ => {
            body = render_timeline_text(ev);
        }
    }

    Some(MessageEvent {
        item_id: format!("{:?}", ev.identifier()),
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        body,
        timestamp_ms: ts,
        send_state,
        txn_id,
        reply_to_event_id,
        reply_to_sender,
        reply_to_body,
        attachment,
    })
}

fn map_event_with_uid(ev: &EventTimelineItem, room_id: &str, uid: &str) -> Option<MessageEvent> {
    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev.event_id().map(|e| e.to_string()).unwrap_or_default();
    let txn_id = ev.transaction_id().map(|t| t.to_string());
    let send_state = match ev.send_state() {
        Some(EventSendState::NotSentYet { .. }) => Some(SendState::Sending),
        Some(EventSendState::SendingFailed { .. }) => Some(SendState::Failed),
        Some(EventSendState::Sent { event_id }) => Some(SendState::Sent),
        None => None,
    };

    let mut reply_to_event_id: Option<String> = None;
    let mut reply_to_sender: Option<String> = None;
    let mut reply_to_body: Option<String> = None;
    let body;
    let mut attachment: Option<AttachmentInfo> = None;

    match ev.content() {
        TimelineItemContent::MsgLike(ml) => {
            if let Some(details) = &ml.in_reply_to {
                reply_to_event_id = Some(details.event_id.to_string());
                if let matrix_sdk_ui::timeline::TimelineDetails::Ready(embed) = &details.event {
                    reply_to_sender = Some(embed.sender.to_string());
                    if let Some(m) = embed.content.as_message() {
                        reply_to_body = Some(m.body().to_owned());
                    }
                }
            }
            if let Some(msg) = ml.as_message() {
                use matrix_sdk::ruma::events::room::message::MessageType as MT;
                let mt = msg.msgtype();
                match mt {
                    MT::Image(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (w, h, size, mime, thumb) = if let Some(info) = &c.info {
                            (
                                info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::Image,
                                mxc_uri,
                                mime,
                                size_bytes: size,
                                width: w,
                                height: h,
                                duration_ms: None,
                                thumbnail_mxc_uri: thumb,
                            });
                        }
                    }
                    MT::Video(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (w, h, size, mime, dur, thumb) = if let Some(info) = &c.info {
                            (
                                info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.duration.map(|d| d.as_millis() as u64),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None, None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::Video,
                                mxc_uri: mxc_uri.clone(),
                                mime,
                                size_bytes: size,
                                width: w,
                                height: h,
                                duration_ms: dur,
                                thumbnail_mxc_uri: thumb.or_else(|| Some(mxc_uri.clone())),
                            });
                        }
                    }
                    MT::File(c) => {
                        let mxc = mxc_from_media_source(&c.source);
                        let (size, mime, thumb) = if let Some(info) = &c.info {
                            (
                                info.size.map(|v| u64::from(v)),
                                info.mimetype.clone(),
                                info.thumbnail_source
                                    .as_ref()
                                    .and_then(|s| mxc_from_media_source(s)),
                            )
                        } else {
                            (None, None, None)
                        };
                        if let Some(mxc_uri) = mxc {
                            attachment = Some(AttachmentInfo {
                                kind: AttachmentKind::File,
                                mxc_uri,
                                mime,
                                size_bytes: size,
                                width: None,
                                height: None,
                                duration_ms: None,
                                thumbnail_mxc_uri: thumb,
                            });
                        }
                    }
                    _ => {}
                }
                let raw = msg.body();
                body = if reply_to_event_id.is_some() {
                    strip_reply_fallback(raw)
                } else {
                    render_message_text(&msg)
                };
            } else {
                body = render_timeline_text(ev);
            }
        }
        _ => body = render_timeline_text(ev),
    }

    Some(MessageEvent {
        item_id: uid.to_string(),
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        body,
        timestamp_ms: ts,
        send_state,
        txn_id,
        reply_to_event_id,
        reply_to_sender,
        reply_to_body,
        attachment,
    })
}

fn render_timeline_text(ev: &EventTimelineItem) -> String {
    match ev.content() {
        TimelineItemContent::MsgLike(msg_like) => render_msg_like(ev, msg_like),
        TimelineItemContent::MembershipChange(change) => render_membership_change(ev, change),
        TimelineItemContent::ProfileChange(change) => render_profile_change(ev, change),
        TimelineItemContent::OtherState(state) => render_other_state(ev, state),
        TimelineItemContent::FailedToParseMessageLike { event_type, .. } => {
            format!("Unsupported message-like event: {}", event_type)
        }
        TimelineItemContent::FailedToParseState { event_type, .. } => {
            format!("Unsupported state event: {}", event_type)
        }
        TimelineItemContent::CallInvite => "Started a call".to_string(),
        TimelineItemContent::CallNotify => "Call notification".to_string(),
    }
}

fn render_msg_like(_ev: &EventTimelineItem, ml: &MsgLikeContent) -> String {
    use MsgLikeKind::*;
    match &ml.kind {
        Message(m) => render_message_text(m),
        Sticker(_s) => "sent a sticker".to_string(),
        Poll(_p) => "started a poll".to_string(),
        Redacted => "Message deleted".to_string(),
        UnableToDecrypt(_e) => "Unable to decrypt this message".to_string(),
    }
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

fn render_message_text(msg: &matrix_sdk_ui::timeline::Message) -> String {
    let mut s = msg.body().to_owned();
    if s.trim().is_empty() {
        s = "Encrypted or unsupported message. Verify this session or restore keys to view."
            .to_owned();
    }
    if msg.is_edited() {
        s.push_str(" \n(edited)");
    }
    s
}

fn strip_reply_fallback(body: &str) -> String {
    let lines = body.lines();
    let mut consumed = 0usize;
    // Consume leading quoted lines (starting with '>')
    for l in body.lines() {
        if l.starts_with('>') {
            consumed += 1;
        } else {
            break;
        }
    }
    // Optionally consume a single blank line after the quote block
    let remaining: Vec<&str> = body.lines().collect();
    let mut start = consumed;
    if start < remaining.len() && remaining[start].trim().is_empty() && consumed > 0 {
        start += 1;
    }
    remaining[start..]
        .join("\n")
        .if_empty_then(|| body.to_owned())
}

trait IfEmptyThen {
    fn if_empty_then<F: FnOnce() -> String>(self, f: F) -> String;
}
impl IfEmptyThen for String {
    fn if_empty_then<F: FnOnce() -> String>(self, f: F) -> String {
        if self.trim().is_empty() { f() } else { self }
    }
}

fn render_membership_change(
    ev: &EventTimelineItem,
    ch: &matrix_sdk_ui::timeline::RoomMembershipChange,
) -> String {
    use matrix_sdk_ui::timeline::MembershipChange as MC;

    let actor = ev.sender().to_string();
    let subject = ch.user_id().to_string();

    match ch.change() {
        Some(MC::Joined) => format!("{subject} joined the room"),
        Some(MC::Left) => format!("{subject} left the room"),
        Some(MC::Invited) => format!("{actor} invited {subject}"),
        Some(MC::Kicked) => format!("{actor} removed {subject}"),
        Some(MC::Banned) => format!("{actor} banned {subject}"),
        Some(MC::Unbanned) => format!("{actor} unbanned {subject}"),
        Some(MC::InvitationAccepted) => format!("{subject} accepted the invite"),
        Some(MC::InvitationRejected) => format!("{subject} rejected the invite"),
        Some(MC::InvitationRevoked) => format!("{actor} revoked the invite for {subject}"),
        Some(MC::KickedAndBanned) => format!("{actor} removed and banned {subject}"),
        Some(MC::Knocked) => format!("{subject} knocked"),
        Some(MC::KnockAccepted) => format!("{actor} accepted {subject}"),
        Some(MC::KnockDenied) => format!("{actor} denied {subject}"),
        _ => format!("{subject} updated membership"),
    }
}

fn render_profile_change(
    _ev: &EventTimelineItem,
    pc: &matrix_sdk_ui::timeline::MemberProfileChange,
) -> String {
    let subject = pc.user_id().to_string();

    if let Some(ch) = pc.displayname_change() {
        match (&ch.old, &ch.new) {
            (None, Some(new)) => return format!("{subject} set their display name to “{new}”"),
            (Some(old), Some(new)) if old != new => {
                return format!("{subject} changed their display name from “{old}” to “{new}”");
            }
            (Some(_), None) => return format!("{subject} removed their display name"),
            _ => {}
        }
    }

    if pc.avatar_url_change().is_some() {
        return format!("{subject} updated their avatar");
    }

    format!("{subject} updated their profile")
}

fn render_other_state(ev: &EventTimelineItem, s: &matrix_sdk_ui::timeline::OtherState) -> String {
    use matrix_sdk_ui::timeline::AnyOtherFullStateEventContent as A;

    let actor = ev.sender().to_string();
    match s.content() {
        A::RoomName(c) => {
            let mut name = "";

            if let ruma::events::FullStateEventContent::Original { content, .. } = c {
                name = &content.name;
            }
            format!("{actor} changed the room name to {name}")
        }
        A::RoomTopic(c) => {
            let mut topic = "";
            if let ruma::events::FullStateEventContent::Original { content, .. } = c {
                topic = &content.topic;
            }
            format!("{actor} changed the topic to {topic}")
        }
        A::RoomAvatar(_) => format!("{actor} changed the room avatar"),
        A::RoomEncryption(_) => "Encryption enabled for this room".to_string(),
        A::RoomPinnedEvents(_) => format!("{actor} updated pinned events"),
        A::RoomPowerLevels(_) => format!("{actor} changed power levels"),
        A::RoomCanonicalAlias(_) => format!("{actor} changed the main address"),
        _ => {
            let ty = s.content().event_type().to_string();
            format!("{actor} updated state: {ty}")
        }
    }
}

fn mxc_from_media_source(src: &matrix_sdk::ruma::events::room::MediaSource) -> Option<String> {
    use matrix_sdk::ruma::events::room::MediaSource as MS;
    match src {
        MS::Plain(mxc) => Some(mxc.to_string()),
        MS::Encrypted(file) => Some(file.url.to_string()),
    }
}

fn missing_reply_event_id(ev: &EventTimelineItem) -> Option<matrix_sdk::ruma::OwnedEventId> {
    if let TimelineItemContent::MsgLike(ml) = ev.content() {
        if let Some(details) = &ml.in_reply_to {
            use matrix_sdk_ui::timeline::TimelineDetails::*;
            if !matches!(details.event, Ready(_)) {
                return Some(details.event_id.clone());
            }
        }
    }
    None
}

#[uniffi::export(callback_interface)]
pub trait UrlOpener: Send + Sync {
    fn open(&self, url: String) -> bool;
}
