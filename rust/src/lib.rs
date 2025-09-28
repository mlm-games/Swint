use once_cell::sync::Lazy;
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::runtime::Runtime;

use futures_util::StreamExt;
use matrix_sdk::{authentication::matrix::MatrixSession, config::SyncSettings, ruma::{api::client::receipt::create_receipt::v3::ReceiptType, events::{room::message::RoomMessageEventContentWithoutRelation, typing::SyncTypingEvent}, EventId, OwnedUserId}, Client as SdkClient, Room, SessionTokens};
use matrix_sdk::ruma::{OwnedRoomId, UserId, MilliSecondsSinceUnixEpoch};
use matrix_sdk_ui::{eyeball_im::VectorDiff, timeline::{
    EventTimelineItem, RoomExt as _, Timeline, TimelineEventItemId, TimelineItem, TimelineItemContent
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

fn map_event(ev: &EventTimelineItem, room_id: &str) -> Option<MessageEvent> {
    // Only message events
    let msg = ev.content().as_message()?; 
    let ts = ev.timestamp().0;

    let event_id = ev
        .event_id()
        .map(|e| e.to_string())
        .unwrap_or_else(|| format!("local-{}", ts));
    Some(MessageEvent {
        event_id,
        room_id: room_id.to_string(),
        sender: ev.sender().to_string(),
        body: msg.body().to_string(),
        timestamp_ms: ts.into(),
    })
}

async fn get_timeline_for(client: &SdkClient, room_id: &OwnedRoomId)
    -> Option<matrix_sdk_ui::timeline::Timeline>
{
    let room = client.get_room(room_id)?;
    room.timeline().await.ok()
}


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
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH).unwrap()
        .as_millis() as u64
}

// ---------- Object exported to Kotlin ----------
#[derive(uniffi::Object)]
pub struct Client {
    inner: SdkClient,
    store_dir: PathBuf,
    guards: Mutex<Vec<tokio::task::JoinHandle<()>>>,
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

        let this = Self { inner, store_dir, guards: Mutex::new(vec![]) };

        // Try to restore session (if file exists), then seed local store
        RT.block_on(async {
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
                                refresh_token: None,
                            },
                        };
                        if this.inner.restore_session(session).await.is_ok() {
                            let _ = this.inner.sync_once(SyncSettings::default()).await;
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
            let id_str = username.as_str();
            let res = self.inner
                .matrix_auth()
                .login_username(id_str, &password)
                .send()
                .await
                .expect("login");

            // Save session
            let info = SessionInfo {
                user_id: res.user_id.to_string(),
                device_id: res.device_id.to_string(),
                access_token: res.access_token.clone(),
                homeserver: self.inner.homeserver().to_string(),
            };
            let _ = tokio::fs::create_dir_all(&self.store_dir).await;
            let _ = tokio::fs::write(
                session_file(&self.store_dir),
                serde_json::to_string(&info).unwrap(),
            ).await;

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

    pub fn recent_events(&self, room_id: String, limit: u32) -> Vec<MessageEvent> {
        RT.block_on(async {
            let Ok(room_id) = matrix_sdk::ruma::OwnedRoomId::try_from(room_id) else { return vec![]; };
            let Some(room) = self.inner.get_room(&room_id) else { return vec![]; };
            let Ok(timeline) = room.timeline().await else { return vec![]; };

            // Current snapshot + ignore the stream here
            let (items, _stream) = timeline.subscribe().await;

            // items are in timeline order; we want newest-first, take, then restore ascending
            let mut out: Vec<MessageEvent> = items
                .iter()
                .rev()
                .filter_map(|it: &std::sync::Arc<TimelineItem>| {
                    it.as_event().and_then(|ev| map_event(ev, room_id.as_str()))
                })
                .take(limit as usize)
                .collect();

            out.reverse();
            out
        })
    }

    pub fn observe_room_timeline(&self, room_id: String, observer: Box<dyn TimelineObserver>) {
        let client = self.inner.clone();
        let Ok(room_id) = matrix_sdk::ruma::OwnedRoomId::try_from(room_id) else { return; };
        let obs = std::sync::Arc::new(observer);

        let h = RT.spawn(async move {
            let Some(room) = client.get_room(&room_id) else { return; };
            let Ok(timeline) = room.timeline().await else { return; };

            // Initial snapshot
            let (items, mut stream) = timeline.subscribe().await;
            for it in items.iter() {
                if let Some(ev) = it.as_event().and_then(|ev| map_event(ev, room_id.as_str())) {
                    obs.on_event(ev);
                }
            }

            // Live diffs
            while let Some(diffs) = stream.next().await {
                for diff in diffs {
                    match diff {
                        VectorDiff::PushBack { value }
                        | VectorDiff::PushFront { value }
                        | VectorDiff::Insert { value, .. }
                        | VectorDiff::Set { value, .. } => {
                            if let Some(ev) = value.as_event().and_then(|ev| map_event(ev, room_id.as_str())) {
                                obs.on_event(ev);
                            }
                        }
                        _ => {}
                    }
                }
            }
        });
        self.guards.lock().unwrap().push(h);
    }

    pub fn paginate_backwards(&self, room_id: String, num_events: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = matrix_sdk::ruma::OwnedRoomId::try_from(room_id) else { return false; };
            let Some(room) = self.inner.get_room(&room_id) else { return false; };
            let Ok(timeline) = room.timeline().await else { return false; };

            timeline.paginate_backwards(num_events).await.unwrap_or(false)
        })
    }

    pub fn mark_read(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline.mark_as_read(ReceiptType::Read).await.unwrap_or(false)
        })
    }

    // Send a read receipt for a specific event id in the timeline. Returns whether a receipt was sent.
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

    // REACTIONS

    // Toggle reaction (adds/removes) on the given event id with the provided emoji.
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

    // REPLIES

    // Send a plain-text reply to an event id.
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

    // EDITS

    // Edit a message by event id, replacing its body with the given text.
    pub fn edit(&self, room_id: String, target_event_id: String, new_body: String) -> bool {
        RT.block_on(async {
            use matrix_sdk::room::edit::EditedContent;

            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(target_event_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };

            // Locate the timeline item and build an edit payload.
            let Some(item) = timeline.item_by_event_id(&eid).await else { return false; };
            let item_id = item.identifier();

            // EditedContent supports constructing from a text body.
            // (This type lives at matrix_sdk::room::edit::EditedContent.)
            let edited = EditedContent::RoomMessage(RoomMessageEventContentWithoutRelation::text_plain(new_body));

            timeline.edit(&item_id, edited).await.is_ok()
        })
    }

    // FORWARD PAGINATION

    // Load more events at the end of the timeline. Returns whether we hit the end.
    pub fn paginate_forwards(&self, room_id: String, count: u16) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Some(timeline) = get_timeline_for(&self.inner, &room_id).await else { return false; };
            timeline.paginate_forwards(count).await.unwrap_or(false)
        })
    }

    // Redact an event (delete). Optional `reason` (pass empty string from Kotlin if none).
    pub fn redact(&self, room_id: String, event_id: String, reason: Option<String>) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            let Ok(eid) = EventId::parse(event_id) else { return false; };
            if let Some(room) = self.inner.get_room(&room_id) {
                room.redact(&eid, reason.as_deref(), None).await.is_ok()
            } else { false }
        })
    }

    // Start/stop typing notifications.
    // Some versions take just a bool; others accept a timeout. We try the simplest call.
    pub fn start_typing(&self, room_id: String, _timeout_ms: u64) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            if let Some(room) = self.inner.get_room(&room_id) {
                room.typing_notice(true).await.is_ok()
            } else { false }
        })
    }


    pub fn observe_typing(&self, room_id: String, observer: Box<dyn TypingObserver>) {
    let client = self.inner.clone();
    let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return; };
    let obs = std::sync::Arc::new(observer);

    let h = RT.spawn(async move {
        // Create an observable handler for typing in this room
        let observer = client.observe_room_events::<SyncTypingEvent, Room>(&room_id);
        let mut sub = observer.subscribe();

        // Track last value we sent to avoid noisy duplicates
        let mut last: Vec<String> = Vec::new();

        while let Some((ev, _room)) = sub.next().await {
            // m.typing carries a list of user IDs currently typing
            let mut names: Vec<String> = ev.content.user_ids.iter().map(|u| u.to_string()).collect();

            // Optional: keep deterministic order for stable diffs
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

    pub fn stop_typing(&self, room_id: String) -> bool {
        RT.block_on(async {
            let Ok(room_id) = OwnedRoomId::try_from(room_id) else { return false; };
            if let Some(room) = self.inner.get_room(&room_id) {
                room.typing_notice(false).await.is_ok()
            } else { false }
        })
    }

    // no network call
    pub fn clear_session(&self) -> bool {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        let path = session_file(&self.store_dir);
        std::fs::remove_file(&path).is_ok()
    }

    pub fn logout(&self) -> bool {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
        let ok = RT.block_on(async {
            // matrix_auth().logout() is the 0.14 API
            self.inner.matrix_auth().logout().await.is_ok()
        });
        let path = session_file(&self.store_dir);
        let _ = std::fs::remove_file(&path);

        ok
    }

    pub fn shutdown(&self) {
        for h in self.guards.lock().unwrap().drain(..) {
            h.abort();
        }
    }
}

#[uniffi::export(callback_interface)]
pub trait TypingObserver: Send + Sync {
    fn on_update(&self, names: Vec<String>);
}
