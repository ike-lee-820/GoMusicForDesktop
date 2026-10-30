//! 数据模型：与 Kotlin 桌面端一一对应，字段名保持 camelCase，前端直接可用。

use serde::{Deserialize, Serialize};

pub const SRC_LOCAL: &str = "local";
pub const SRC_NETEASE: &str = "netease";
pub const NM_PREFIX: &str = "nm";

/// 网易云可选音质档位（从低到高）
pub const NM_LEVELS: &[&str] = &[
    "standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby", "vivid", "jymaster", "sky",
];

pub fn nm_level_label(lv: &str) -> String {
    let s = match lv {
        "standard" => "标准 128k",
        "higher" => "较高 192k",
        "exhigh" => "极高 320k",
        "lossless" => "无损 FLAC",
        "hires" => "Hi-Res",
        "jyeffect" => "高清臻音",
        "dolby" => "杜比全景声",
        "vivid" => "臻音全景声",
        "jymaster" => "超清母带",
        "sky" => "沉浸环绕声",
        other => other,
    };
    s.to_string()
}

/// 音质中文名：本地曲库键与网易云档位都要覆盖，
/// 否则菜单里会直接显示 exhigh 这类原始键
pub fn quality_label(k: &str) -> String {
    let s = match k {
        "lossless" => "无损",
        "high" => "高品质",
        "normal" => "标准",
        "low" | "saver" | "economy" | "lite" => "省流",
        "extreme" => "极高",
        "source" => "原声",
        "standard" => "标准 128k",
        "higher" => "较高 192k",
        "exhigh" => "极高 320k",
        "hires" => "Hi-Res",
        "jyeffect" => "高清臻音",
        "dolby" => "杜比全景声",
        "vivid" => "臻音全景声",
        "jymaster" => "超清母带",
        "sky" => "沉浸环绕声",
        other => other,
    };
    s.to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Song {
    pub ssid: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub repo_name: Option<String>,
    #[serde(default)]
    pub owner: Option<String>,
    #[serde(default)]
    pub locked: Option<bool>,
    #[serde(default = "default_source")]
    pub source: String,
    #[serde(default)]
    pub nm_id: i64,
    #[serde(default)]
    pub nm_album_id: i64,
    #[serde(default)]
    pub nm_artist_id: i64,
    /// 网易云 MV id，0 表示没有 MV
    #[serde(default)]
    pub mv_id: i64,
}

fn default_source() -> String { SRC_LOCAL.to_string() }

impl Default for Song {
    fn default() -> Self {
        Song {
            ssid: String::new(), title: String::new(), artist: String::new(), album: String::new(),
            cover_url: None, repo_name: None, owner: None, locked: None,
            source: SRC_LOCAL.to_string(), nm_id: 0, nm_album_id: 0, nm_artist_id: 0, mv_id: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArtistInfo {
    pub image: Option<String>,
    pub bio: Option<String>,
}

/// 歌曲详情：歌词 + 封面 + 音质地址表
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SongDetail {
    pub ssid: String,
    pub lyrics: String,
    pub cover: Option<String>,
    /// 音质 -> 播放地址数组（多段文件按顺序）
    pub audio: std::collections::HashMap<String, Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LrcLine {
    pub time_ms: i64,
    pub text: String,
}

/// 专辑 / 歌手 / 歌单卡片
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Group {
    pub title: String,
    #[serde(default)]
    pub subtitle: String,
    #[serde(default)]
    pub cover: Option<String>,
    #[serde(default)]
    pub songs: Vec<Song>,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub album: String,
    #[serde(default = "default_source")]
    pub source: String,
    #[serde(default)]
    pub nm_id: i64,
    #[serde(default)]
    pub nm_kind: String,
}

/// 下载进度（界面底部进度条）
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DlStatus {
    pub title: String,
    pub index: i32,
    pub total: i32,
    pub done: i64,
    pub size: i64,
    pub bps: i64,
}

impl DlStatus {
    pub fn percent(&self) -> i32 {
        if self.size > 0 { ((self.done * 100) / self.size).clamp(0, 100) as i32 } else { -1 }
    }
}

pub fn is_netease(ssid: &str) -> bool { ssid.starts_with(NM_PREFIX) }

pub fn netease_id_of(ssid: &str) -> Option<i64> {
    ssid.strip_prefix(NM_PREFIX)?.parse::<i64>().ok()
}

pub fn fmt_ms(ms: i64) -> String {
    let s = if ms < 0 { 0 } else { ms } / 1000;
    format!("{:02}:{:02}", s / 60, s % 60)
}

pub fn size_text(bytes: i64) -> String {
    let b = bytes as f64;
    if bytes < 1024 { return format!("{} B", bytes); }
    let kb = b / 1024.0;
    if kb < 1024.0 { return format!("{:.0} KB", kb); }
    let mb = kb / 1024.0;
    if mb < 1024.0 { return format!("{:.1} MB", mb); }
    format!("{:.2} GB", mb / 1024.0)
}

pub fn speed_text(bps: i64) -> String {
    if bps <= 0 { String::new() } else { format!("{}/s", size_text(bps)) }
}

/// 解析 LRC：支持 [mm:ss] 与 [mm:ss.xx]
pub fn parse_lrc(t: &str) -> Vec<LrcLine> {
    let mut out: Vec<LrcLine> = Vec::new();
    for line in t.lines() {
        let bytes = line.as_bytes();
        let mut i = 0usize;
        let mut stamps: Vec<i64> = Vec::new();
        let mut last_end = 0usize;
        while i < bytes.len() {
            if bytes[i] == b'[' {
                if let Some(end) = line[i..].find(']') {
                    let inner = &line[i + 1..i + end];
                    if let Some(ms) = parse_stamp(inner) {
                        stamps.push(ms);
                        last_end = i + end + 1;
                        i = last_end;
                        continue;
                    }
                }
            }
            i += 1;
        }
        if stamps.is_empty() { continue; }
        let text = line[last_end..].trim().to_string();
        if text.is_empty() { continue; }
        for ms in stamps { out.push(LrcLine { time_ms: ms, text: text.clone() }); }
    }
    out.sort_by_key(|l| l.time_ms);
    out
}

fn parse_stamp(s: &str) -> Option<i64> {
    let (mm, rest) = s.split_once(':')?;
    let mm: i64 = mm.trim().parse().ok()?;
    let (ss, frac) = match rest.split_once('.') {
        Some((a, b)) => (a, Some(b)),
        None => (rest, None),
    };
    let ss: i64 = ss.trim().parse().ok()?;
    let ms = match frac {
        Some(f) => {
            let f = f.trim();
            if f.is_empty() { 0 } else {
                let pad = format!("{:0<3}", &f[..f.len().min(3)]);
                pad.parse::<i64>().unwrap_or(0)
            }
        }
        None => 0,
    };
    if ss > 59 { return None; }
    Some(mm * 60000 + ss * 1000 + ms)
}

/// 有时间戳按 LRC 解析，否则当作纯文本逐行显示（timeMs = -1）
pub fn lyric_lines(t: &str) -> Vec<LrcLine> {
    let p = parse_lrc(t);
    if !p.is_empty() { return p; }
    t.lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty())
        .map(|l| LrcLine { time_ms: -1, text: l.to_string() })
        .collect()
}

impl Default for Group {
    fn default() -> Self {
        Group {
            title: String::new(), subtitle: String::new(), cover: None, songs: Vec::new(),
            artist: String::new(), album: String::new(), source: SRC_LOCAL.to_string(),
            nm_id: 0, nm_kind: String::new(),
        }
    }
}
