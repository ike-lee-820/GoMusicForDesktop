//! 前端可用的命令。命名与职责对齐 Kotlin 桌面端 AppState。

use crate::api;
use crate::models::*;
use crate::netease::netease;
use crate::player::Player;
use crate::player::Status;
use crate::prefs;
use crate::prefs::{DownloadRecord, Playlist, PREFS};
use crate::store;
use parking_lot::Mutex;
use std::collections::HashMap;
use tauri::State;

pub struct App {
    pub player: Player,
    /// 当前下载进度
    pub dl: Mutex<Option<DlStatus>>,
    /// 正在下载的 ssid
    pub downloading: Mutex<Vec<String>>,
    /// 网易云歌曲的可用音质缓存
    pub nm_levels: Mutex<HashMap<i64, Vec<String>>>,
}

impl Default for App {
    fn default() -> Self {
        App {
            player: Player::new(),
            dl: Mutex::new(None),
            downloading: Mutex::new(Vec::new()),
            nm_levels: Mutex::new(HashMap::new()),
        }
    }
}

// ---------------- 曲库 ----------------

#[derive(serde::Serialize)]
pub struct Library {
    pub songs: Vec<Song>,
    pub offline: bool,
    pub artists: HashMap<String, ArtistInfo>,
    pub downloads: Vec<DownloadRecord>,
    pub play_counts: HashMap<String, i32>,
}

/// 加载本站曲库。连不上时进入离线模式：只展示已下载内容
#[tauri::command]
pub fn load_library(app: State<'_, App>) -> Library {
    let mut offline = false;
    let songs = match api::songs(true) {
        Ok(s) => s,
        Err(_) => {
            // 短超时失败再用长超时试一次，两次都不行才判离线
            match api::songs(false) {
                Ok(s) => s,
                Err(_) => { offline = true; Vec::new() }
            }
        }
    };
    let artists = if offline { HashMap::new() } else { api::artists() };
    let p = PREFS.lock();
    let mut songs = songs;
    if offline {
        songs = store::songs_of(&p.downloads);
    }
    Library {
        songs,
        offline,
        artists,
        downloads: p.downloads.clone(),
        play_counts: p.play_counts.clone(),
    }
}

#[tauri::command]
pub fn get_prefs() -> prefs::PrefsFile { PREFS.lock().clone() }

#[tauri::command]
pub fn set_pref(key: String, value: String) {
    let mut p = PREFS.lock();
    match key.as_str() {
        "quality" => p.quality = value,
        "playMode" => p.play_mode = value.parse().unwrap_or(0),
        "fontKind" => p.font_kind = value.parse().unwrap_or(0),
        "zhConvert" => p.zh_convert = value.parse().unwrap_or(0),
        "speed" => p.speed = value.parse().unwrap_or(1.0),
        "themeColor" => p.theme_color = if value.is_empty() { None } else { value.parse().ok() },
        "nmEnabled" => {
            p.nm_enabled = value == "1" || value == "true";
            netease().set_cookie(p.nm_cookie.clone());
        }
        "nmCookie" => { p.nm_cookie = value.clone(); netease().set_cookie(value); }
        "nmAccount" => p.nm_account = value,
        _ => {}
    }
    drop(p);
    prefs::save();
}

// ---------------- 播放 ----------------

/// 解析出本地可播放的音频路径：
/// 已下载的直接用本地文件；在线的先缓存到 ~/.gomusic/cache/
fn resolve_audio(song: &Song, quality: &str) -> Result<String, String> {
    let p = PREFS.lock();
    if let Some(rec) = p.downloads.iter().find(|r| r.ssid == song.ssid) {
        if !rec.audio.is_empty() && std::path::Path::new(&rec.audio).exists() {
            return Ok(rec.audio.clone());
        }
    }
    drop(p);

    let url = if is_netease(&song.ssid) {
        let id = netease_id_of(&song.ssid).ok_or("不是网易云歌曲")?;
        netease().audio_url(id, quality).ok_or("网易云没有可用音源")?
    } else {
        api::audio_url(&song.ssid, quality)?
    };
    // http 地址在部分平台会被拒，统一走 https
    let url = if url.starts_with("http://") { format!("https://{}", &url[7..]) } else { url };
    let ext = {
        let p: &str = url.split('?').next().unwrap_or("");
        let e = p.rsplit('.').next().unwrap_or("mp3");
        if e.len() >= 2 && e.len() <= 4 { e.to_string() } else { "mp3".to_string() }
    };
    let dir = prefs::cache_dir();
    let file = dir.join(format!("{}_{}.{}", song.ssid, quality, ext));
    let path = file.to_string_lossy().to_string();
    if !file.exists() || file.metadata().map(|m| m.len()).unwrap_or(0) == 0 {
        api::fetch_to_file(&url, &path, None)?;
    }
    Ok(path)
}

#[tauri::command]
pub fn play_song(app: State<'_, App>, song: Song, quality: String) -> Result<Status, String> {
    let path = resolve_audio(&song, &quality)?;
    let rate = PREFS.lock().speed;
    app.player.play(&path, 0, rate);
    prefs::bump(&song.ssid);
    prefs::push_history(&song.ssid);
    prefs::remember(std::slice::from_ref(&song));
    Ok(app.player.status())
}

#[tauri::command]
pub fn player_pause(app: State<'_, App>) { app.player.pause(); }
#[tauri::command]
pub fn player_resume(app: State<'_, App>) { app.player.resume(); }
#[tauri::command]
pub fn player_stop(app: State<'_, App>) { app.player.stop(); }
#[tauri::command]
pub fn player_seek(app: State<'_, App>, ms: i64) { app.player.seek(ms); }
#[tauri::command]
pub fn player_set_rate(app: State<'_, App>, rate: f32) {
    PREFS.lock().speed = rate;
    drop(PREFS.lock());
    prefs::save();
    app.player.set_rate(rate);
}

#[tauri::command]
pub fn player_status(app: State<'_, App>) -> Status { app.player.status() }

/// 当前歌曲的歌词：已下载读本地文件，网易云走 /lyric，本地曲库走 /api/lyrics
#[tauri::command]
pub fn load_lyric(ssid: String) -> Vec<LrcLine> {
    let p = PREFS.lock();
    let rec = p.downloads.iter().find(|r| r.ssid == ssid).cloned();
    drop(p);
    let text = if let Some(r) = &rec {
        let t = store::read_text(&r.lyric);
        if t.trim().is_empty() { fetch_online_lyric(&ssid) } else { t }
    } else {
        fetch_online_lyric(&ssid)
    };
    lyric_lines(&text)
}

fn fetch_online_lyric(ssid: &str) -> String {
    if let Some(id) = netease_id_of(ssid) {
        let t = netease().lyric(id);
        if !t.trim().is_empty() { return t; }
    }
    if let Ok(d) = api::detail(ssid) {
        if !d.lyrics.trim().is_empty() { return d.lyrics; }
    }
    api::lyrics(ssid)
}

// ---------------- 网易云 ----------------

#[tauri::command]
pub fn nm_search(kind: i32, keyword: String, limit: i32, offset: i32) -> NmSearchResult {
    let kw = keyword.trim();
    if kw.is_empty() {
        return NmSearchResult { songs: vec![], albums: vec![], artists: vec![] };
    }
    match kind {
        1 => NmSearchResult {
            songs: vec![], albums: netease().search_albums(kw, limit, offset), artists: vec![],
        },
        2 => NmSearchResult {
            songs: vec![], albums: vec![], artists: netease().search_artists(kw, limit, offset),
        },
        _ => NmSearchResult {
            songs: netease().search_songs(kw, limit, offset), albums: vec![], artists: vec![],
        },
    }
}

#[derive(serde::Serialize)]
pub struct NmSearchResult {
    pub songs: Vec<Song>,
    pub albums: Vec<Group>,
    pub artists: Vec<Group>,
}

#[derive(serde::Serialize)]
pub struct HomeData {
    pub recommend: Vec<Song>,
    pub new_songs: Vec<Song>,
    pub hot_words: Vec<String>,
    pub top_artists: Vec<Group>,
    pub new_albums: Vec<Group>,
    pub tops: Vec<Group>,
}

#[tauri::command]
pub fn nm_home(new_song_type: i32) -> HomeData {
    let nm = netease();
    HomeData {
        recommend: nm.recommend_songs(),
        new_songs: nm.top_songs(new_song_type),
        hot_words: nm.hot_search(),
        top_artists: nm.top_artists(20, 0),
        new_albums: nm.newest_albums(20),
        tops: nm.top_list(),
    }
}

#[tauri::command]
pub fn nm_songs_of(kind: String, id: i64, limit: i32, offset: i32) -> Vec<Song> {
    netease().songs_of(&kind, id, limit, offset)
}

#[tauri::command]
pub fn nm_artist_albums(id: i64, limit: i32, offset: i32) -> Vec<Group> {
    netease().artist_albums(id, limit, offset)
}

#[tauri::command]
pub fn nm_available_levels(app: State<'_, App>, ssid: String) -> Vec<String> {
    let id = match netease_id_of(&ssid) { Some(i) => i, None => return vec![] };
    {
        let c = app.nm_levels.lock();
        if let Some(v) = c.get(&id) { return v.clone(); }
    }
    let v = netease().available_levels(id);
    app.nm_levels.lock().insert(id, v.clone());
    v
}

#[tauri::command]
pub fn nm_qr_key() -> Option<String> { netease().qr_key() }
#[tauri::command]
pub fn nm_qr_image(key: String) -> Option<String> { netease().qr_image(&key) }

#[derive(serde::Serialize)]
pub struct QrStatus {
    pub code: i64,
    pub cookie: Option<String>,
    pub message: String,
}

/// 800 过期 801 等待扫码 802 待确认 803 成功
#[tauri::command]
pub fn nm_qr_check(key: String) -> QrStatus {
    let (code, cookie, message) = netease().qr_check(&key);
    QrStatus { code, cookie, message }
}

#[tauri::command]
pub fn nm_login_captcha(phone: String, captcha: String) -> Option<String> {
    netease().login_by_captcha(&phone, &captcha)
}

#[tauri::command]
pub fn nm_send_captcha(phone: String) -> bool { netease().send_captcha(&phone) }

#[tauri::command]
pub fn nm_account_name() -> Option<String> { netease().account_name() }

#[tauri::command]
pub fn nm_logged_in() -> bool { netease().logged_in() }

#[tauri::command]
pub fn nm_mv_url(mv_id: i64) -> Option<String> { netease().mv_url(mv_id) }

// ---------------- 歌单 ----------------

#[tauri::command]
pub fn create_playlist(name: String, songs: Vec<String>, cover: Option<String>) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    let id = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0).to_string();
    p.playlists.push(Playlist { id, name: name.trim().to_string(), songs, cover });
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn rename_playlist(id: String, name: String) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    if let Some(pl) = p.playlists.iter_mut().find(|x| x.id == id) {
        pl.name = name.trim().to_string();
    }
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn delete_playlist(id: String) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    p.playlists.retain(|x| x.id != id);
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn add_to_playlist(id: String, ids: Vec<String>) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    if let Some(pl) = p.playlists.iter_mut().find(|x| x.id == id) {
        for s in ids { if !pl.songs.contains(&s) { pl.songs.push(s); } }
    }
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn remove_from_playlist(id: String, ssid: String) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    if let Some(pl) = p.playlists.iter_mut().find(|x| x.id == id) {
        pl.songs.retain(|s| s != &ssid);
    }
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn move_in_playlist(id: String, from: usize, to: usize) -> Vec<Playlist> {
    let mut p = PREFS.lock();
    if let Some(pl) = p.playlists.iter_mut().find(|x| x.id == id) {
        if from < pl.songs.len() && to < pl.songs.len() {
            let item = pl.songs.remove(from);
            pl.songs.insert(to, item);
        }
    }
    let out = p.playlists.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn remember_songs(songs: Vec<Song>) { prefs::remember(&songs); }

// ---------------- 下载 ----------------

#[tauri::command]
pub fn download_songs(app: State<'_, App>, songs: Vec<Song>, quality: String) -> Result<(), String> {
    let todo: Vec<Song> = {
        let p = PREFS.lock();
        let dl = app.downloading.lock();
        songs.into_iter()
            .filter(|s| !p.downloads.iter().any(|r| r.ssid == s.ssid) && !dl.contains(&s.ssid))
            .collect()
    };
    if todo.is_empty() { return Ok(()); }
    {
        let mut dl = app.downloading.lock();
        for s in &todo { dl.push(s.ssid.clone()); }
    }
    let total = todo.len() as i32;
    for (i, s) in todo.iter().enumerate() {
        let title = s.title.clone();
        {
            let mut d = app.dl.lock();
            *d = Some(DlStatus {
                title: title.clone(), index: (i + 1) as i32, total,
                done: 0, size: 0, bps: 0,
            });
        }
        let started = std::time::Instant::now();
        let mut last_bytes = 0u64;
        let mut last_time = started;
        let mut cb = move |done: u64, size: u64| {
            let now = std::time::Instant::now();
            let dt = now.duration_since(last_time).as_millis() as u64;
            if dt < 400 && done < size { return; }
            let bps = if dt > 0 { ((done.saturating_sub(last_bytes)) * 1000 / dt) as i64 } else { 0 };
            last_bytes = done;
            last_time = now;
            // 闭包内不能借用外部的 st_ref（生命周期），改用全局
            set_dl_progress(title.clone(), (i + 1) as i32, total, done as i64, size as i64, bps);
        };
        let url_override = if is_netease(&s.ssid) {
            netease_id_of(&s.ssid).and_then(|id| netease().audio_url(id, &quality))
        } else { None };
        let r = store::download(s, &quality, url_override, Some(&mut cb));
        let mut p = PREFS.lock();
        match r {
            Ok(rec) => {
                p.downloads.retain(|x| !(x.ssid == rec.ssid && x.quality == rec.quality));
                p.downloads.push(rec);
            }
            Err(_) => {}
        }
        drop(p);
        prefs::save();
        app.downloading.lock().retain(|x| x != &s.ssid);
    }
    *app.dl.lock() = None;
    Ok(())
}

/// 下载进度回调要跨闭包更新，用全局槽位中转
static DL: Mutex<Option<DlStatus>> = Mutex::new(None);

fn set_dl_progress(title: String, index: i32, total: i32, done: i64, size: i64, bps: i64) {
    *DL.lock() = Some(DlStatus { title, index, total, done, size, bps });
}

#[tauri::command]
pub fn download_status(_app: State<'_, App>) -> Option<DlStatus> { DL.lock().clone() }

#[tauri::command]
pub fn delete_download(ssid: String) -> Vec<DownloadRecord> {
    let mut p = PREFS.lock();
    let rec = p.downloads.iter().find(|r| r.ssid == ssid).cloned();
    if let Some(r) = rec { store::delete(&r); }
    p.downloads.retain(|x| x.ssid != ssid);
    let out = p.downloads.clone();
    drop(p);
    prefs::save();
    out
}

#[tauri::command]
pub fn open_download_dir() {
    let p = store::root();
    #[cfg(target_os = "windows")]
    { let _ = std::process::Command::new("explorer").arg(p).spawn(); }
    #[cfg(target_os = "macos")]
    { let _ = std::process::Command::new("open").arg(p).spawn(); }
    #[cfg(target_os = "linux")]
    { let _ = std::process::Command::new("xdg-open").arg(p).spawn(); }
}

// ---------------- 其它 ----------------

#[tauri::command]
pub fn app_version() -> String { env!("CARGO_PKG_VERSION").to_string() }

#[tauri::command]
pub fn quality_label_of(k: String) -> String { quality_label(&k) }

#[tauri::command]
pub fn nm_level_label_of(k: String) -> String { nm_level_label(&k) }

