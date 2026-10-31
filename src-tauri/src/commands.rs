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

/// 整篇歌词翻译。
///
/// 用 spawn_blocking 包一层：翻译是要发出几十个网络请求的阻塞活，
/// 直接在同步命令里跑会冻住界面。返回与 texts 等长，翻不出来的是空串。
#[tauri::command]
pub async fn translate_lines(texts: Vec<String>) -> Vec<String> {
    tauri::async_runtime::spawn_blocking(move || crate::translate::translate_all(texts))
        .await
        .unwrap_or_default()
}

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

fn app_dl() -> parking_lot::MutexGuard<'static, Option<DlStatus>> { DL.lock() }

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

/// 下载 MV 视频文件到下载目录的 MV 子目录
#[tauri::command]
pub fn download_mv(title: String, url: String) -> Result<String, String> {
    {
        let mut d = app_dl();
        *d = Some(DlStatus { title: title.clone(), index: 1, total: 1, done: 0, size: 0, bps: 0 });
    }
    let started = std::time::Instant::now();
    let mut last_bytes = 0u64;
    let mut last_time = started;
    let t = title.clone();
    let mut cb = move |done: u64, size: u64| {
        let now = std::time::Instant::now();
        let dt = now.duration_since(last_time).as_millis() as u64;
        if dt < 400 && done < size { return; }
        let bps = if dt > 0 { ((done.saturating_sub(last_bytes)) * 1000 / dt) as i64 } else { 0 };
        last_bytes = done;
        last_time = now;
        set_dl_progress(t.clone(), 1, 1, done as i64, size as i64, bps);
    };
    let r = store::download_mv(&title, &url, Some(&mut cb));
    *app_dl() = None;
    r
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

// ---------------- 补充：榜单 / 网易云歌单同步 / 更新 ----------------

#[tauri::command]
pub fn nm_top_list() -> Vec<Group> { netease().top_list() }

#[tauri::command]
pub fn nm_artist_detail(id: i64) -> Option<Group> { netease().artist_detail(id) }

/// 我创建的网易云歌单（用于同步）
#[tauri::command]
pub fn nm_user_playlists() -> Vec<Group> {
    let uid = netease().uid();
    if uid <= 0 { return Vec::new(); }
    netease().user_playlists(uid)
}

/// 把歌曲加进网易云歌单；只同步网易云歌曲（本站歌曲在云端不存在）
#[tauri::command]
pub fn nm_playlist_add(pid: i64, ids: Vec<String>) -> bool {
    if pid <= 0 { return false; }
    let tids: Vec<i64> = ids.iter().filter_map(|x| netease_id_of(x)).collect();
    if tids.is_empty() { return false; }
    netease().playlist_add(pid, &tids)
}

#[tauri::command]
pub fn nm_playlist_del(pid: i64, ids: Vec<String>) -> bool {
    if pid <= 0 { return false; }
    let tids: Vec<i64> = ids.iter().filter_map(|x| netease_id_of(x)).collect();
    if tids.is_empty() { return false; }
    netease().playlist_del(pid, &tids)
}

#[tauri::command]
pub fn nm_playlist_rename(pid: i64, name: String) -> bool {
    if pid <= 0 { return false; }
    netease().playlist_rename(pid, &name)
}

#[derive(serde::Serialize)]
pub struct UpdateInfo {
    pub version: String,
    pub url: String,
    pub notes: String,
}

/// 检查更新：拉 release 列表取版本号最大的（/releases/latest 会漏掉预发布与草稿）
#[tauri::command]
pub fn check_update() -> Option<UpdateInfo> {
    let mirrors = [
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
        "https://mirror.ghproxy.com/",
        "https://gh-proxy.net/",
        "",
    ];
    let path = "repos/ike-lee-820/GoMusicForDesktop/releases?per_page=20";
    let cur = env!("CARGO_PKG_VERSION");
    let mut urls: Vec<String> = Vec::new();
    for m in mirrors {
        if m.is_empty() {
            urls.push(format!("https://api.github.com/{}", path));
        } else {
            urls.push(format!("{}https://api.github.com/{}", m, path));
        }
    }
    for url in urls {
        let txt = match crate::api::bytes(&url) {
            Some(b) => String::from_utf8_lossy(&b).to_string(),
            None => continue,
        };
        let v: serde_json::Value = match serde_json::from_str(&txt) { Ok(x) => x, Err(_) => continue };
        let arr = match v.as_array() { Some(a) => a, None => continue };
        let mut best: Option<(String, String, String)> = None;
        for r in arr {
            if r.get("draft").and_then(|x| x.as_bool()).unwrap_or(false) { continue; }
            let tag = r.get("tag_name").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let ver = tag.trim_start_matches('v').to_string();
            if ver.is_empty() { continue; }
            let html = r.get("html_url").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let notes = r.get("body").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let better = match &best {
                None => true,
                Some((bv, _, _)) => ver_cmp(&ver, bv) > 0,
            };
            if better { best = Some((ver, html, notes)); }
        }
        if let Some((ver, url, notes)) = best {
            if ver_cmp(&ver, cur) > 0 {
                return Some(UpdateInfo { version: ver, url, notes });
            }
            return None;
        }
    }
    None
}

/// 版本号比较：按点分段逐段比数字，非数字段退回字符串比较
fn ver_cmp(a: &str, b: &str) -> i32 {
    let pa: Vec<&str> = a.split('.').collect();
    let pb: Vec<&str> = b.split('.').collect();
    for i in 0..pa.len().max(pb.len()) {
        let x = pa.get(i).copied().unwrap_or("0");
        let y = pb.get(i).copied().unwrap_or("0");
        match (x.parse::<i64>(), y.parse::<i64>()) {
            (Ok(m), Ok(n)) => if m != n { return if m > n { 1 } else { -1 } },
            _ => if x != y { return if x > y { 1 } else { -1 } },
        }
    }
    0
}

/// 用系统默认浏览器/文件管理器打开链接或目录
#[tauri::command]
pub fn open_url(url: String) {
    let u = url.clone();
    std::thread::spawn(move || {
        let _ = if cfg!(target_os = "windows") {
            std::process::Command::new("cmd").args(["/C", "start", "", &u]).spawn()
        } else if cfg!(target_os = "macos") {
            std::process::Command::new("open").arg(&u).spawn()
        } else {
            std::process::Command::new("xdg-open").arg(&u).spawn()
        };
    });
}

#[tauri::command]
pub fn remove_history(ssid: String) -> Vec<String> {
    let mut p = PREFS.lock();
    p.history.retain(|x| x != &ssid);
    let h = p.history.clone();
    drop(p);
    prefs::save();
    h
}

#[tauri::command]
pub fn clear_history() -> Vec<String> {
    let mut p = PREFS.lock();
    p.history.clear();
    drop(p);
    prefs::save();
    Vec::new()
}
