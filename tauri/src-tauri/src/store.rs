//! 本地下载库：~/Downloads/GoMusic/{音乐,封面,歌词,歌曲信息}（对齐 Kotlin LocalLib）

use crate::api;
use crate::models::Song;
use crate::prefs::DownloadRecord;
use std::path::PathBuf;

pub const ROOT: &str = "GoMusic";
pub const AUDIO_DIR: &str = "音乐";
pub const COVER_DIR: &str = "封面";
pub const LYRIC_DIR: &str = "歌词";
pub const META_DIR: &str = "歌曲信息";

/// 下载根目录：优先 ~/Downloads，没有就用 ~/Music
pub fn root() -> PathBuf {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    let dl = home.join("Downloads");
    let base = if dl.exists() || std::fs::create_dir_all(&dl).is_ok() { dl } else { home.join("Music") };
    let p = base.join(ROOT);
    let _ = std::fs::create_dir_all(&p);
    p
}

fn sub(name: &str) -> PathBuf {
    let p = root().join(name);
    let _ = std::fs::create_dir_all(&p);
    p
}

fn safe(name: &str) -> String {
    let bad = ['\\', '/', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t'];
    let s: String = name.chars().map(|c| if bad.contains(&c) { '_' } else { c })
        .take(80).collect();
    let s = s.trim().to_string();
    if s.is_empty() { "unknown".to_string() } else { s }
}

pub fn songs_of(recs: &[DownloadRecord]) -> Vec<Song> {
    recs.iter().map(|r| Song {
        ssid: r.ssid.clone(),
        title: r.title.clone(),
        artist: r.artist.clone(),
        album: r.album.clone(),
        cover_url: if r.cover.is_empty() { None } else { Some(r.cover.clone()) },
        ..Default::default()
    }).collect()
}

pub fn downloaded_ids(recs: &[DownloadRecord]) -> Vec<String> {
    recs.iter().map(|r| r.ssid.clone()).collect()
}

/// 下载一首歌：音频 + 封面 + 歌词 + 歌曲信息
pub fn download(
    song: &Song,
    quality: &str,
    url_override: Option<String>,
    on_progress: Option<&mut dyn FnMut(u64, u64)>,
) -> Result<DownloadRecord, String> {
    let d = api::detail(&song.ssid).ok();
    let url = match url_override {
        Some(u) => u,
        None => api::audio_url(&song.ssid, quality)?,
    };
    let base = safe(&format!("{} - {}", song.artist, song.title));
    let ext = {
        let p: String = url.split('?').next().unwrap_or("").to_string();
        let e = p.rsplit('.').next().unwrap_or("mp3").to_string();
        if e.len() >= 2 && e.len() <= 4 { e } else { "mp3".to_string() }
    };

    // 音频
    let audio_file = sub(AUDIO_DIR).join(format!("{}.{}", base, ext));
    let path_str = audio_file.to_string_lossy().to_string();
    api::fetch_to_file(&url, &path_str, on_progress)?;

    // 封面
    let mut cover_ref = String::new();
    let cover_url = d.as_ref().and_then(|x| x.cover.clone())
        .or_else(|| api::cover_ref(song.cover_url.as_deref()));
    if let Some(cu) = cover_url {
        let bytes = if cu.starts_with("data:") { api::decode_data_uri(&cu) } else { api::bytes(&cu) };
        if let Some(b) = bytes {
            let f = sub(COVER_DIR).join(format!("{}.jpg", base));
            if std::fs::write(&f, &b).is_ok() { cover_ref = f.to_string_lossy().to_string(); }
        }
    }

    // 歌词
    let mut lrc = d.as_ref().map(|x| x.lyrics.clone()).unwrap_or_default();
    if lrc.trim().is_empty() { lrc = api::lyrics(&song.ssid); }
    let mut lyric_ref = String::new();
    if !lrc.trim().is_empty() {
        let f = sub(LYRIC_DIR).join(format!("{}.lrc", base));
        if std::fs::write(&f, &lrc).is_ok() { lyric_ref = f.to_string_lossy().to_string(); }
    }

    let rec = DownloadRecord {
        ssid: song.ssid.clone(),
        title: song.title.clone(),
        artist: song.artist.clone(),
        album: song.album.clone(),
        quality: quality.to_string(),
        audio: path_str,
        cover: cover_ref,
        lyric: lyric_ref.clone(),
        meta: String::new(),
        time: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0),
    };

    // 歌曲信息
    let meta_text = format!(
        "标题：{}\n歌手：{}\n专辑：{}\nssid：{}\n音质：{}\n音频地址：{}\n封面：{}\n歌词：{}\n下载时间：{}\n\n{}",
        song.title, song.artist, song.album, song.ssid, quality, url,
        song.cover_url.clone().unwrap_or_default(),
        if lyric_ref.is_empty() { "无".to_string() } else { format!("{}.lrc", base) },
        chrono_like(rec.time),
        lrc,
    );
    let meta = sub(META_DIR).join(format!("{}.txt", base));
    let _ = std::fs::write(&meta, meta_text);
    Ok(DownloadRecord { meta: meta.to_string_lossy().to_string(), ..rec })
}

fn chrono_like(ms: i64) -> String {
    // 不引入 chrono：手工格式化本地时间为 YYYY-MM-DD HH:MM:SS
    let secs = ms / 1000;
    let days = secs / 86400;
    let rem = secs % 86400;
    let (y, m, d) = civil_from_days(days + 719163);
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
}

/// 天数 -> 年月日（Howard Hinnant 算法）
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

pub fn read_text(ref_: &str) -> String {
    if ref_.trim().is_empty() { return String::new(); }
    std::fs::read_to_string(ref_).unwrap_or_default()
}

pub fn delete(rec: &DownloadRecord) {
    for p in [&rec.audio, &rec.cover, &rec.lyric, &rec.meta] {
        if p.trim().is_empty() { continue; }
        let _ = std::fs::remove_file(p);
    }
}
