//! 配置与本地索引，存在 ~/.gomusic/prefs.json（对齐 Kotlin 桌面端 Prefs.kt）

use crate::models::Song;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const MAX_HISTORY: usize = 10000;
pub const MAX_SNAPSHOTS: usize = 3000;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Playlist {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub songs: Vec<String>,
    #[serde(default)]
    pub cover: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadRecord {
    pub ssid: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub quality: String,
    #[serde(default)]
    pub audio: String,
    #[serde(default)]
    pub cover: String,
    #[serde(default)]
    pub lyric: String,
    #[serde(default)]
    pub meta: String,
    #[serde(default)]
    pub time: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrefsFile {
    #[serde(default)]
    pub play_counts: HashMap<String, i32>,
    #[serde(default)]
    pub history: Vec<String>,
    #[serde(default)]
    pub playlists: Vec<Playlist>,
    #[serde(default)]
    pub downloads: Vec<DownloadRecord>,
    #[serde(default = "def_quality")]
    pub quality: String,
    #[serde(default)]
    pub play_mode: i32,
    /// 全局字体：0 系统字体（默认）/ 1 思源宋体
    #[serde(default)]
    pub font_kind: i32,
    /// 简繁转换：0 简体 / 1 香港繁体 / 2 仅搜索统一 / 3 不转换
    #[serde(default)]
    pub zh_convert: i32,
    #[serde(default = "def_speed")]
    pub speed: f32,
    #[serde(default)]
    pub theme_color: Option<i64>,
    #[serde(default)]
    pub nm_enabled: bool,
    #[serde(default)]
    pub nm_cookie: String,
    #[serde(default)]
    pub nm_account: String,
    #[serde(default)]
    pub song_snapshots: HashMap<String, Song>,
}

fn def_quality() -> String { "normal".to_string() }
fn def_speed() -> f32 { 1.0 }

impl Default for PrefsFile {
    fn default() -> Self {
        PrefsFile {
            play_counts: HashMap::new(), history: Vec::new(), playlists: Vec::new(),
            downloads: Vec::new(), quality: def_quality(), play_mode: 0, font_kind: 0,
            zh_convert: 0, speed: 1.0, theme_color: None, nm_enabled: false,
            nm_cookie: String::new(), nm_account: String::new(), song_snapshots: HashMap::new(),
        }
    }
}

pub static PREFS: Lazy<Mutex<PrefsFile>> = Lazy::new(|| Mutex::new(load()));

fn dir() -> std::path::PathBuf {
    let p = dirs::home_dir().unwrap_or_else(|| std::path::PathBuf::from(".")).join(".gomusic");
    let _ = std::fs::create_dir_all(&p);
    p
}

fn path() -> std::path::PathBuf { dir().join("prefs.json") }

fn load() -> PrefsFile {
    match std::fs::read_to_string(path()) {
        Ok(t) => serde_json::from_str(&t).unwrap_or_default(),
        Err(_) => PrefsFile::default(),
    }
}

pub fn save() {
    let d = PREFS.lock().clone();
    if let Ok(t) = serde_json::to_string_pretty(&d) {
        let _ = std::fs::write(path(), t);
    }
}

pub fn bump(ssid: &str) {
    let mut p = PREFS.lock();
    let c = p.play_counts.get(ssid).copied().unwrap_or(0) + 1;
    p.play_counts.insert(ssid.to_string(), c);
    drop(p);
    save();
}

pub fn push_history(ssid: &str) {
    let mut p = PREFS.lock();
    let mut h: Vec<String> = std::iter::once(ssid.to_string())
        .chain(p.history.iter().filter(|x| x.as_str() != ssid).cloned())
        .take(MAX_HISTORY)
        .collect();
    std::mem::swap(&mut h, &mut p.history);
    drop(p);
    save();
}

/// 歌曲信息快照（含网易云），保证历史随时能显示歌名
pub fn remember(songs: &[Song]) {
    let mut p = PREFS.lock();
    let mut changed = false;
    for s in songs {
        if s.title.trim().is_empty() { continue; }
        match p.song_snapshots.get(&s.ssid) {
            Some(old) if !(old.cover_url.is_none() && s.cover_url.is_some()) => {}
            _ => {
                p.song_snapshots.insert(s.ssid.clone(), s.clone());
                changed = true;
            }
        }
    }
    if p.song_snapshots.len() > MAX_SNAPSHOTS {
        // 简单裁剪：保留最近插入的一批
        let mut keys: Vec<String> = p.song_snapshots.keys().cloned().collect();
        keys.sort();
        let drop_n = keys.len() - MAX_SNAPSHOTS;
        for k in keys.into_iter().take(drop_n) { p.song_snapshots.remove(&k); }
    }
    drop(p);
    if changed { save(); }
}

/// 日志目录：崩溃与网易云请求都写这里
pub fn log_dir() -> std::path::PathBuf {
    let p = dir().join("log");
    let _ = std::fs::create_dir_all(&p);
    p
}

pub fn cache_dir() -> std::path::PathBuf {
    let p = dir().join("cache");
    let _ = std::fs::create_dir_all(&p);
    p
}
