#![allow(unused_imports)]
use js_int::UInt;
use matrix_sdk::{
    PredecessorRoom, SuccessorRoom, notification_settings::NotificationSettings,
    ruma::room::Restricted,
};
use matrix_sdk_base::notification_settings::RoomNotificationMode as RsMode;
use matrix_sdk_ui::{
    eyeball_im::Vector,
    timeline::{AttachmentConfig, AttachmentSource},
};
use mime::Mime;
use once_cell::sync::Lazy;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;
use tracing::{debug, error, info, warn};
use tracing_subscriber::{EnvFilter, fmt};
use uniffi::{Enum, HandleAlloc, Object, Record, export, setup_scaffolding};

use futures_util::StreamExt;
use matrix_sdk::{
    Client as SdkClient, OwnedServerName, Room, RoomMemberships, SessionTokens,
    authentication::{matrix::MatrixSession, oauth::OAuthError},
    config::SyncSettings,
    media::{MediaFormat, MediaRequestParameters, MediaRetentionPolicy, MediaThumbnailSettings},
    ruma::{
        OwnedMxcUri, OwnedRoomAliasId, OwnedRoomOrAliasId, SpaceChildOrder,
        api::client::{
            directory::get_public_rooms_filtered,
            push::{Pusher, PusherIds, PusherInit, PusherKind},
            room::{Visibility, create_room::v3::RoomPreset},
        },
        directory::Filter,
        events::room::{
            EncryptedFile, MediaSource, name::RoomNameEventContent, topic::RoomTopicEventContent,
        },
        push::HttpPusherData,
        room::RoomType,
    },
};
use matrix_sdk::{
    encryption::BackupDownloadStrategy,
    ruma::{
        EventId, OwnedDeviceId, OwnedRoomId, OwnedUserId,
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
    eyeball_im::VectorDiff,
    notification_client::{
        NotificationClient, NotificationEvent, NotificationProcessSetup, NotificationStatus,
    },
    room_list_service::filters,
    sync_service::{State, SyncService},
    timeline::{
        EventSendState, EventTimelineItem, MsgLikeContent, MsgLikeKind, RoomExt as _, Timeline,
        TimelineItem, TimelineItemContent,
    },
};
use ruma::api::client::room::create_room::v3 as create_room_v3;
use thiserror::Error;

use ruma::{
    api::{
        Direction,
        client::relations::get_relating_events_with_rel_type_and_event_type as get_relating,
    },
    events::{
        TimelineEventType,
        relation::{RelationType, Thread as ThreadRel},
        room::message::{Relation as MsgRelation, RoomMessageEventContent},
    },
};

use matrix_sdk::live_location_share::ObservableLiveLocation;
use matrix_sdk::ruma::{
    RoomVersionId,
    api::client::presence::{
        get_presence::v3 as get_presence_v3, set_presence::v3 as set_presence_v3,
    },
    api::client::room::upgrade_room::v3 as upgrade_room_v3,
    events::poll::{
        start::PollKind as RumaPollKind,
        unstable_end::UnstablePollEndEventContent,
        unstable_response::UnstablePollResponseEventContent,
        unstable_start::{
            NewUnstablePollStartEventContent, UnstablePollAnswer, UnstablePollAnswers,
            UnstablePollStartContentBlock,
        },
    },
    events::room::{
        history_visibility::HistoryVisibility, join_rules::JoinRule,
        tombstone::RoomTombstoneEventContent,
    },
    presence::PresenceState,
};
use std::panic::AssertUnwindSafe;

// UniFFI macro-first setup
setup_scaffolding!();

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

#[export(callback_interface)]
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
    pub thread_root_event_id: Option<String>,
}

#[derive(Clone, Enum)]
pub enum AttachmentKind {
    Image,
    Video,
    File,
}

#[derive(Clone, Record)]
pub struct EncFile {
    /// mxc://… of the encrypted media
    pub url: String,
    /// Full JSON of ruma::events::room::message::EncryptedFile (v2)
    pub json: String,
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
    /// encrypted "file" (main content)
    pub encrypted: Option<EncFile>,
    /// encrypted "thumbnail_file"
    pub thumbnail_encrypted: Option<EncFile>,
}

#[derive(Clone, Record)]
pub struct DeviceSummary {
    pub device_id: String,
    pub display_name: String,
    pub ed25519: String,
    pub is_own: bool,
    pub verified: bool,
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

#[export(callback_interface)]
pub trait SyncObserver: Send + Sync {
    fn on_state(&self, status: SyncStatus);
}

#[export(callback_interface)]
pub trait TypingObserver: Send + Sync {
    fn on_update(&self, names: Vec<String>);
}

#[export(callback_interface)]
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

#[export(callback_interface)]
pub trait CallObserver: Send + Sync {
    fn on_invite(&self, invite: CallInvite); // Optional future: on_hangup, on_answer…
}

#[export(callback_interface)]
pub trait ProgressObserver: Send + Sync {
    fn on_progress(&self, sent: u64, total: Option<u64>);
}

#[derive(Clone, Record)]
pub struct DownloadResult {
    pub path: String,
    pub bytes: u64,
}

#[derive(Clone, Record)]
pub struct RenderedNotification {
    pub room_id: String,
    pub event_id: String,
    pub room_name: String,
    pub sender: String,
    pub body: String,
    pub is_noisy: bool,
    pub has_mention: bool,
}

#[derive(Clone, Record)]
pub struct UnreadStats {
    pub messages: u64,
    pub notifications: u64,
    pub mentions: u64,
}

#[derive(Clone, Record)]
pub struct RoomProfile {
    pub room_id: String,
    pub name: String,
    pub topic: Option<String>,
    pub member_count: u64,
    pub is_encrypted: bool,
    pub is_dm: bool,
}

#[derive(Clone, Record)]
pub struct MemberSummary {
    pub user_id: String,
    pub display_name: Option<String>,
    pub is_me: bool,
    pub membership: String,
}

enum RoomListCmd {
    SetUnreadOnly(bool),
}

#[derive(uniffi::Record)]
pub struct RoomTags {
    pub is_favourite: bool,
    pub is_low_priority: bool,
}

#[derive(Clone, Record)]
pub struct ThreadPage {
    pub root_event_id: String,
    pub room_id: String,
    pub messages: Vec<MessageEvent>,
    pub next_batch: Option<String>,
    pub prev_batch: Option<String>,
}

#[derive(Clone, Record)]
pub struct ThreadSummary {
    pub root_event_id: String,
    pub room_id: String,
    pub count: u64,
    pub latest_ts_ms: Option<u64>,
}

#[derive(Record, Clone)]
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

#[export(callback_interface)]
pub trait VerificationObserver: Send + Sync {
    fn on_phase(&self, flow_id: String, phase: SasPhase);
    fn on_emojis(&self, payload: SasEmojis);
    fn on_error(&self, flow_id: String, message: String);
}

#[export(callback_interface)]
pub trait VerificationInboxObserver: Send + Sync {
    fn on_request(&self, flow_id: String, from_user: String, from_device: String);
    fn on_error(&self, message: String);
}

#[derive(Clone, Record)]
pub struct RoomListEntry {
    pub room_id: String,
    pub name: String,

    pub last_ts: u64,

    pub notifications: u64,
    pub messages: u64,
    pub mentions: u64,
    pub marked_unread: bool,
}

#[derive(Clone, Enum)]
pub enum FfiRoomNotificationMode {
    AllMessages,
    MentionsAndKeywordsOnly,
    Mute,
}

#[derive(Clone, Record)]
pub struct DirectoryUser {
    pub user_id: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
}

#[derive(Clone, Record)]
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

#[derive(Clone, Record)]
pub struct PublicRoomsPage {
    pub rooms: Vec<PublicRoom>,
    pub next_batch: Option<String>,
    pub prev_batch: Option<String>,
}

#[export(callback_interface)]
pub trait RoomListObserver: Send + Sync {
    fn on_reset(&self, items: Vec<RoomListEntry>);
    fn on_update(&self, item: RoomListEntry);
}

#[derive(Clone, Record)]
pub struct InviteSummary {
    pub room_id: String,
    pub name: String,
}

#[derive(Clone, Record)]
pub struct ReactionSummary {
    pub key: String,
    pub count: u32,
    pub me: bool,
}

#[derive(Clone, Record)]
pub struct SpaceInfo {
    pub room_id: String,
    pub name: String,
    pub topic: Option<String>,
    pub member_count: u64,
    pub is_encrypted: bool,
    pub is_public: bool,
}

#[derive(Clone, Record)]
pub struct SpaceChildInfo {
    pub room_id: String,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub alias: Option<String>,
    pub avatar_url: Option<String>,
    pub is_space: bool,
    pub member_count: u64,
    pub world_readable: bool,
    pub guest_can_join: bool,
    pub suggested: bool,
}

#[derive(Clone, Record)]
pub struct SpaceHierarchyPage {
    pub children: Vec<SpaceChildInfo>,
    pub next_batch: Option<String>,
}

#[derive(Clone, Enum)]
pub enum TimelineDiffKind {
    Append { values: Vec<MessageEvent> },
    PushBack { value: MessageEvent },
    PushFront { value: MessageEvent },
    PopBack,
    PopFront,
    Insert { index: u32, value: MessageEvent },
    Remove { index: u32 },
    Set { index: u32, value: MessageEvent },
    Truncate { length: u32 },
    Reset { values: Vec<MessageEvent> },
    Clear,
}

#[uniffi::export(callback_interface)]
pub trait TimelineObserver: Send + Sync {
    fn on_diff(&self, diff: TimelineDiffKind);
    fn on_error(&self, message: String);
}

#[derive(Clone, Enum)]
pub enum PollKind {
    Disclosed,
    Undisclosed,
}

#[derive(Clone, Record)]
pub struct PollDefinition {
    /// Question text
    pub question: String,
    /// Answer labels – IDs will be generated as "a", "b", "c", ...
    pub answers: Vec<String>,
    /// Poll kind: disclosed vs. undisclosed
    pub kind: PollKind,
    /// Max selections per user (1 = single choice)
    pub max_selections: u32,
}

#[derive(Clone, Record)]
pub struct LiveLocationShareInfo {
    pub user_id: String,
    pub geo_uri: String,
    pub ts_ms: u64,
    pub is_live: bool,
}

#[export(callback_interface)]
pub trait LiveLocationObserver: Send + Sync {
    fn on_update(&self, shares: Vec<LiveLocationShareInfo>);
}

#[derive(Clone, Enum)]
pub enum Presence {
    Online,
    Offline,
    Unavailable,
}

#[derive(Clone, Enum)]
pub enum RoomDirectoryVisibility {
    Public,
    Private,
}

#[derive(Clone, Enum)]
pub enum RoomJoinRule {
    Public,
    Invite,
    Knock,
    Restricted,
    KnockRestricted,
}

#[derive(Clone, Enum)]
pub enum RoomHistoryVisibility {
    Invited,
    Joined,
    Shared,
    WorldReadable,
}

#[derive(Clone, Record)]
pub struct SuccessorRoomInfo {
    pub room_id: String,
    pub reason: Option<String>,
}

#[derive(Clone, Record)]
pub struct PredecessorRoomInfo {
    pub room_id: String,
}

#[derive(Clone, Record)]
pub struct RoomUpgradeLinks {
    pub is_tombstoned: bool,
    pub successor: Option<SuccessorRoomInfo>,
    pub predecessor: Option<PredecessorRoomInfo>,
}

impl From<SuccessorRoom> for SuccessorRoomInfo {
    fn from(v: SuccessorRoom) -> Self {
        SuccessorRoomInfo {
            room_id: v.room_id.to_string(),
            reason: v.reason,
        }
    }
}

impl From<PredecessorRoom> for PredecessorRoomInfo {
    fn from(v: PredecessorRoom) -> Self {
        PredecessorRoomInfo {
            room_id: v.room_id.to_string(),
        }
    }
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
    _other_user: OwnedUserId,
    _other_device: OwnedDeviceId,
}

type VerifMap = Arc<Mutex<HashMap<String, VerifFlow>>>;

#[derive(Object)]
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
    live_location_subs: Mutex<HashMap<u64, tokio::task::JoinHandle<()>>>,
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

#[export(callback_interface)]
pub trait SendObserver: Send + Sync {
    fn on_update(&self, update: SendUpdate);
}

macro_rules! sub_manager {
    ($self:expr, $subs:ident, $spawn:expr) => {{
        let id = $self.next_sub_id();
        let h = RT.spawn($spawn);
        $self.$subs.lock().unwrap().insert(id, h);
        id
    }};
}

macro_rules! unsub {
    ($self:expr, $subs:ident, $id:expr) => {{
        if let Some(h) = $self.$subs.lock().unwrap().remove(&$id) {
            h.abort();
            true
        } else {
            false
        }
    }};
}

macro_rules! with_room_async {
    ($self:expr, $room_id:expr, $body:expr) => {{
        RT.block_on(async {
            let rid = match OwnedRoomId::try_from($room_id) {
                Ok(r) => r,
                Err(_) => return false,
            };
            let room = match $self.inner.get_room(&rid) {
                Some(r) => r,
                None => return false,
            };
            $body(room, rid).await
        })
    }};
}

macro_rules! with_timeline_async {
    ($self:expr, $room_id:expr, $body:expr) => {{
        RT.block_on(async {
            let rid = match OwnedRoomId::try_from($room_id) {
                Ok(r) => r,
                Err(_) => return false,
            };
            let tl = match get_timeline_for(&$self.inner, &rid).await {
                Some(t) => t,
                None => return false,
            };
            $body(tl, rid).await
        })
    }};
}

static TRACING_INIT: Lazy<()> = Lazy::new(|| {
    let filter = EnvFilter::from_default_env()
        .add_directive("mages_ffi=debug".parse().unwrap())
        .add_directive("matrix_sdk=info".parse().unwrap())
        .add_directive("matrix_sdk_crypto=info".parse().unwrap());

    fmt()
        .with_env_filter(filter)
        .with_target(true)
        .without_time() // avoids weird timestamps on Android
        .init();
});

fn init_tracing() {
    Lazy::force(&TRACING_INIT);
}

#[export]
impl Client {
    #[uniffi::constructor]
    pub fn new(homeserver_url: String, store_dir: String) -> Self {
        init_tracing();

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
            live_location_subs: Mutex::new(HashMap::new()),
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
        with_room_async!(self, room_id, |room: Room, _rid| async move {
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
                .filter_map(|it| {
                    it.as_event().and_then(|ev| {
                        map_timeline_event(
                            ev,
                            room_id.as_str(),
                            Some(&it.unique_id().0.to_string()),
                        )
                    })
                })
                .take(limit as usize)
                .collect();
            out.reverse();
            out
        })
    }

    pub fn observe_timeline(&self, room_id: String, observer: Box<dyn TimelineObserver>) -> u64 {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TimelineObserver> = Arc::from(observer);

        sub_manager!(self, timeline_subs, async move {
            let Some(room) = client.get_room(&room_id) else {
                return;
            };
            let Ok(tl) = room.timeline().await else {
                return;
            };
            let tl = Arc::new(tl);
            let (items, mut stream) = tl.subscribe().await;

            // Initial snapshot
            let initial: Vec<_> = items
                .iter()
                .filter_map(|it| {
                    it.as_event().and_then(|ei| {
                        map_timeline_event(
                            ei,
                            room_id.as_str(),
                            Some(&it.unique_id().0.to_string()),
                        )
                    })
                })
                .collect();
            obs.on_diff(TimelineDiffKind::Reset { values: initial });

            // Fetch missing reply details
            for it in items.iter() {
                if let Some(ev) = it.as_event() {
                    if let Some(eid) = missing_reply_event_id(ev) {
                        let tlc = tl.clone();
                        tokio::spawn(async move {
                            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
                        });
                    }
                }
            }

            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    if let Some(mapped) = map_vec_diff(diff, &room_id, &tl) {
                        let _ = std::panic::catch_unwind(AssertUnwindSafe(|| obs.on_diff(mapped)));
                    }
                }
            }
        })
    }

    pub fn unobserve_timeline(&self, sub_id: u64) -> bool {
        unsub!(self, timeline_subs, sub_id)
    }

    pub fn start_verification_inbox(&self, observer: Box<dyn VerificationInboxObserver>) -> u64 {
        let client = self.inner.clone();
        let obs: Arc<dyn VerificationInboxObserver> = Arc::from(observer);
        let inbox = self.inbox.clone();

        let id = self.next_sub_id();
        let h = RT.spawn(async move {
        info!("verification_inbox: start (sub_id={})", id);

        let td_handler = client.observe_events::<ToDeviceKeyVerificationRequestEvent, ()>();
        let mut td_sub = td_handler.subscribe();
        info!("verification_inbox: subscribed to ToDeviceKeyVerificationRequestEvent");

        let ir_handler = client.observe_events::<SyncRoomMessageEvent, Room>();
        let mut ir_sub = ir_handler.subscribe();
        info!("verification_inbox: subscribed to SyncRoomMessageEvent");

        loop {
            tokio::select! {
                maybe = td_sub.next() => {
                    info!("verification_inbox: to-device next = {:?}", maybe.as_ref().map(|(ev, _)| &ev.content.transaction_id));
                    if let Some((ev, ())) = maybe {
                        let flow_id    = ev.content.transaction_id.to_string();
                        let from_user  = ev.sender.to_string();
                        let from_device= ev.content.from_device.to_string();

                        inbox.lock().unwrap().insert(
                            flow_id.clone(),
                            (ev.sender, ev.content.from_device.clone()),
                        );

                        info!("verification_inbox: got to-device request flow_id={} from {} / {}",
                              flow_id, from_user, from_device);

                        let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                            obs.on_request(flow_id, from_user, from_device);
                        }));
                    } else {
                        info!("verification_inbox: to-device stream ended");
                        break;
                    }
                }

                maybe = ir_sub.next() => {
                    info!("verification_inbox: in-room next = {:?}", maybe.as_ref().map(|(ev, _)| ev.event_id()));
                    if let Some((ev, _room)) = maybe {
                        if let SyncRoomMessageEvent::Original(o) = ev {
                            if let MessageType::VerificationRequest(_c) = &o.content.msgtype {
                                let flow_id   = o.event_id.to_string();
                                let from_user = o.sender.to_string();

                                inbox.lock().unwrap().insert(
                                    flow_id.clone(),
                                    (o.sender.clone(), owned_device_id!("inroom")),
                                );

                                info!("verification_inbox: got in-room request flow_id={} from {}",
                                      flow_id, from_user);

                                let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                                    obs.on_request(flow_id, from_user, String::new());
                                }));
                            }
                        }
                    } else {
                        info!("verification_inbox: in-room stream ended");
                        break;
                    }
                }
            }
        }
    });

        self.inbox_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_verification_inbox(&self, sub_id: u64) -> bool {
        unsub!(self, inbox_subs, sub_id)
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
        unsub!(self, connection_subs, sub_id)
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
        for (_, h) in self.live_location_subs.lock().unwrap().drain() {
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
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            tl.mark_as_read(ReceiptType::ReadPrivate).await.is_ok()
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

            room.send_single_receipt(
                ReceiptType::ReadPrivate,
                ReceiptThread::Unthreaded,
                eid.to_owned(),
            )
            .await
            .is_ok()
        })
    }

    pub fn set_mark_unread(&self, room_id: String, unread: bool) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            room.set_unread_flag(unread).await.is_ok()
        })
    }

    pub fn is_marked_unread(&self, room_id: String) -> Option<bool> {
        RT.block_on(async {
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };
            Some(room.is_marked_unread())
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
        att: AttachmentInfo,
        width: u32,
        height: u32,
        use_crop: bool,
    ) -> Result<String, FfiError> {
        use matrix_sdk::media::{MediaFormat, MediaRequestParameters, MediaThumbnailSettings};
        use ruma::events::room::MediaSource;

        let (source, format, name_key) = if let Some(enc) = att.thumbnail_encrypted.as_ref() {
            let ef: EncryptedFile = serde_json::from_str(&enc.json)
                .map_err(|e| FfiError::Msg(format!("thumb enc parse: {e}")))?;
            (
                MediaSource::Encrypted(Box::new(ef)),
                MediaFormat::File,
                enc.url.clone(),
            )
        } else if let Some(mxc) = att.thumbnail_mxc_uri.as_ref() {
            (
                MediaSource::Plain(mxc.clone().into()),
                MediaFormat::File,
                mxc.clone(),
            )
        } else if let Some(enc) = att.encrypted.as_ref() {
            // fetch full encrypted file as fallback
            let ef: EncryptedFile = serde_json::from_str(&enc.json)
                .map_err(|e| FfiError::Msg(format!("file enc parse: {e}")))?;
            (
                MediaSource::Encrypted(Box::new(ef)),
                MediaFormat::File,
                enc.url.clone(),
            )
        } else {
            // Plain primary mxc
            let settings = if use_crop {
                MediaThumbnailSettings::with_method(
                    matrix_sdk::ruma::api::client::media::get_content_thumbnail::v3::Method::Crop,
                    width.into(),
                    height.into(),
                )
            } else {
                MediaThumbnailSettings::new(width.into(), height.into())
            };
            let mxc = att.mxc_uri.clone();
            (
                MediaSource::Plain(mxc.clone().into()),
                MediaFormat::Thumbnail(settings),
                mxc,
            )
        };

        let req = MediaRequestParameters { source, format };

        let dir = cache_dir(&self.store_dir);
        ensure_dir(&dir);
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
        let key =
            blake3::hash(format!("{}-{}x{}-{}", name_key, width, height, use_crop).as_bytes())
                .to_hex();
        let ext = att
            .mime
            .as_deref()
            .and_then(|m| m.split('/').nth(1))
            .filter(|e| !e.is_empty())
            .unwrap_or("jpg");
        let fname = format!(
            "thumb_{}_{}x{}{}.{ext}",
            &key[..16],
            width,
            height,
            if use_crop { "_crop" } else { "_scale" }
        );
        let out = dir.join(sanitize(&fname));
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let bytes = RT
            .block_on(async { self.inner.media().get_media_content(&req, true).await })
            .or_else(|_e| {
                // Fallback only when we asked for a server-side thumb of a plain mxc
                if matches!(req.format, MediaFormat::Thumbnail(_)) {
                    let req_full = MediaRequestParameters {
                        source: req.source.clone(),
                        format: MediaFormat::File,
                    };
                    RT.block_on(async {
                        self.inner.media().get_media_content(&req_full, true).await
                    })
                } else {
                    Err(_e)
                }
            })
            .map_err(|e| FfiError::Msg(format!("thumbnail fetch: {e}")))?;

        std::fs::write(&out, &bytes)?;
        Ok(out.to_string_lossy().to_string())
    }

    pub fn react(&self, room_id: String, event_id: String, emoji: String) -> bool {
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            let Ok(eid) = EventId::parse(&event_id) else {
                return false;
            };
            let Some(item) = tl.item_by_event_id(&eid).await else {
                return false;
            };
            let item_id = item.identifier();
            tl.toggle_reaction(&item_id, &emoji).await.is_ok()
        })
    }

    pub fn reply(&self, room_id: String, in_reply_to: String, body: String) -> bool {
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(reply_to) = EventId::parse(&in_reply_to) else {
                return false;
            };
            let content = MsgNoRel::text_plain(body);
            tl.send_reply(content, reply_to.to_owned()).await.is_ok()
        })
    }

    pub fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            use matrix_sdk::room::edit::EditedContent;
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(eid) = EventId::parse(&target_event_id) else {
                return false;
            };
            let Some(item) = tl.item_by_event_id(&eid).await else {
                return false;
            };
            let item_id = item.identifier();
            let edited = EditedContent::RoomMessage(MsgNoRel::text_plain(new_body));

            tl.edit(&item_id, edited).await.is_ok()
        })
    }

    pub fn paginate_backwards(&self, room_id: String, count: u16) -> bool {
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            tl.paginate_backwards(count).await.unwrap_or(false)
        })
    }

    pub fn paginate_forwards(&self, room_id: String, count: u16) -> bool {
        with_timeline_async!(self, room_id, |tl: Timeline, _rid| async move {
            tl.paginate_forwards(count).await.unwrap_or(false)
        })
    }

    pub fn redact(&self, room_id: String, event_id: String, reason: Option<String>) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            let Ok(eid) = EventId::parse(&event_id) else {
                return false;
            };
            room.redact(&eid, reason.as_deref(), None).await.is_ok()
        })
    }

    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) -> u64 {
        let client = self.inner.clone();
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn TypingObserver> = Arc::from(observer);
        let id = self.next_sub_id();

        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&rid) else {
                return;
            };
            // Keep the guard alive here.
            let (_guard, mut rx) = room.subscribe_to_typing_notifications();

            let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
            let mut last: Vec<String> = Vec::new();

            while let Ok(user_ids) = rx.recv().await {
                let mut names = Vec::with_capacity(user_ids.len());
                for uid in user_ids {
                    if let Some(n) = cache.get(&uid) {
                        names.push(n.clone());
                        continue;
                    }
                    let name = match room.get_member(&uid).await {
                        Ok(Some(m)) => m
                            .display_name()
                            .map(|s| s.to_string())
                            .unwrap_or_else(|| uid.localpart().to_string()),
                        _ => uid.localpart().to_string(),
                    };
                    cache.insert(uid.clone(), name.clone());
                    names.push(name);
                }
                names.sort();
                names.dedup();
                if names != last {
                    last = names.clone();
                    let _ = std::panic::catch_unwind(AssertUnwindSafe(|| obs.on_update(names)));
                }
            }
        });

        self.typing_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_typing(&self, sub_id: u64) -> bool {
        unsub!(self, typing_subs, sub_id)
    }
    pub fn observe_receipts(&self, room_id: String, observer: Box<dyn ReceiptsObserver>) -> u64 {
        let client = self.inner.clone();
        let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: std::sync::Arc<dyn ReceiptsObserver> = std::sync::Arc::from(observer);
        let id = self.next_sub_id();

        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&rid) else {
                return;
            };
            let Ok(tl) = room.timeline().await else {
                return;
            };
            let mut stream = tl.subscribe_own_user_read_receipts_changed().await;
            use futures_util::StreamExt;
            while let Some(()) = stream.next().await {
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| obs.on_changed()));
            }
        });
        self.receipts_subs.lock().unwrap().insert(id, h);
        id
    }

    pub fn unobserve_receipts(&self, sub_id: u64) -> bool {
        unsub!(self, receipts_subs, sub_id)
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
        unsub!(self, call_subs, token)
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
        _filename: Option<String>,
        progress: Option<Box<dyn ProgressObserver>>,
    ) -> bool {
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
            use mime::APPLICATION_OCTET_STREAM;

            let mxc: OwnedMxcUri = mxc_uri.into();
            let req = MediaRequestParameters {
                source: MediaSource::Plain(mxc),
                format: MediaFormat::File,
            };

            let handle = self
                .inner
                .media()
                .get_media_file(&req, None, &APPLICATION_OCTET_STREAM, true, None)
                .await
                .map_err(|e| FfiError::Msg(format!("download: {e}")))?;

            // progress (can stat the temp file)
            if let Some(p) = progress.as_ref() {
                if let Ok(md) = std::fs::metadata(handle.path()) {
                    p.on_progress(md.len(), Some(md.len()));
                }
            }

            handle
                .persist(Path::new(&save_path))
                .map_err(|e| FfiError::Msg(format!("persist: {e}")))?;

            let bytes = std::fs::metadata(&save_path).map(|m| m.len()).unwrap_or(0);
            Ok(DownloadResult {
                path: save_path,
                bytes,
            })
        })
    }

    pub fn start_supervised_sync(&self, observer: Box<dyn SyncObserver>) {
        let obs: Arc<dyn SyncObserver> = Arc::from(observer);
        let svc_slot = self.sync_service.clone();
        let h = RT.spawn(async move {
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

            let Ok(user_devs) = self.inner.encryption().get_user_devices(me).await else {
                return vec![];
            };

            user_devs
                .devices()
                .map(|dev| {
                    use matrix_sdk_crypto::LocalTrust;

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
                        verified: dev.is_verified(),
                    }
                })
                .collect()
        })
    }

    pub fn start_self_sas(
        &self,
        device_id: String,
        observer: Box<dyn VerificationObserver>,
    ) -> String {
        let obs: Arc<dyn VerificationObserver> = Arc::from(observer);
        RT.block_on(async {
            info!("start_self_sas: device_id={}", device_id);

            let Some(me) = self.inner.user_id() else {
                warn!("start_self_sas: no user");
                obs.on_error("".into(), "No user".into());
                return "".into();
            };

            // Ensure crypto is fully initialised
            self.inner
                .encryption()
                .wait_for_e2ee_initialization_tasks()
                .await;

            let Ok(devices) = self.inner.encryption().get_user_devices(me).await else {
                warn!("start_self_sas: Devices unavailable");
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
                warn!("start_self_sas: device not found");
                obs.on_error("".into(), "Device not found".into());
                return "".into();
            };

            match dev.request_verification().await {
                Ok(req) => {
                    let flow_id = req.flow_id().to_string();
                    info!(
                        "start_self_sas: got VerificationRequest flow_id={}",
                        flow_id
                    );
                    self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                    flow_id
                }
                Err(e) => {
                    error!("start_self_sas: request_verification failed: {e}");
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
            info!("start_user_sas: user_id={}", user_id);

            let Ok(uid) = user_id.parse::<OwnedUserId>() else {
                warn!("start_user_sas: bad user id");
                obs.on_error("".into(), "Bad user id".into());
                return "".into();
            };

            self.inner
                .encryption()
                .wait_for_e2ee_initialization_tasks()
                .await;

            match self.inner.encryption().request_user_identity(&uid).await {
                Ok(Some(identity)) => match identity.request_verification().await {
                    Ok(req) => {
                        let flow_id = req.flow_id().to_string();
                        info!(
                            "start_user_sas: got VerificationRequest flow_id={}",
                            flow_id
                        );
                        self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                        flow_id
                    }
                    Err(e) => {
                        error!("start_user_sas: request_verification failed: {e}");
                        obs.on_error("".into(), e.to_string());
                        "".into()
                    }
                },
                Ok(None) => {
                    warn!("start_user_sas: user has no cross-signing identity");
                    obs.on_error("".into(), "User has no cross‑signing identity".into());
                    "".into()
                }
                Err(e) => {
                    error!("start_user_sas: Identity fetch failed: {e}");
                    obs.on_error("".into(), format!("Identity fetch failed: {e}"));
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
            info!(
                "accept_verification: flow_id={:?}, other_user_id={:?}",
                flow_id, other_user_id
            );

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
                warn!(
                    "accept_verification: could not resolve user for flow_id={}",
                    flow_id
                );
                return false;
            };
            info!("accept_verification: resolved user={}", user);

            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                info!("accept_verification: found existing SAS in verifs, calling accept()");
                return f.sas.accept().await.is_ok();
            }

            if let Some(req) = self
                .inner
                .encryption()
                .get_verification_request(&user, &flow_id)
                .await
            {
                info!("accept_verification: found VerificationRequest, accepting and starting sas");
                if req.accept().await.is_err() {
                    warn!("accept_verification: req.accept() failed");
                    return false;
                }
                self.wait_and_start_sas(flow_id.clone(), req, obs.clone());
                return true;
            }

            if let Some(verification) = self
                .inner
                .encryption()
                .get_verification(&user, &flow_id)
                .await
            {
                info!("accept_verification: found Verification, trying sas().accept()");
                if let Some(sas) = verification.clone().sas() {
                    return sas.accept().await.is_ok();
                }
                for _ in 0..5 {
                    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
                    if let Some(sas) = verification.clone().sas() {
                        info!("accept_verification: sas became available, accepting");
                        return sas.accept().await.is_ok();
                    }
                }
                warn!("accept_verification: sas() never became available");
            } else {
                warn!(
                    "accept_verification: no Verification found for user={} flow_id={}",
                    user, flow_id
                );
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
        self.inner.session_meta().is_some()
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

    pub fn list_invited(&self) -> Result<Vec<RoomProfile>, FfiError> {
        RT.block_on(async {
            let rooms = self.inner.invited_rooms();

            let mut out = Vec::with_capacity(rooms.len());
            for room in rooms {
                let rid = room.room_id().to_owned();

                let name = room
                    .display_name()
                    .await
                    .map(|d| d.to_string())
                    .unwrap_or_else(|_| rid.to_string());

                let topic = room.topic();
                let member_count = room.active_members_count();

                let is_dm = room.is_direct().await.unwrap_or(false);
                let is_encrypted = room
                    .latest_encryption_state()
                    .await
                    .map(|s| s.is_encrypted())
                    .unwrap_or(false);

                out.push(RoomProfile {
                    room_id: rid.to_string(),
                    name,
                    topic,
                    member_count,
                    is_encrypted,
                    is_dm,
                });
            }
            Ok(out)
        })
    }

    // Accept an invite by room ID
    pub fn accept_invite(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return false;
            };
            // Join-by-id is the canonical accept for invites
            self.inner.join_room_by_id(&rid).await.is_ok()
        })
    }

    // Decline an invite (leave)
    pub fn leave_room(&self, room_id: String) -> Result<(), FfiError> {
        RT.block_on(async {
            let rid =
                ruma::OwnedRoomId::try_from(room_id).map_err(|e| FfiError::Msg(e.to_string()))?;
            let room = self
                .inner
                .get_room(&rid)
                .ok_or_else(|| FfiError::Msg("room not found".into()))?;

            room.leave().await.map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    // Create a private room; optional name/topic; optional invitees.
    // Returns the new roomId on success.
    pub fn create_room(
        &self,
        name: Option<String>,
        topic: Option<String>,
        invitees: Vec<String>,
    ) -> Result<String, FfiError> {
        RT.block_on(async {
            // Build create room request (private visibility)
            let mut req = create_room_v3::Request::new();
            req.preset = Some(RoomPreset::PrivateChat.into());
            req.visibility = Visibility::Private;
            if let Some(n) = &name {
                req.name = Some(n.clone());
            }
            if let Some(t) = &topic {
                req.topic = Some(t.clone());
            }
            if !invitees.is_empty() {
                let parsed = invitees
                    .into_iter()
                    .map(|u| u.parse())
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|e: ruma::IdParseError| FfiError::Msg(e.to_string()))?;
                req.invite = parsed;
            }

            // NOTE: If you want encryption-by-default, see small fix below.
            let resp = self
                .inner
                .send(req)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(resp.room_id.to_string())
        })
    }

    // Set room name (state event)
    pub fn set_room_name(&self, room_id: String, name: String) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            room.send_state_event(RoomNameEventContent::new(name))
                .await
                .is_ok()
        })
    }

    // Set room topic
    pub fn set_room_topic(&self, room_id: String, topic: String) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            room.send_state_event(RoomTopicEventContent::new(topic))
                .await
                .is_ok()
        })
    }

    pub fn room_profile(&self, room_id: String) -> Result<RoomProfile, FfiError> {
        RT.block_on(async {
            use matrix_sdk_base::RoomMemberships;

            let rid =
                ruma::OwnedRoomId::try_from(room_id).map_err(|e| FfiError::Msg(e.to_string()))?;
            let room = self
                .inner
                .get_room(&rid)
                .ok_or_else(|| FfiError::Msg("room not found".into()))?;

            let name = room
                .display_name()
                .await
                .map(|d| d.to_string())
                .unwrap_or_else(|_| rid.to_string());

            let topic = room.topic();

            let member_count = room.joined_members_count();

            let is_encrypted = room
                .latest_encryption_state()
                .await
                .map(|s| s.is_encrypted())
                .unwrap_or(false);

            let is_dm = match room.is_direct().await {
                Ok(b) => b,
                Err(_) => {
                    if let (Some(me), Ok(members)) = (
                        self.inner.user_id(),
                        room.members(RoomMemberships::ACTIVE).await,
                    ) {
                        let others = members.into_iter().filter(|m| m.user_id() != me).count();
                        others == 1
                    } else {
                        false
                    }
                }
            };

            Ok(RoomProfile {
                room_id: rid.to_string(),
                name,
                topic,
                member_count,
                is_encrypted,
                is_dm,
            })
        })
    }

    pub fn room_notification_mode(&self, room_id: String) -> Option<FfiRoomNotificationMode> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };

            let mode = room.notification_mode().await?;

            Some(match mode {
                RsMode::AllMessages => FfiRoomNotificationMode::AllMessages,
                RsMode::MentionsAndKeywordsOnly => FfiRoomNotificationMode::MentionsAndKeywordsOnly,
                RsMode::Mute => FfiRoomNotificationMode::Mute,
            })
        })
    }

    pub fn set_room_notification_mode(
        &self,
        room_id: String,
        mode: FfiRoomNotificationMode,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };

            let sdk_mode = match mode {
                FfiRoomNotificationMode::AllMessages => RsMode::AllMessages,
                FfiRoomNotificationMode::MentionsAndKeywordsOnly => RsMode::MentionsAndKeywordsOnly,
                FfiRoomNotificationMode::Mute => RsMode::Mute,
            };

            self.inner
                .notification_settings()
                .await
                .set_room_notification_mode(rid.as_ref(), sdk_mode)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn list_members(&self, room_id: String) -> Result<Vec<MemberSummary>, FfiError> {
        RT.block_on(async {
            use matrix_sdk_base::RoomMemberships;

            let rid =
                ruma::OwnedRoomId::try_from(room_id).map_err(|e| FfiError::Msg(e.to_string()))?;
            let room = self
                .inner
                .get_room(&rid)
                .ok_or_else(|| FfiError::Msg("room not found".into()))?;

            let me = self.inner.user_id();

            let members = room
                .members(RoomMemberships::ACTIVE)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let out: Vec<MemberSummary> = members
                .into_iter()
                .map(|m| MemberSummary {
                    user_id: m.user_id().to_string(),
                    display_name: m.display_name().map(|n| n.to_string()),
                    is_me: me.map(|u| u == m.user_id()).unwrap_or(false),
                    membership: m.membership().to_string(),
                })
                .collect();

            Ok(out)
        })
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

    /// Deprecated, remove after fixing push notifs for android (causes older parts to be used (legacy sync, which causes errors on the current synapse server))
    #[warn(deprecated)]
    pub fn wake_sync_once(&self, timeout_ms: u32) -> bool {
        RT.block_on(async {
            let settings =
                SyncSettings::default().timeout(Duration::from_millis(timeout_ms as u64));
            self.inner.sync_once(settings).await.is_ok()
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
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
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
            let Some(me) = self.inner.user_id() else {
                return OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                };
            };

            if let Some((eid, receipt)) = tl.latest_user_read_receipt(me).await {
                let ts = receipt.ts.map(|t| t.0.into());
                OwnReceipt {
                    event_id: Some(eid.to_string()),
                    ts_ms: ts,
                }
            } else {
                OwnReceipt {
                    event_id: None,
                    ts_ms: None,
                }
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
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(eid) = ruma::OwnedEventId::try_from(event_id) else {
                return false;
            };
            if let Some(room) = self.inner.get_room(&rid) {
                let receipts = matrix_sdk::room::Receipts::new()
                    .private_read_receipt(eid.clone())
                    .fully_read_marker(eid);
                room.send_multiple_receipts(receipts).await.is_ok()
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
        let obs: std::sync::Arc<dyn RoomListObserver> = std::sync::Arc::from(observer);
        let svc_slot = self.sync_service.clone();
        let id = self.next_sub_id();

        let (cmd_tx, mut cmd_rx) = tokio::sync::mpsc::unbounded_channel::<RoomListCmd>();
        self.room_list_cmds.lock().unwrap().insert(id, cmd_tx);

        let h = RT.spawn(async move {
            let svc = loop {
                if let Some(s) = { svc_slot.lock().unwrap().as_ref().cloned() } {
                    break s;
                }
                tokio::time::sleep(std::time::Duration::from_millis(200)).await;
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

            controller.set_filter(Box::new(filters::new_filter_non_left()));

            // Maintain local ordered state
            let mut rooms = Vector::<matrix_sdk::Room>::new();

            loop {
                tokio::select! {
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
                    Some(diffs) = stream.next() => {
                        let mut changed = false;

                        for diff in diffs {
                            match diff {
                                VectorDiff::Reset { values } => {
                                    rooms = values;
                                    changed = true;
                                }
                                VectorDiff::Clear => {
                                    rooms.clear();
                                    changed = true;
                                }
                                VectorDiff::PushFront { value } => {
                                    rooms.insert(0, value);
                                    changed = true;
                                }
                                VectorDiff::PushBack { value } => {
                                    rooms.push_back(value);
                                    changed = true;
                                }
                                VectorDiff::PopFront => {
                                    if !rooms.is_empty() {
                                        rooms.remove(0);
                                        changed = true;
                                    }
                                }
                                VectorDiff::PopBack => {
                                    rooms.pop_back();
                                    changed = true;
                                }
                                VectorDiff::Insert { index, value } => {
                                    let idx = index as usize;
                                    if idx <= rooms.len() {
                                        rooms.insert(idx, value);
                                        changed = true;
                                    }
                                }
                                VectorDiff::Set { index, value } => {
                                    let idx = index as usize;
                                    if idx < rooms.len() {
                                        rooms[idx] = value;
                                        changed = true;
                                    }
                                }
                                VectorDiff::Remove { index } => {
                                    let idx = index as usize;
                                    if idx < rooms.len() {
                                        rooms.remove(idx);
                                        changed = true;
                                    }
                                }
                                VectorDiff::Truncate { length } => {
                                    rooms.truncate(length as usize);
                                    changed = true;
                                }
                                VectorDiff::Append {values} => {
                                    rooms.append(values);
                                    changed = true;
                                }
                            }
                        }

                        if changed {
                            let snapshot: Vec<RoomListEntry> = rooms
                            .iter()
                            .map(|room| {
                                let notifications = room.num_unread_notifications();
                                let messages = room.num_unread_messages();
                                let mentions = room.num_unread_mentions();
                                let marked_unread = room.is_marked_unread();

                                RoomListEntry {
                                    room_id: room.room_id().to_string(),
                                    name: room
                                        .cached_display_name()
                                        .map(|n| n.to_string())
                                        .unwrap_or_else(|| room.room_id().to_string()),
                                    last_ts: 0,                   // TODO: use recency_stamp
                                    notifications,
                                    messages,
                                    mentions,
                                    marked_unread,
                                }
                            })
                            .collect();
                            let obs_clone = obs.clone();
                            let _ = std::panic::catch_unwind(AssertUnwindSafe(move || {
                                obs_clone.on_reset(snapshot);
                            }));
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
        unsub!(self, room_list_subs, token)
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
            .as_deref()
            .map(sanitize)
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "file.bin".into());
        let out = dir.join(format!("dl_{}_{}", now_ms(), hint));

        RT.block_on(async {
            use mime::APPLICATION_OCTET_STREAM;

            let mxc: OwnedMxcUri = mxc_uri.into();
            let req = MediaRequestParameters {
                source: MediaSource::Plain(mxc),
                format: MediaFormat::File,
            };

            let handle = self
                .inner
                .media()
                .get_media_file(&req, None, &APPLICATION_OCTET_STREAM, true, None)
                .await
                .map_err(|e| FfiError::Msg(format!("download: {e}")))?;

            handle
                .persist(&out)
                .map_err(|e| FfiError::Msg(format!("persist: {e}")))?;

            let bytes = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
            Ok(DownloadResult {
                path: out.to_string_lossy().to_string(),
                bytes,
            })
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

            Ok(())
        })
    }

    /// Return reactions (emoji -> count, me).
    pub fn reactions_for_event(&self, room_id: String, event_id: String) -> Vec<ReactionSummary> {
        RT.block_on(async {
            let rid = match ruma::OwnedRoomId::try_from(room_id) {
                Ok(v) => v,
                Err(_) => return vec![],
            };
            let eid = match ruma::OwnedEventId::try_from(event_id) {
                Ok(v) => v,
                Err(_) => return vec![],
            };

            let Some(tl) = get_timeline_for(&self.inner, &rid).await else {
                return vec![];
            };
            let Some(item) = tl.item_by_event_id(&eid).await else {
                return vec![];
            };

            let me = self.inner.user_id();
            let mut out = Vec::new();

            if let Some(reactions) = item.content().reactions() {
                for (key, by_sender) in reactions.iter() {
                    let count = by_sender.len() as u32;
                    let me_reacted = me.map(|u| by_sender.contains_key(u)).unwrap_or(false);
                    out.push(ReactionSummary {
                        key: key.to_string(),
                        count,
                        me: me_reacted,
                    });
                }
            }

            out
        })
    }

    pub fn room_tags(&self, room_id: String) -> Option<RoomTags> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };
            Some(RoomTags {
                is_favourite: room.is_favourite(),
                is_low_priority: room.is_low_priority(),
            })
        })
    }

    pub fn set_room_favourite(&self, room_id: String, fav: bool) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            room.set_is_favourite(fav, None).await.is_ok()
        })
    }

    pub fn set_room_low_priority(&self, room_id: String, low: bool) -> bool {
        with_room_async!(self, room_id, |room: Room, _rid| async move {
            room.set_is_low_priority(low, None).await.is_ok()
        })
    }

    /// MSC3440
    pub fn send_thread_text(
        &self,
        room_id: String,
        root_event_id: String,
        body: String,
        reply_to_event_id: Option<String>,
    ) -> bool {
        RT.block_on(async {
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return false;
            };
            let Ok(root) = ruma::OwnedEventId::try_from(root_event_id) else {
                return false;
            };
            let Some(tl) = get_timeline_for(&self.inner, &rid).await else {
                return false;
            };

            let mut content: RoomMessageEventContent = RoomMessageEventContent::text_plain(body);

            let relation = if let Some(reply_to) = reply_to_event_id {
                if let Ok(eid) = ruma::OwnedEventId::try_from(reply_to) {
                    MsgRelation::Thread(ThreadRel::reply(root, eid))
                } else {
                    MsgRelation::Thread(ThreadRel::without_fallback(root))
                }
            } else {
                MsgRelation::Thread(ThreadRel::without_fallback(root))
            };

            content.relates_to = Some(relation);
            tl.send(content.into()).await.is_ok()
        })
    }

    pub fn thread_replies(
        &self,
        room_id: String,
        root_event_id: String,
        from: Option<String>,
        limit: u32,
        direction_forward: bool,
    ) -> Result<ThreadPage, FfiError> {
        RT.block_on(async {
            let rid = ruma::OwnedRoomId::try_from(room_id.clone())
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            let root = ruma::OwnedEventId::try_from(root_event_id.clone())
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let mut req = get_relating::v1::Request::new(
                rid.clone(),
                root.clone(),
                RelationType::Thread,
                TimelineEventType::RoomMessage,
            );
            if let Some(f) = from.as_deref() {
                req.from = Some(f.to_owned());
            }
            if limit > 0 {
                req.limit = Some(limit.into());
            }
            req.dir = if direction_forward {
                Direction::Forward
            } else {
                Direction::Backward
            };

            let resp = self
                .inner
                .send(req)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let mut out: Vec<MessageEvent> = Vec::new();

            // Include the root first (mapped via timeline for consistent formatting)
            if let Some(root_ev) = map_event_id_via_timeline(&self.inner, &rid, &root).await {
                out.push(root_ev);
            }

            // Each chunk item is Raw<AnyMessageLikeEvent>; deserialize, take event_id, then map via timeline
            for raw in resp.chunk.iter() {
                if let Ok(ml) = raw.deserialize() {
                    let eid = ml.event_id().to_owned();
                    if let Some(mev) = map_event_id_via_timeline(&self.inner, &rid, &eid).await {
                        out.push(mev);
                    }
                }
            }

            // Chronological order (ascending by timestamp)
            out.sort_by_key(|e| e.timestamp_ms);

            Ok(ThreadPage {
                root_event_id: root_event_id,
                room_id: room_id,
                messages: out,
                next_batch: resp.next_batch.clone(),
                prev_batch: resp.prev_batch.clone(),
            })
        })
    }

    /// Approximate thread summary: count + latest timestamp by paging relations.
    pub fn thread_summary(
        &self,
        room_id: String,
        root_event_id: String,
        per_page: u32,
        max_pages: u32,
    ) -> Result<ThreadSummary, FfiError> {
        RT.block_on(async {
            let rid = ruma::OwnedRoomId::try_from(room_id.clone())
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            let root = ruma::OwnedEventId::try_from(root_event_id.clone())
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let mut from: Option<String> = None;
            let mut pages = 0u32;
            let mut count: u64 = 0;
            let mut latest: Option<u64> = None;

            loop {
                pages += 1;
                if pages > max_pages.max(1) {
                    break;
                }

                let mut req = get_relating::v1::Request::new(
                    rid.clone(),
                    root.clone(),
                    RelationType::Thread,
                    TimelineEventType::RoomMessage,
                );
                req.dir = Direction::Backward; // newer first
                if let Some(f) = &from {
                    req.from = Some(f.clone());
                }
                if per_page > 0 {
                    req.limit = Some(per_page.into());
                }

                let resp = self
                    .inner
                    .send(req)
                    .await
                    .map_err(|e| FfiError::Msg(e.to_string()))?;

                for raw in resp.chunk.iter() {
                    if let Ok(ml) = raw.deserialize() {
                        let eid = ml.event_id().to_owned();
                        count += 1;
                        if let Some(mev) = map_event_id_via_timeline(&self.inner, &rid, &eid).await
                        {
                            if latest.map_or(true, |l| mev.timestamp_ms > l) {
                                latest = Some(mev.timestamp_ms);
                            }
                        }
                    }
                }

                if resp.next_batch.is_none() {
                    break;
                }
                from = resp.next_batch;
            }

            Ok(ThreadSummary {
                root_event_id,
                room_id,
                count,
                latest_ts_ms: latest,
            })
        })
    }

    /// Return true if the room is a Space (m.space).
    pub fn is_space(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(rid) = ruma::OwnedRoomId::try_from(room_id) else {
                return false;
            };
            self.inner
                .get_room(&rid)
                .map(|r| r.is_space())
                .unwrap_or(false)
        })
    }

    /// List all joined spaces with basic profile info.
    pub fn my_spaces(&self) -> Vec<SpaceInfo> {
        RT.block_on(async {
            let mut out = Vec::new();

            for room in self.inner.joined_space_rooms() {
                let rid = room.room_id().to_owned();

                let name = room
                    .display_name()
                    .await
                    .map(|d| d.to_string())
                    .unwrap_or_else(|_| rid.to_string());

                let topic = room.topic();
                let member_count = room.joined_members_count();

                let is_encrypted = matches!(
                    room.encryption_state(),
                    matrix_sdk::EncryptionState::Encrypted
                );

                // Heuristic/publicity helper the SDK provides (may be None if state missing)
                let is_public = room.is_public().unwrap_or(false);

                out.push(SpaceInfo {
                    room_id: rid.to_string(),
                    name,
                    topic,
                    member_count,
                    is_encrypted,
                    is_public,
                });
            }

            out
        })
    }

    /// Create a space (m.space). Returns the new space room_id on success.
    pub fn create_space(
        &self,
        name: String,
        topic: Option<String>,
        is_public: bool,
        invitees: Vec<String>,
    ) -> Result<String, FfiError> {
        RT.block_on(async {
            use ruma::{
                api::client::room::{Visibility, create_room::v3 as create_room_v3},
                serde::Raw,
            };

            let mut req = create_room_v3::Request::new();

            // Set m.space via CreationContent.room_type
            let mut cc = create_room_v3::CreationContent::new();
            cc.room_type = Some(RoomType::Space);
            req.creation_content = Some(Raw::new(&cc).map_err(|e| FfiError::Msg(e.to_string()))?);

            req.name = Some(name);
            req.topic = topic;
            req.visibility = if is_public {
                Visibility::Public
            } else {
                Visibility::Private
            };
            req.preset = Some(if is_public {
                create_room_v3::RoomPreset::PublicChat
            } else {
                create_room_v3::RoomPreset::PrivateChat
            });

            if !invitees.is_empty() {
                let parsed = invitees
                    .into_iter()
                    .map(|u| u.parse())
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|e: ruma::IdParseError| FfiError::Msg(e.to_string()))?;
                req.invite = parsed;
            }

            // Using Client::send so we can return the room_id from the response
            let resp = self
                .inner
                .send(req)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(resp.room_id.to_string())
        })
    }

    /// Add a child (room or subspace) to a space via m.space.child.
    pub fn space_add_child(
        &self,
        space_id: String,
        child_room_id: String,
        order: Option<String>,
        suggested: Option<bool>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            use ruma::{
                OwnedRoomId, OwnedServerName, events::space::child::SpaceChildEventContent,
            };

            // Parse room IDs
            let rid_space = OwnedRoomId::try_from(space_id.as_str())
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            let rid_child = OwnedRoomId::try_from(child_room_id.as_str())
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let room = self
                .inner
                .get_room(&rid_space)
                .ok_or_else(|| FfiError::Msg("space not found".into()))?;

            // Build via list from child's server
            let via: Vec<OwnedServerName> = rid_child
                .server_name()
                .map(|s| s.to_owned())
                .into_iter()
                .collect();

            let mut content = SpaceChildEventContent::new(via);

            if let Some(o) = order {
                let ord = <&SpaceChildOrder>::try_from(o.as_str())
                    .map_err(|e| FfiError::Msg(format!("Invalid order string: {}", e)))?
                    .to_owned();
                content.order = Some(ord);
            }

            content.suggested = suggested.unwrap_or(false);

            room.send_state_event_for_key(&rid_child, content)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            Ok(())
        })
    }

    /// Remove a child from a space by sending an empty content state for that key.
    pub fn space_remove_child(
        &self,
        space_id: String,
        child_room_id: String,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            use ruma::OwnedRoomId;
            use serde_json::json;

            let rid_space = OwnedRoomId::try_from(space_id.as_str())
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            let room = self
                .inner
                .get_room(&rid_space)
                .ok_or_else(|| FfiError::Msg("space not found".into()))?;

            // Return type here is Response; we ignore it and return ()
            room.send_state_event_raw("m.space.child", child_room_id.as_str(), json!({}))
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            Ok(())
        })
    }

    /// Traverse a space with the server-side hierarchy API (MSC2946).
    pub fn space_hierarchy(
        &self,
        space_id: String,
        from: Option<String>,
        limit: u32,
        max_depth: Option<u32>,
        suggested_only: bool,
    ) -> Result<SpaceHierarchyPage, FfiError> {
        RT.block_on(async {
            use ruma::{OwnedRoomId, api::client::space::get_hierarchy::v1 as space_hierarchy_v1};

            let rid_space = OwnedRoomId::try_from(space_id.as_str())
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let mut req = space_hierarchy_v1::Request::new(rid_space);
            req.from = from;
            if limit > 0 {
                req.limit = Some(limit.into());
            }
            req.max_depth = max_depth.map(Into::into);
            req.suggested_only = suggested_only; // bool

            let resp = self
                .inner
                .send(req)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            // Fields are on chunk.summary (not on the chunk itself)
            let children = resp
                .rooms
                .into_iter()
                .map(|chunk| {
                    let s = chunk.summary;
                    let is_space = matches!(s.room_type, Some(RoomType::Space));
                    SpaceChildInfo {
                        room_id: s.room_id.to_string(),
                        name: s.name,
                        topic: s.topic,
                        alias: s.canonical_alias.map(|a| a.to_string()),
                        avatar_url: s.avatar_url.map(|m| m.to_string()),
                        is_space,
                        member_count: s.num_joined_members.into(),
                        world_readable: s.world_readable,
                        guest_can_join: s.guest_can_join,
                        // Not present on summary; use false by default
                        suggested: false,
                    }
                })
                .collect();

            Ok(SpaceHierarchyPage {
                children,
                next_batch: resp.next_batch,
            })
        })
    }

    /// Invite a user to a space.
    pub fn space_invite_user(&self, space_id: String, user_id: String) -> bool {
        with_room_async!(self, space_id, |room: Room, _rid| async move {
            let Ok(uid) = ruma::OwnedUserId::try_from(user_id) else {
                return false;
            };
            room.invite_user_by_id(&uid).await.is_ok()
        })
    }

    /// Send a new poll (MSC3381, unstable `m.poll.start`).
    /// Returns the event ID if sending succeeds.
    pub fn send_poll_start(
        &self,
        room_id: String,
        def: PollDefinition,
    ) -> Result<String, FfiError> {
        use matrix_sdk::ruma::events::AnyMessageLikeEventContent;

        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let content = build_unstable_poll_content(&def)?;
            let any = AnyMessageLikeEventContent::UnstablePollStart(content.into());

            let send_res = room
                .send(any)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(send_res.event_id.to_string())
        })
    }

    /// Send a poll response for a given poll event.
    /// `answers` are the answer IDs ("a", "b", "c"...), not the labels.
    pub fn send_poll_response(
        &self,
        room_id: String,
        poll_event_id: String,
        answers: Vec<String>,
    ) -> Result<(), FfiError> {
        use matrix_sdk::ruma::events::AnyMessageLikeEventContent;

        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Ok(eid) = EventId::parse(&poll_event_id) else {
                return Err(FfiError::Msg("bad poll event id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let content = UnstablePollResponseEventContent::new(answers, eid.to_owned());
            let any = AnyMessageLikeEventContent::UnstablePollResponse(content);

            room.send(any)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(())
        })
    }

    /// End a poll (MSC3381, unstable `org.matrix.msc3381.poll.end`).
    ///
    /// This just sends an `m.poll.end` (unstable) event linked to the given poll
    /// start event. It does *not* compute or embed per‑option results.
    pub fn send_poll_end(&self, room_id: String, poll_event_id: String) -> Result<(), FfiError> {
        use matrix_sdk::ruma::{EventId, events::AnyMessageLikeEventContent};

        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Ok(poll_eid) = EventId::parse(&poll_event_id) else {
                return Err(FfiError::Msg("bad poll event id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            // Minimal end, only fallback string.
            let end_content = UnstablePollEndEventContent::new("Poll ended", poll_eid);

            let any = AnyMessageLikeEventContent::UnstablePollEnd(end_content);

            room.send(any)
                .await
                .map(|_| ())
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Start sharing live location in a room for `duration_ms` milliseconds.
    pub fn start_live_location(
        &self,
        room_id: String,
        duration_ms: u64,
        description: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            room.start_live_location_share(duration_ms, description)
                .await
                .map(|_| ())
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Stop our live location share (if any) in the room.
    pub fn stop_live_location(&self, room_id: String) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            room.stop_live_location_share()
                .await
                .map(|_| ())
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Send a single live location beacon update (geo:`geo:` URI) in the room.
    pub fn send_live_location(&self, room_id: String, geo_uri: String) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            room.send_location_beacon(geo_uri)
                .await
                .map(|_| ())
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Subscribe to other users' live location shares in a room.
    pub fn observe_live_location(
        &self,
        room_id: String,
        observer: Box<dyn LiveLocationObserver>,
    ) -> u64 {
        let client = self.inner.clone();
        let Ok(rid) = OwnedRoomId::try_from(room_id) else {
            return 0;
        };
        let obs: Arc<dyn LiveLocationObserver> = Arc::from(observer);

        sub_manager!(self, live_location_subs, async move {
            let Some(room) = client.get_room(&rid) else {
                return;
            };
            let observable = room.observe_live_location_shares();
            let mut stream = observable.subscribe();

            use futures_util::{StreamExt, pin_mut};

            pin_mut!(stream);

            while let Some(event) = stream.next().await {
                let Some(beacon_info) = event.beacon_info else {
                    continue;
                };
                let info = LiveLocationShareInfo {
                    user_id: event.user_id.to_string(),
                    geo_uri: event.last_location.location.uri.to_string(),
                    ts_ms: event.last_location.ts.0.into(),
                    is_live: beacon_info.is_live(),
                };
                let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
                    obs.on_update(vec![info.clone()])
                }));
            }
        })
    }

    pub fn unobserve_live_location(&self, sub_id: u64) -> bool {
        unsub!(self, live_location_subs, sub_id)
    }

    pub fn publish_room_alias(&self, room_id: String, alias: String) -> Result<bool, FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id.as_str()) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let alias_id =
                OwnedRoomAliasId::try_from(alias).map_err(|e| FfiError::Msg(e.to_string()))?;

            room.privacy_settings()
                .publish_room_alias_in_room_directory(alias_id.as_ref())
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn unpublish_room_alias(&self, room_id: String, alias: String) -> Result<bool, FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id.as_str()) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let alias_id =
                OwnedRoomAliasId::try_from(alias).map_err(|e| FfiError::Msg(e.to_string()))?;

            room.privacy_settings()
                .remove_room_alias_from_room_directory(alias_id.as_ref())
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn set_room_canonical_alias(
        &self,
        room_id: String,
        alias: Option<String>,
        alt_aliases: Vec<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id.as_str()) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let alias_opt = if let Some(a) = alias {
                Some(OwnedRoomAliasId::try_from(a).map_err(|e| FfiError::Msg(e.to_string()))?)
            } else {
                None
            };

            let mut alts = Vec::new();
            for s in alt_aliases {
                alts.push(OwnedRoomAliasId::try_from(s).map_err(|e| FfiError::Msg(e.to_string()))?);
            }

            room.privacy_settings()
                .update_canonical_alias(alias_opt, alts)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn set_room_directory_visibility(
        &self,
        room_id: String,
        visibility: RoomDirectoryVisibility,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let vs = match visibility {
                RoomDirectoryVisibility::Public => Visibility::Public,
                RoomDirectoryVisibility::Private => Visibility::Private,
            };

            room.privacy_settings()
                .update_room_visibility(vs)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn room_directory_visibility(
        &self,
        room_id: String,
    ) -> Result<RoomDirectoryVisibility, FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let vis = room
                .privacy_settings()
                .get_room_visibility()
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            Ok(match vis {
                Visibility::Public => RoomDirectoryVisibility::Public,
                Visibility::Private => RoomDirectoryVisibility::Private,
                _ => RoomDirectoryVisibility::Private,
            })
        })
    }

    /// Add a user to the ignore list (muting them across all rooms).
    pub fn ignore_user(&self, user_id: String) -> Result<(), FfiError> {
        RT.block_on(async {
            let uid = user_id
                .parse::<OwnedUserId>()
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            self.inner
                .account()
                .ignore_user(uid.as_ref())
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Remove a user from the ignore list.
    pub fn unignore_user(&self, user_id: String) -> Result<(), FfiError> {
        RT.block_on(async {
            let uid = user_id
                .parse::<OwnedUserId>()
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            self.inner
                .account()
                .unignore_user(uid.as_ref())
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Check whether a user is currently ignored.
    pub fn is_user_ignored(&self, user_id: String) -> bool {
        RT.block_on(async {
            match user_id.parse::<OwnedUserId>() {
                Ok(uid) => self.inner.is_user_ignored(uid.as_ref()).await,
                Err(_) => false,
            }
        })
    }

    /// Set this account's presence and optional status message.
    pub fn set_presence(
        &self,
        state: Presence,
        status_msg: Option<String>,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Some(me) = self.inner.user_id() else {
                return Err(FfiError::Msg("No logged-in user".into()));
            };

            let presence = match state {
                Presence::Online => PresenceState::Online,
                Presence::Offline => PresenceState::Offline,
                Presence::Unavailable => PresenceState::Unavailable,
            };

            let mut req = set_presence_v3::Request::new(me.to_owned(), presence);
            req.status_msg = status_msg;

            self.inner
                .send(req)
                .await
                .map(|_: set_presence_v3::Response| ())
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn set_room_join_rule(&self, room_id: String, rule: RoomJoinRule) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let join = match rule {
                RoomJoinRule::Public => JoinRule::Public,
                RoomJoinRule::Invite => JoinRule::Invite,
                RoomJoinRule::Knock => JoinRule::Knock,
                RoomJoinRule::Restricted => JoinRule::Restricted(Restricted::default()),
                RoomJoinRule::KnockRestricted => JoinRule::KnockRestricted(Restricted::default()),
            };

            room.privacy_settings()
                .update_join_rule(join)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    pub fn set_room_history_visibility(
        &self,
        room_id: String,
        vis: RoomHistoryVisibility,
    ) -> Result<(), FfiError> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return Err(FfiError::Msg("bad room id".into()));
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return Err(FfiError::Msg("room not found".into()));
            };

            let hv = match vis {
                RoomHistoryVisibility::Invited => HistoryVisibility::Invited,
                RoomHistoryVisibility::Joined => HistoryVisibility::Joined,
                RoomHistoryVisibility::Shared => HistoryVisibility::Shared,
                RoomHistoryVisibility::WorldReadable => HistoryVisibility::WorldReadable,
            };

            room.privacy_settings()
                .update_room_history_visibility(hv)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))
        })
    }

    /// Upgrade a room to a new room version.
    /// `new_version` is e.g. "9", "10", "11".
    /// Returns the new room ID on success.
    pub fn upgrade_room(&self, room_id: String, new_version: String) -> Result<String, FfiError> {
        RT.block_on(async {
            let rid = OwnedRoomId::try_from(room_id).map_err(|e| FfiError::Msg(e.to_string()))?;
            let version = RoomVersionId::try_from(new_version.as_str())
                .map_err(|e| FfiError::Msg(e.to_string()))?;

            let req = upgrade_room_v3::Request::new(rid.clone(), version);
            let resp = self
                .inner
                .send(req)
                .await
                .map_err(|e| FfiError::Msg(e.to_string()))?;
            Ok(resp.replacement_room.to_string())
        })
    }

    /// Get tombstone / predecessor / successor info for a room, if available.
    pub fn room_upgrade_links(&self, room_id: String) -> Option<RoomUpgradeLinks> {
        RT.block_on(async {
            let Ok(rid) = OwnedRoomId::try_from(room_id) else {
                return None;
            };
            let Some(room) = self.inner.get_room(&rid) else {
                return None;
            };

            let is_tombstoned = room.is_tombstoned();
            let successor = room.successor_room().map(Into::into);
            let predecessor = room.predecessor_room().map(Into::into);

            Some(RoomUpgradeLinks {
                is_tombstoned,
                successor,
                predecessor,
            })
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

fn build_unstable_poll_content(
    def: &PollDefinition,
) -> Result<NewUnstablePollStartEventContent, FfiError> {
    // Build answers with simple stable IDs: "a", "b", "c", ...
    let mut answers = Vec::with_capacity(def.answers.len());
    for (idx, text) in def.answers.iter().enumerate() {
        let id = ((b'a' + (idx as u8)) as char).to_string();
        answers.push(UnstablePollAnswer::new(id, text.clone()));
    }

    let unstable_answers =
        UnstablePollAnswers::try_from(answers).map_err(|e| FfiError::Msg(e.to_string()))?;

    let mut block = UnstablePollStartContentBlock::new(def.question.clone(), unstable_answers);

    // Map kind + max_selections
    block.kind = match def.kind {
        PollKind::Disclosed => RumaPollKind::Disclosed,
        PollKind::Undisclosed => RumaPollKind::Undisclosed,
    };
    block.max_selections = js_int::UInt::from(def.max_selections.max(1));

    Ok(NewUnstablePollStartEventContent::plain_text(
        def.question.clone(),
        block,
    ))
}

async fn get_timeline_for(client: &SdkClient, room_id: &OwnedRoomId) -> Option<Timeline> {
    let room = client.get_room(room_id)?;
    room.timeline().await.ok()
}

fn map_timeline_event(
    ev: &EventTimelineItem,
    room_id: &str,
    item_id: Option<&str>,
) -> Option<MessageEvent> {
    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev.event_id().map(|e| e.to_string()).unwrap_or_default();
    let txn_id = ev.transaction_id().map(|t| t.to_string());
    let send_state = match ev.send_state() {
        Some(EventSendState::NotSentYet { .. }) => Some(SendState::Sending),
        Some(EventSendState::SendingFailed { .. }) => Some(SendState::Failed),
        Some(EventSendState::Sent { .. }) => Some(SendState::Sent),
        None => None,
    };

    let item_id_str = item_id
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("{:?}", ev.identifier()));

    let mut reply_to_event_id: Option<String> = None;
    let mut reply_to_sender: Option<String> = None;
    let mut reply_to_body: Option<String> = None;
    let mut attachment: Option<AttachmentInfo> = None;
    let thread_root_event_id = ev.content().thread_root().map(|id| id.to_string());
    let body: String;

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
                attachment = extract_attachment(&msg);
                let raw = msg.body();
                body = if reply_to_event_id.is_some() {
                    strip_reply_fallback(raw)
                } else {
                    render_message_text(&msg)
                };
            } else {
                body = render_msg_like(ev, ml);
            }
        }
        _ => {
            body = render_timeline_text(ev);
        }
    }

    Some(MessageEvent {
        item_id: item_id_str,
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
        thread_root_event_id,
    })
}

fn extract_attachment(msg: &matrix_sdk_ui::timeline::Message) -> Option<AttachmentInfo> {
    use matrix_sdk::ruma::events::room::{MediaSource, message::MessageType as MT};

    // Helper: split a MediaSource into MXC URI and optional EncFile
    fn split_source(source: &MediaSource) -> (String, Option<EncFile>) {
        match source {
            MediaSource::Plain(url) => (url.to_string(), None),
            MediaSource::Encrypted(file) => {
                let url = file.url.to_string();
                let enc = enc_to_record(file.as_ref());
                (url, Some(enc))
            }
        }
    }

    // Helper: same, but for Option<&MediaSource> (used for thumbnails)
    fn split_opt_source(source: Option<&MediaSource>) -> (Option<String>, Option<EncFile>) {
        match source {
            Some(MediaSource::Plain(url)) => (Some(url.to_string()), None),
            Some(MediaSource::Encrypted(file)) => {
                let url = file.url.to_string();
                let enc = enc_to_record(file.as_ref());
                (Some(url), Some(enc))
            }
            None => (None, None),
        }
    }

    match msg.msgtype() {
        MT::Image(c) => {
            // main image source
            let (mxc_uri, encrypted) = split_source(&c.source);

            // metadata + thumbnail
            let (w, h, size, mime, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::Image,
                mxc_uri,
                mime,
                size_bytes: size,
                width: w,
                height: h,
                duration_ms: None,
                thumbnail_mxc_uri: thumb_mxc,
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        MT::Video(c) => {
            let (mxc_uri, encrypted) = split_source(&c.source);

            let (w, h, size, mime, dur, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.width.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.height.map(|v| u32::try_from(v).unwrap_or(0)),
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        info.duration.map(|d| d.as_millis() as u64),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::Video,
                mxc_uri: mxc_uri.clone(),
                mime,
                size_bytes: size,
                width: w,
                height: h,
                duration_ms: dur,
                // Fallback to full video if no explicit thumbnail
                thumbnail_mxc_uri: thumb_mxc.or_else(|| Some(mxc_uri.clone())),
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        MT::File(c) => {
            let (mxc_uri, encrypted) = split_source(&c.source);

            let (size, mime, thumb_mxc, thumb_enc) = c
                .info
                .as_ref()
                .map(|info| {
                    let (thumb_mxc, thumb_enc) = split_opt_source(info.thumbnail_source.as_ref());
                    (
                        info.size.map(u64::from),
                        info.mimetype.clone(),
                        thumb_mxc,
                        thumb_enc,
                    )
                })
                .unwrap_or((None, None, None, None));

            Some(AttachmentInfo {
                kind: AttachmentKind::File,
                mxc_uri,
                mime,
                size_bytes: size,
                width: None,
                height: None,
                duration_ms: None,
                thumbnail_mxc_uri: thumb_mxc,
                encrypted,
                thumbnail_encrypted: thumb_enc,
            })
        }

        _ => None,
    }
}

fn enc_to_record(ef: &EncryptedFile) -> EncFile {
    EncFile {
        url: ef.url.to_string(),
        json: serde_json::to_string(ef).unwrap_or_default(),
    }
}

async fn map_event_id_via_timeline(
    client: &SdkClient,
    rid: &ruma::OwnedRoomId,
    eid: &ruma::OwnedEventId,
) -> Option<MessageEvent> {
    let Some(tl) = get_timeline_for(client, rid).await else {
        return None;
    };
    let _ = tl.fetch_details_for_event(eid.as_ref()).await;
    let item = tl.item_by_event_id(eid).await?;
    map_timeline_event(&item, rid.as_str(), Some(eid.as_str())) // TODO: fix item_id usage
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
    info!("attach_sas_stream: flow_id={}", flow_id);

    let other_user = sas.other_user_id().to_owned();
    let other_device = sas.other_device().device_id().to_owned();

    verifs.lock().unwrap().insert(
        flow_id.clone(),
        VerifFlow {
            sas: sas.clone(),
            _other_user: other_user.clone(),
            _other_device: other_device.clone(),
        },
    );
    info!(
        "attach_sas_stream: stored VerifFlow for flow_id={}, other_user={}, other_device={}",
        flow_id, other_user, other_device
    );

    let mut stream = sas.changes();

    while let Some(state) = stream.next().await {
        info!("attach_sas_stream: flow_id={} state={:?}", flow_id, state);

        match state {
            SdkSasState::KeysExchanged { emojis, .. } => {
                if let Some(emojis) = emojis {
                    let payload = SasEmojis {
                        flow_id: flow_id.clone(),
                        other_user: sas.other_user_id().to_string(),
                        other_device: sas.other_device().device_id().to_string(),
                        emojis: emojis.emojis.iter().map(|e| e.symbol.to_string()).collect(),
                    };
                    obs.on_phase(flow_id.clone(), SasPhase::Emojis);
                    obs.on_emojis(payload);
                }
            }
            SdkSasState::Confirmed => {
                obs.on_phase(flow_id.clone(), SasPhase::Confirmed);
            }
            SdkSasState::Done { .. } => {
                obs.on_phase(flow_id.clone(), SasPhase::Done);
                verifs.lock().unwrap().remove(&flow_id);
                info!("attach_sas_stream: DONE, removed flow_id={}", flow_id);
                break;
            }
            SdkSasState::Cancelled(info_c) => {
                obs.on_phase(flow_id.clone(), SasPhase::Cancelled);
                obs.on_error(flow_id.clone(), info_c.reason().to_owned());
                verifs.lock().unwrap().remove(&flow_id);
                warn!(
                    "attach_sas_stream: CANCELLED for flow_id={} reason={}",
                    flow_id,
                    info_c.reason()
                );
                break;
            }
            SdkSasState::Created { .. }
            | SdkSasState::Started { .. }
            | SdkSasState::Accepted { .. } => {
                // maybe map to Requested/Ready?
            }
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
    let _lines = body.lines();
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

fn _mxc_from_media_source(src: &matrix_sdk::ruma::events::room::MediaSource) -> Option<String> {
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

fn map_vec_diff(
    diff: VectorDiff<Arc<TimelineItem>>,
    room_id: &OwnedRoomId,
    tl: &Arc<Timeline>,
) -> Option<TimelineDiffKind> {
    match diff {
        VectorDiff::Append { values } => {
            let vals: Vec<_> = values
                .iter()
                .filter_map(|v| {
                    v.as_event().and_then(|ei| {
                        fetch_reply_if_needed(ei, tl);
                        map_timeline_event(ei, room_id.as_str(), Some(&v.unique_id().0.to_string()))
                    })
                })
                .collect();
            Some(TimelineDiffKind::Append { values: vals })
        }
        VectorDiff::PushBack { value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, room_id.as_str(), Some(&value.unique_id().0.to_string()))
            })
            .map(|v| TimelineDiffKind::PushBack { value: v }),
        VectorDiff::PushFront { value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, room_id.as_str(), Some(&value.unique_id().0.to_string()))
            })
            .map(|v| TimelineDiffKind::PushFront { value: v }),
        VectorDiff::Insert { index, value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, room_id.as_str(), Some(&value.unique_id().0.to_string()))
            })
            .map(|v| TimelineDiffKind::Insert {
                index: index as u32,
                value: v,
            }),
        VectorDiff::Set { index, value } => value
            .as_event()
            .and_then(|ei| {
                fetch_reply_if_needed(ei, tl);
                map_timeline_event(ei, room_id.as_str(), Some(&value.unique_id().0.to_string()))
            })
            .map(|v| TimelineDiffKind::Set {
                index: index as u32,
                value: v,
            }),
        VectorDiff::Remove { index } => Some(TimelineDiffKind::Remove {
            index: index as u32,
        }),
        VectorDiff::PopBack => Some(TimelineDiffKind::PopBack),
        VectorDiff::PopFront => Some(TimelineDiffKind::PopFront),
        VectorDiff::Truncate { length } => Some(TimelineDiffKind::Truncate {
            length: length as u32,
        }),
        VectorDiff::Clear => Some(TimelineDiffKind::Clear),
        VectorDiff::Reset { values } => {
            let vals: Vec<_> = values
                .iter()
                .filter_map(|v| {
                    v.as_event().and_then(|ei| {
                        fetch_reply_if_needed(ei, tl);
                        map_timeline_event(ei, room_id.as_str(), Some(&v.unique_id().0.to_string()))
                    })
                })
                .collect();
            Some(TimelineDiffKind::Reset { values: vals })
        }
    }
}

fn fetch_reply_if_needed(ei: &EventTimelineItem, tl: &Arc<Timeline>) {
    if let Some(eid) = missing_reply_event_id(ei) {
        let tlc = tl.clone();
        tokio::spawn(async move {
            let _ = tlc.fetch_details_for_event(eid.as_ref()).await;
        });
    }
}

#[export(callback_interface)]
pub trait UrlOpener: Send + Sync {
    fn open(&self, url: String) -> bool;
}
