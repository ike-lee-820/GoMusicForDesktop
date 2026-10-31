//! 本站（gomusic.myocd.de5.net）接口。逻辑对齐 Kotlin 桌面端 Data.kt 的 Api。

use crate::models::*;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use reqwest::blocking::Client;
use serde_json::Value;
use std::collections::HashMap;
use std::time::Duration;

pub const BASE: &str = "https://gomusic.myocd.de5.net";

/// 音质从低到高。本地曲库键与网易云档位都要列进来，
/// 否则网易云的键 rank 一律 99，音质菜单顺序会乱
const ORDER: &[&str] = &[
    "low", "saver", "economy", "lite",
    "standard", "normal",
    "higher", "high",
    "exhigh", "extreme",
    "lossless", "source",
    "hires", "jyeffect", "dolby", "vivid", "sky", "jymaster",
];

pub fn rank(k: &str) -> i32 {
    ORDER.iter().position(|x| *x == k).map(|i| i as i32).unwrap_or(99)
}

/// 补全相对地址
pub fn abs(u: &str) -> String {
    if u.starts_with("http") || u.starts_with("data:") { u.to_string() }
    else if u.starts_with('/') { format!("{}{}", BASE, u) }
    else { format!("{}/{}", BASE, u) }
}

struct Api {
    client: Client,
    probe: Client,
    detail_cache: Mutex<HashMap<String, SongDetail>>,
}

impl Api {
    fn new() -> Self {
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(20))
            .timeout(Duration::from_secs(60))
            .build()
            .unwrap_or_else(|_| Client::new());
        let probe = Client::builder()
            .connect_timeout(Duration::from_secs(6))
            .timeout(Duration::from_secs(15))
            .build()
            .unwrap_or_else(|_| Client::new());
        Api { client, probe, detail_cache: Mutex::new(HashMap::new()) }
    }

    fn get(&self, path: &str, tries: usize, quick: bool) -> Result<String, String> {
        let url = format!("{}{}", BASE, path);
        let mut last = String::from("请求失败");
        for i in 0..tries {
            let c = if quick { &self.probe } else { &self.client };
            match c.get(&url).header("Connection", "close").send() {
                Ok(r) => {
                    if !r.status().is_success() {
                        last = format!("HTTP {}", r.status());
                    } else {
                        return r.text().map_err(|e| e.to_string());
                    }
                }
                Err(e) => last = e.to_string(),
            }
            if i + 1 < tries { std::thread::sleep(Duration::from_millis(500 * (i as u64 + 1))); }
        }
        Err(last)
    }
}

static API: Lazy<Api> = Lazy::new(Api::new);

/// 封面图统一走 CORS 反代。
///
/// WebView 直接请求图床常被拒（403 / 缺 CORS 头），CSS background-image
/// 拿不到图就一直空白。反代带正确的响应头，图片才能显示出来。
pub const IMG_PROXY: &str = "https://api.ocd.ccwu.cc/?url=";

fn pct_encode(s: &str) -> String {
    let mut o = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => o.push(b as char),
            _ => o.push_str(&format!("%{:02X}", b)),
        }
    }
    o
}

/// 把图片地址包进反代；已经是反代地址或不是 http 就原样返回
pub fn img_proxy(u: String) -> String {
    if !u.starts_with("http") { return u; }
    if u.starts_with(IMG_PROXY) { return u; }
    format!("{}{}", IMG_PROXY, pct_encode(&u))
}

pub fn songs(quick: bool) -> Result<Vec<Song>, String> {
    let t = API.get("/api/songs", if quick { 1 } else { 3 }, quick)?;
    serde_json::from_str(&t).map_err(|e| e.to_string())
}

pub fn artists() -> HashMap<String, ArtistInfo> {
    match API.get("/api/artists", 2, false) {
        Ok(t) => serde_json::from_str(&t).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

pub fn lyrics(ssid: &str) -> String {
    API.get(&format!("/api/lyrics/{}", ssid), 3, false).unwrap_or_default()
}

pub fn detail(ssid: &str) -> Result<SongDetail, String> {
    {
        let c = API.detail_cache.lock();
        if let Some(d) = c.get(ssid) { return Ok(d.clone()); }
    }
    let t = API.get(&format!("/api/song/{}", ssid), 3, false)?;
    let v: Value = serde_json::from_str(&t).map_err(|e| e.to_string())?;
    let mut audio: HashMap<String, Vec<String>> = HashMap::new();
    if let Some(a) = v.get("audio").and_then(|x| x.as_object()) {
        for (k, val) in a {
            let list: Vec<String> = match val {
                Value::String(s) => vec![s.clone()],
                Value::Array(arr) => arr.iter().filter_map(|x| x.as_str().map(|s| s.to_string())).collect(),
                _ => Vec::new(),
            };
            if !list.is_empty() {
                audio.insert(k.clone(), list.into_iter().map(|u| abs(&u)).collect());
            }
        }
    }
    let pick = |keys: &[&str]| -> String {
        keys.iter().filter_map(|k| v.get(k).and_then(|x| x.as_str())).find(|s| !s.trim().is_empty()).unwrap_or("").to_string()
    };
    let d = SongDetail {
        ssid: ssid.to_string(),
        lyrics: pick(&["lyrics", "lrc"]),
        cover: {
            let c = pick(&["cover", "coverUrl"]);
            if c.is_empty() { None } else { Some(abs(&c)) }
        },
        audio,
    };
    API.detail_cache.lock().insert(ssid.to_string(), d.clone());
    Ok(d)
}

pub fn clear_cache() { API.detail_cache.lock().clear(); }

/// 挑最接近目标音质的地址（多段只取第一段）
pub fn audio_url(ssid: &str, quality: &str) -> Result<String, String> {
    let d = detail(ssid)?;
    if d.audio.is_empty() { return Err("没有可用音频".to_string()); }
    let target = rank(quality);
    let key = d.audio.keys().min_by_key(|k| (rank(k) - target).abs()).cloned()
        .ok_or_else(|| "没有可用音频".to_string())?;
    d.audio.get(&key).and_then(|v| v.first()).cloned()
        .ok_or_else(|| "没有可用音频".to_string())
}

/// 下载到文件；进度回调 (已读字节, 总字节)
pub fn fetch_to_file(url: &str, path: &str, mut on_progress: Option<&mut dyn FnMut(u64, u64)>)
    -> Result<(), String> {
    let mut r = API.client.get(url).header("Connection", "close").send().map_err(|e| e.to_string())?;
    if !r.status().is_success() { return Err(format!("HTTP {}", r.status())); }
    let total = r.content_length().unwrap_or(0);
    let mut file = std::fs::File::create(path).map_err(|e| e.to_string())?;
    let mut done: u64 = 0;
    let mut last: u64 = 0;
    loop {
        let mut buf = [0u8; 64 * 1024];
        use std::io::{Read, Write};
        let n = match r.read(&mut buf) { Ok(0) => break, Ok(n) => n, Err(_) => break };
        file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        done += n as u64;
        if let Some(cb) = on_progress.as_deref_mut() {
            if done - last >= 128 * 1024 { last = done; cb(done, total); }
        }
    }
    use std::io::Write;
    let _ = file.flush();
    if let Some(cb) = on_progress.as_deref_mut() { cb(done, total); }
    Ok(())
}

pub fn bytes(url: &str) -> Option<Vec<u8>> {
    let mut r = API.client.get(url).header("Connection", "close").send().ok()?;
    if !r.status().is_success() { return None; }
    let mut v = Vec::new();
    use std::io::Read;
    r.read_to_end(&mut v).ok()?;
    Some(v)
}

/// 仓库内「专辑名/cover.webp」即该专辑封面，列表没给封面时用这个兜底
pub fn guess_cover_url(s: &Song) -> Option<String> {
    let repo = s.repo_name.clone().filter(|x| !x.trim().is_empty())?;
    let owner = s.owner.clone().filter(|x| !x.trim().is_empty())?;
    let album = if s.album.trim().is_empty() { return None; } else { s.album.clone() };
    Some(img_proxy(format!(
        "{}/api/proxy/raw/{}/{}/cover.webp?o={}", BASE, repo, enc_path(&album), enc_query(&owner)
    )))
}

fn enc_path(s: &str) -> String { crate::netease::enc_str(s) }
fn enc_query(s: &str) -> String { crate::netease::enc_str(s) }

/// 图片统一转成可加载的引用：data: URI 与 http 保持原样，补全相对地址
pub fn cover_ref(u: Option<&str>) -> Option<String> {
    let u = u?;
    if u.trim().is_empty() { return None; }
    if u.starts_with("data:") || u.starts_with("http") { return Some(u.to_string()); }
    if u.starts_with('/') { return Some(abs(u)); }
    Some(u.to_string()) // 本地文件路径
}

pub fn decode_data_uri(u: &str) -> Option<Vec<u8>> {
    let i = u.find("base64,")?;
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(&u[i + 7..]).ok()
}
