use once_cell::sync::Lazy;
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;

use futures_util::StreamExt;
use thiserror::Error;

// Matrix SDK core and UI
use matrix_sdk::{
    authentication::matrix::MatrixSession,
    config::SyncSettings,
    Client as SdkClient,
    SessionTokens,
    Room,
};
use matrix_sdk::ruma::{
    api::client::receipt::create_receipt::v3::ReceiptType,
    events::typing::SyncTypingEvent,
    DeviceId, EventId, OwnedDeviceId, OwnedRoomId, OwnedUserId, UserId,
};
use matrix_sdk::encryption::verification::{
    SasState as SdkSasState, SasVerification, VerificationRequest,
};
use matrix_sdk_ui::timeline::{
    EventTimelineItem, RoomExt as _, Timeline, TimelineEventItemId, TimelineItem,
};

// UniFFI macro-first setup
uniffi::setup_scaffolding!();

// ---------- Types exposed to Kotlin ----------
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

#[derive(Clone, uniffi::Record)]
pub struct DeviceSummary {
    pub device_id: String,
    pub display_name: String,
    pub ed25519: String,
    pub is_own: bool,
    pub locally_trusted: bool,
}

// Typing observer callback
#[uniffi::export(callback_interface)]
pub trait TypingObserver: Send + Sync {
    fn on_update(&self, names: Vec<String>);
}

// SAS verification FFI types
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

// ---------- Session persistence ----------
#[derive(Clone, serde::Serialize, serde::Deserialize)]
struct SessionInfo {
    user_id: String,
    device_id: String,
    access_token: String,
    homeserver: String,
}

fn default_store_dir() -> PathBuf {
    std::env::temp_dir().join("frair_store")
}
fn session_file(dir: &PathBuf) -> PathBuf {
    dir.join("session.json")
}

// Local trust file (we persist trusted device IDs here)
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

#[derive(Debug, Error, uniffi::Error)]
pub enum FfiError {
    #[error("{0}")]
    Msg(String),
}

impl From<matrix_sdk::Error> for FfiError {
    fn from(e: matrix_sdk::Error) -> Self { FfiError::Msg(e.to_string()) }
}
impl From<std::io::Error> for FfiError {
    fn from(e: std::io::Error) -> Self { FfiError::Msg(e.to_string()) }
}


// ---------- Verification flows store ----------
struct VerifFlow {
    sas: SasVerification,
    other_user: OwnedUserId,
    other_device: OwnedDeviceId,
}
type VerifMap = Arc<Mutex<HashMap<String, VerifFlow>>>;

// ---------- Object exported to Kotlin ----------
#[derive(uniffi::Object)]
pub struct Client {
    inner: SdkClient,
    store_dir: PathBuf,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
    verifs: VerifMap,
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

        let this = Self {
            inner,
            store_dir,
            guards: Mutex::new(vec![]),
            verifs: Arc::new(Mutex::new(HashMap::new())),
        };

        // Try session restore; seed store
        RT.block_on(async {
            // If the store already has an authenticated session, whoami() will succeed
            match this.inner.whoami().await {
                Ok(_) => {
                    // Seed local caches so rooms() etc. work immediately
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
                                refresh_token: None, // or Some if you also persist it
                            },
                        };
                        if this.inner.restore_session(session).await.is_ok() {
                            let _ = this.inner.sync_once(SyncSettings::default()).await;
                            return;
                        } else {
                            let _ = tokio::fs::remove_file(&path).await;
                        }
                    }
                }
            }
        });


        this
    }

    pub fn login(&self, username: String, password: String) {
        RT.block_on(async {
            let res = self.inner
                .matrix_auth()
                .login_username(username.as_str(), &password)
                .send()
                .await
                .expect("login");

            let info = SessionInfo {
                user_id: res.user_id.to_string(),
                device_id: res.device_id.to_string(),
                access_token: res.access_token.clone(),
                homeserver: self.inner.homeserver().to_string(), // <- sync
            };
            let _ = tokio::fs::create_dir_all(&self.store_dir).await;
            let _ = tokio::fs::write(session_file(&self.store_dir), serde_json::to_string(&info).unwrap()).await;

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
                out.push(RoomSummary { id: r.room_id().to_string(), name });
            }
            out
        })
    }

    pub fn start_sliding_sync(&self) {
        // Fallback to classic sync loop. Gate sliding-sync with a feature if needed.
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

    // Recent events (newest N returned ascending)
    pub fn recent_events(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return vec![]; };
            let Some(room) = self.inner.get_room(&room_id) else { return vec![]; };
            let Ok(timeline) = room.timeline().await else { return vec![]; };

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

    // Live timeline observer
    pub fn observe_room_timeline(&self, room_id: String, observer: Box<dyn TimelineObserver>) {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return; };
        let obs: Arc<dyn TimelineObserver> = Arc::<dyn TimelineObserver>::from(observer);

        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&room_id) else { return; };
            let Ok(tl) = room.timeline().await else { return; };
            let (items, mut stream) = tl.subscribe().await;

            for it in items.iter() {
                if let Some(ei) = it.as_event() {
                    if let Some(ev) = map_event(ei, room_id.as_str()) { obs.on_event(ev); }
                }
            }
            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    use matrix_sdk_ui::eyeball_im::VectorDiff;
                    match diff {
                        VectorDiff::PushBack { value }
                        | VectorDiff::PushFront { value }
                        | VectorDiff::Insert { value, .. }
                        | VectorDiff::Set { value, .. } => {
                            if let Some(ei) = value.as_event() {
                                if let Some(ev) = map_event(ei, room_id.as_str()) { obs.on_event(ev); }
                            }
                        }
                        _ => {}
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
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
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
    }

    pub fn logout(&self) -> bool {
        // 1) stop background tasks
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        // 2) best-effort server logout
        let _ = RT.block_on(async { self.inner.matrix_auth().logout().await });
        // 3) remove session.json and the SQLite store dir
        let _ = std::fs::remove_file(session_file(&self.store_dir));
        reset_store_dir(&self.store_dir);
        // Always return true: we’ve cleared local state; server logout can fail if offline
        true
    }

    // Read receipts
    pub fn mark_read(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline.mark_as_read(ReceiptType::Read).await.unwrap_or(false)
        })
    }

    pub fn mark_read_at(&self, room_id: String, event_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(event_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline
                .send_single_receipt(ReceiptType::Read, eid.to_owned())
                .await
                .unwrap_or(false)
        })
    }

    // Reactions
    pub fn react(&self, room_id: String, event_id: String, emoji: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(event_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            let Some(item) = timeline.item_by_event_id(&eid).await else { return false; };
            let item_id: TimelineEventItemId = item.identifier();
            timeline.toggle_reaction(&item_id, &emoji).await.is_ok()
        })
    }

    // Reply (plain text)
    pub fn reply(&self, room_id: String, in_reply_to: String, body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(reply_to) = EventId::parse(in_reply_to) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };

            let content = MsgNoRel::text_plain(body);
            timeline.send_reply(content, reply_to.to_owned()).await.is_ok()
        })
    }

    // Edit by event id
    pub fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::room::edit::EditedContent;
            use matrix_sdk::ruma::events::room::message::RoomMessageEventContentWithoutRelation as MsgNoRel;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(target_event_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };

            let Some(item) = timeline.item_by_event_id(&eid).await else { return false; };
            let item_id = item.identifier();
            let edited = EditedContent::RoomMessage(MsgNoRel::text_plain(new_body));

            timeline.edit(&item_id, edited).await.is_ok()
        })
    }

    // Pagination
    pub fn paginate_backwards(&self, room_id: String, count: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline.paginate_backwards(count).await.unwrap_or(false)
        })
    }

    pub fn paginate_forwards(&self, room_id: String, count: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline.paginate_forwards(count).await.unwrap_or(false)
        })
    }

    // Redaction
    pub fn redact(&self, room_id: String, event_id: String, reason: Option<String>) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(event_id) else { return false; };
            if let Some(room) = self.inner.get_room(&room_id) {
                room.redact(&eid, reason.as_deref(), None).await.is_ok()
            } else {
                false
            }
        })
    }

    // Typing observer (push): m.typing stream; resolve display names
    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) {
        let client = self.inner.clone();
        let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return; };
        let obs: Arc<dyn TypingObserver> = Arc::<dyn TypingObserver>::from(observer);

        let h = RT.spawn(async move {
            let stream = client.observe_room_events::<SyncTypingEvent, Room>(&room_id);
            let mut sub = stream.subscribe();

            let mut cache: HashMap<OwnedUserId, String> = HashMap::new();
            let mut last: Vec<String> = Vec::new();

            while let Some((ev, room)) = sub.next().await {
                let mut names = Vec::with_capacity(ev.content.user_ids.len());
                for uid in ev.content.user_ids.iter() {
                    if let Some(n) = cache.get(uid) { names.push(n.clone()); continue; }
                    let name = match room.get_member(uid).await {
                        Ok(Some(m)) => m.display_name().map(|s| s.to_string()).unwrap_or_else(|| uid.localpart().to_string()),
                        _ => uid.localpart().to_string(),
                    };
                    cache.insert(uid.to_owned(), name.clone());
                    names.push(name);
                }
                names.sort(); names.dedup();
                if names != last { last = names.clone(); obs.on_update(names); }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    // Devices (fingerprints + local trust persisted locally)
    pub fn list_my_devices(&self) -> Vec<DeviceSummary> {
        RT.block_on(async {
            let Some(me) = self.inner.user_id() else { return vec![]; };
            let trusted = read_trusted(&self.store_dir);
            let trusted_set: std::collections::HashSet<_> = trusted.iter().cloned().collect();
            let Ok(user_devs) = self.inner.encryption().get_user_devices(me).await else { return vec![]; };

            user_devs.devices().map(|dev| {
                let ed25519 = dev.ed25519_key().map(|k| k.to_base64()).unwrap_or_default();
                let is_own = self.inner.device_id().map(|my| my == dev.device_id()).unwrap_or(false);
                DeviceSummary {
                    device_id: dev.device_id().to_string(),
                    display_name: dev.display_name().unwrap_or_default().to_string(),
                    ed25519,
                    is_own,
                    locally_trusted: trusted_set.contains(&dev.device_id().to_string()),
                }
            }).collect()
        })
    }

    pub fn set_local_trust(&self, device_id: String, verified: bool) -> bool {
        // Local persistence only (SDK variants differ); safe and portable
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

    pub fn start_self_sas(&self, device_id: String, observer: Box<dyn VerificationObserver>) -> String {
        let obs: Arc<dyn VerificationObserver> = Arc::<dyn VerificationObserver>::from(observer);
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

    pub fn start_user_sas(&self, user_id: String, observer: Box<dyn VerificationObserver>) -> String {
        let obs: Arc<dyn VerificationObserver> = Arc::<dyn VerificationObserver>::from(observer);
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

    pub fn accept_verification(&self, flow_id: String) -> bool {
        RT.block_on(async {
            if let Some(f) = self.verifs.lock().unwrap().get(&flow_id) {
                f.sas.accept().await.is_ok()
            } else {
                false
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

    // helpful on app bootstrap:
    pub fn is_logged_in(&self) -> bool {
        self.inner.user_id().is_some()
    }
}

// Helper: attach SAS stream
impl Client {
    fn attach_sas(&self, flow_id: String, sas: SasVerification, observer: Box<dyn VerificationObserver>) -> String {
        let verifs = self.verifs.clone();
        let obs: Arc<dyn VerificationObserver> = Arc::<dyn VerificationObserver>::from(observer);

        let other_user = sas.other_user_id().to_owned();
        let other_device = sas.other_device().device_id().to_owned();

        verifs.lock().unwrap().insert(
            flow_id.clone(),
            VerifFlow { sas: sas.clone(), other_user: other_user.clone(), other_device: other_device.clone() },
        );

        let flow_id_inner = flow_id.clone();
        let h = RT.spawn(async move {
            let mut stream = sas.changes();
            obs.on_phase(flow_id_inner.clone(), SasPhase::Requested);
            while let Some(state) = stream.next().await {
                match state {
                    SdkSasState::Started { .. } => obs.on_phase(flow_id_inner.clone(), SasPhase::Ready),
                    SdkSasState::KeysExchanged { emojis, .. } => {
                        if let Some(emojis) = emojis {
                            let list: Vec<String> = emojis.emojis.iter().map(|e| e.symbol.to_string()).collect();
                            obs.on_phase(flow_id_inner.clone(), SasPhase::Emojis);
                            obs.on_emojis(SasEmojis {
                                flow_id: flow_id_inner.clone(),
                                other_user: other_user.to_string(),
                                other_device: other_device.to_string(),
                                emojis: list,
                            });
                        }
                    }
                    SdkSasState::Confirmed => obs.on_phase(flow_id_inner.clone(), SasPhase::Confirmed),
                    SdkSasState::Cancelled(_) => obs.on_phase(flow_id_inner.clone(), SasPhase::Cancelled),
                    SdkSasState::Done { .. } => { obs.on_phase(flow_id_inner.clone(), SasPhase::Done); break; }
                    _ => {}
                }
            }
        });
        self.guards.lock().unwrap().push(h);
        flow_id
    }

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

// Tiny adapter if you need to pass Arc<dyn VerificationObserver> back into a Box
struct ForwardingObserver(Arc<dyn VerificationObserver>);
impl VerificationObserver for ForwardingObserver {
    fn on_phase(&self, f: String, p: SasPhase) { self.0.on_phase(f, p) }
    fn on_emojis(&self, payload: SasEmojis) { self.0.on_emojis(payload) }
    fn on_error(&self, f: String, m: String) { self.0.on_error(f, m) }
}


// ---------- Helpers ----------
async fn get_timeline_for(client: &SdkClient, room_id: &OwnedRoomId) -> Option<Timeline> {
    let room = client.get_room(room_id)?;
    room.timeline().await.ok()
}

fn map_event(ev: &EventTimelineItem, room_id: &str) -> Option<MessageEvent> {
    let msg = ev.content().as_message()?;
    let ts: u64 = ev.timestamp().0.into();
    let event_id = ev.event_id().map(|e| e.to_string()).unwrap_or_else(|| format!("local-{ts}"));
    Some(MessageEvent {
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        body: msg.body().to_string(),
        timestamp_ms: ts,
    })
}

fn reset_store_dir(dir: &PathBuf) {
    let _ = std::fs::remove_dir_all(dir);
    // Recreate empty dir so a fresh SQLite store can be created next time
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
        VerifFlow { sas: sas.clone(), other_user: other_user.clone(), other_device: other_device.clone() },
    );

    let mut stream = sas.changes();
    while let Some(state) = stream.next().await {
        match state {
            SdkSasState::KeysExchanged { emojis, .. } => {
                if let Some(short) = emojis {
                    let list: Vec<String> = short.emojis.iter().map(|e| e.symbol.to_string()).collect();
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
            SdkSasState::Cancelled(_) => obs.on_phase(flow_id.clone(), SasPhase::Cancelled),
            SdkSasState::Done { .. } => { obs.on_phase(flow_id.clone(), SasPhase::Done); break; }
            _ => {}
        }
    }
}
