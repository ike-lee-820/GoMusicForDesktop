//! 网易云接口。逻辑与 Kotlin 桌面端 Netease.kt 一一对应。
//!
//! 几个关键点（都是踩过的坑，改动前先看这里）：
//! - cookie 必须百分号编码（空格 -> %20）。用 URLEncoder 那套「空格变 +」
//!   会让服务端解析失败，表现是「登录了却取不到播放地址」。
//! - 所有请求带 unblock=true 解灰；/song/url/v1 不支持自定义解灰源，
//!   拿不到时要走 /song/url/match。
//! - 取播放地址并发探测「档位 × 3 种参数」，不要改成串行（会退化成几十个 RTT）。

use crate::models::*;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use reqwest::blocking::Client;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

pub const NM_BASE: &str = "https://mapi.myocd.de5.net";
/// realIP 兜底用的国内 IP（网易对境外 IP 会返回 460 cheating）
const FALLBACK_CN_IP: &str = "116.25.146.177";
const URL_TTL: Duration = Duration::from_secs(10 * 60);

/// 百分号编码：只保留 A-Za-z0-9 与 -_.!~*'()，其余按 UTF-8 字节编码成 %XX
fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 16);
    for b in s.as_bytes() {
        let c = *b;
        let keep = c.is_ascii_alphanumeric()
            || matches!(c, b'-' | b'_' | b'.' | b'!' | b'~' | b'*' | b'\'' | b'(' | b')');
        if keep {
            out.push(c as char);
        } else {
            out.push_str(&format!("%{:02X}", c));
        }
    }
    out
}

/// 供 api.rs 拼 URL 用（与请求里的编码一致）
pub fn enc_str(s: &str) -> String { enc(s) }

// ---------------- JSON 取值小工具 ----------------

fn obj<'a>(v: &'a Value, k: &str) -> Option<&'a Value> { v.get(k).filter(|x| x.is_object()) }

/// 依次尝试多个键，取第一个非空字符串
fn s(v: &Value, keys: &[&str]) -> Option<String> {
    for k in keys {
        if let Some(x) = v.get(k) {
            if let Some(t) = x.as_str() {
                if !t.trim().is_empty() { return Some(t.to_string()); }
            }
        }
    }
    None
}

fn code_of(v: &Value) -> i64 {
    v.get("code").and_then(|x| x.as_i64())
        .or_else(|| v.get("code").and_then(|x| x.as_f64()).map(|f| f as i64))
        .unwrap_or(-1)
}

fn id_of(v: &Value) -> i64 {
    v.get("id")
        .and_then(|x| x.as_i64())
        .or_else(|| v.get("id").and_then(|x| x.as_f64()).map(|f| f as i64))
        .unwrap_or(0)
}

/// 搜索结果的数组：兼容 result.xxx 与顶层 xxx 两种结构
fn result_array(root: &Value, key: &str) -> Vec<Value> {
    if let Some(r) = obj(root, "result") {
        if let Some(a) = r.get(key).and_then(|x| x.as_array()) { return a.clone(); }
    }
    if let Some(a) = root.get(key).and_then(|x| x.as_array()) { return a.clone(); }
    Vec::new()
}

/// 封面缩略图：网易云图片支持 ?param=宽y高
fn pic(u: Option<String>, size: u32) -> Option<String> {
    let u = u?;
    if u.trim().is_empty() || !u.starts_with("http") { return None; }
    Some(if u.contains('?') { u } else { format!("{}?param={}y{}", u, size, size) })
}

fn artists_of(o: &Value) -> String {
    for k in ["ar", "artists"] {
        if let Some(a) = o.get(k).and_then(|x| x.as_array()) {
            let v: Vec<String> = a.iter().filter_map(|x| s(x, &["name"])).collect();
            if !v.is_empty() { return v.join("/"); }
        }
    }
    String::new()
}

fn album_name_of(o: &Value) -> String {
    obj(o, "al").and_then(|x| s(x, &["name"]))
        .or_else(|| obj(o, "album").and_then(|x| s(x, &["name"])))
        .unwrap_or_default()
}

fn cover_of(o: &Value) -> Option<String> {
    pic(obj(o, "al").and_then(|x| s(x, &["picUrl"])), 300)
        .or_else(|| pic(obj(o, "album").and_then(|x| s(x, &["picUrl", "blurPicUrl", "pic"])), 300))
        .or_else(|| pic(s(o, &["picUrl", "img1v1Url", "cover"]), 300))
}

fn to_song(o: &Value) -> Option<Song> {
    let id = id_of(o);
    if id <= 0 { return None; }
    let al = obj(o, "al").or_else(|| obj(o, "album"));
    let ar = o.get("ar").and_then(|x| x.as_array()).and_then(|a| a.first())
        .or_else(|| o.get("artists").and_then(|x| x.as_array()).and_then(|a| a.first()));
    Some(Song {
        ssid: format!("{}{}", NM_PREFIX, id),
        title: s(o, &["name"]).unwrap_or_default(),
        artist: artists_of(o),
        album: album_name_of(o),
        cover_url: cover_of(o),
        source: SRC_NETEASE.to_string(),
        nm_id: id,
        nm_album_id: al.map(id_of).unwrap_or(0),
        nm_artist_id: ar.map(id_of).unwrap_or(0),
        mv_id: mv_id_of(o),
        ..Default::default()
    })
}

/// MV id：搜索结果里常是 0，只有 /song/detail 才给真值，所以 enrich 时补
fn mv_id_of(o: &Value) -> i64 {
    let v = o.get("mv").and_then(|x| x.as_i64())
        .or_else(|| o.get("mvid").and_then(|x| x.as_i64()))
        .or_else(|| o.get("mvId").and_then(|x| x.as_i64()))
        .unwrap_or(0);
    if v > 0 { return v; }
    // 有些接口把 mv 包成对象 {"id": ...}
    obj(o, "mv").map(id_of).unwrap_or(0)
}

fn first_of_data(v: &Value) -> Option<Value> {
    v.get("data")
        .and_then(|x| x.as_array())
        .and_then(|a| a.first())
        .cloned()
}

fn url_of_hit(v: &Value) -> Option<String> {
    let u = s(v, &["url"]);
    match u {
        Some(u) if !u.is_empty() => Some(u),
        _ => {
            // 试听片段：freeTrialInfo.url
            let t = obj(v, "freeTrialInfo").and_then(|x| s(x, &["url"]))?;
            if t.starts_with("http") { Some(t) } else { None }
        }
    }
}

// ---------------- 客户端 ----------------

pub struct Nm {
    client: Client,
    url_client: Client,
    cookie: Mutex<String>,
    cache: Mutex<HashMap<String, (String, Instant)>>,
}

impl Nm {
    fn new() -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(40))
            .build()
            .unwrap_or_else(|_| Client::new());
        let url_client = Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(12))
            .build()
            .unwrap_or_else(|_| Client::new());
        Nm { client, url_client, cookie: Mutex::new(String::new()), cache: Mutex::new(HashMap::new()) }
    }

    pub fn set_cookie(&self, c: String) { *self.cookie.lock() = c; self.cache.lock().clear(); }
    pub fn cookie(&self) -> String { self.cookie.lock().clone() }

    fn build_url(&self, path: &str, params: &[(&str, String)]) -> String {
        let mut u = format!("{}{}?", NM_BASE, path);
        for (k, v) in params { u.push_str(k); u.push('='); u.push_str(&enc(v)); u.push('&'); }
        u.push_str("unblock=true&");
        let ck = self.cookie();
        if !ck.trim().is_empty() {
            u.push_str("cookie=");
            u.push_str(&enc(&ck));
            u.push('&');
        }
        u.push_str("timestamp=");
        u.push_str(&std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0).to_string());
        u
    }

    /// 带重试的 GET；失败返回空对象，绝不让异常冒出去
    fn get(&self, path: &str, params: &[(&str, String)]) -> Value {
        let url = self.build_url(path, params);
        let delays = [400u64, 900, 1600];
        for i in 0..=delays.len() {
            match self.client.get(&url).header("Connection", "close").send() {
                Ok(r) => {
                    if !r.status().is_success() { continue; }
                    let t = r.text().unwrap_or_default();
                    if t.trim().is_empty() { continue; }
                    if let Ok(v) = serde_json::from_str::<Value>(&t) {
                        if v.is_object() { return v; }
                    }
                }
                Err(_) => {}
            }
            if i < delays.len() { std::thread::sleep(Duration::from_millis(delays[i])); }
        }
        Value::Object(Default::default())
    }

    /// 单次请求：不重试、不 sleep。取地址要快，拿不到立刻换下一种
    fn get_once(&self, path: &str, params: &[(&str, String)]) -> Option<Value> {
        let url = self.build_url(path, params);
        let r = self.url_client.get(&url).header("Connection", "close").send().ok()?;
        if !r.status().is_success() { return None; }
        let t = r.text().ok()?;
        if t.trim().is_empty() { return None; }
        serde_json::from_str::<Value>(&t).ok().filter(|v| v.is_object())
    }

    /// 本站音质键 -> 网易云 level（从高到低，逐个尝试直到拿到地址）
    fn levels(quality: &str) -> Vec<String> {
        match quality {
            "lossless" => vec!["lossless", "exhigh", "higher", "standard"],
            "high" => vec!["exhigh", "higher", "standard"],
            "normal" => vec!["higher", "standard"],
            _ => vec!["standard"],
        }
        .into_iter()
        .map(|s| s.to_string())
        .collect()
    }

    /// /song/detail?ids=1,2,3 -> id -> 歌曲
    fn song_detail(&self, ids: &[i64]) -> HashMap<i64, Song> {
        let ids_s = ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
        let root = self.get("/song/detail", &[("ids", ids_s)]);
        let arr = result_array(&root, "songs");
        let mut m = HashMap::new();
        for o in arr {
            if let Some(sg) = to_song(&o) {
                if sg.nm_id > 0 { m.insert(sg.nm_id, sg); }
            }
        }
        m
    }

    /// 用 /song/detail 补全信息：搜索接口返回的 al.picUrl 经常是空的
    pub fn enrich(&self, songs: Vec<Song>) -> Vec<Song> {
        let ids: Vec<i64> = songs.iter().map(|s| s.nm_id).filter(|i| *i > 0).collect();
        if ids.is_empty() { return songs; }
        let mut uniq: Vec<i64> = Vec::new();
        let mut seen = HashSet::new();
        for i in ids { if seen.insert(i) { uniq.push(i); } }
        let detail = self.song_detail(&uniq);
        if detail.is_empty() { return songs; }
        songs.into_iter().map(|s| {
            match detail.get(&s.nm_id) {
                None => s,
                Some(d) => Song {
                    title: if d.title.trim().is_empty() { s.title } else { d.title.clone() },
                    artist: if d.artist.trim().is_empty() { s.artist } else { d.artist.clone() },
                    album: if d.album.trim().is_empty() { s.album } else { d.album.clone() },
                    cover_url: d.cover_url.clone().or(s.cover_url),
                    // MV 只有详情接口给真值，补上后播放页才能立刻显示 MV 按钮
                    mv_id: if s.mv_id > 0 { s.mv_id } else { d.mv_id },
                    ..s
                },
            }
        }).collect()
    }

    pub fn detail_of(&self, id: i64) -> Option<Song> { self.song_detail(&[id]).remove(&id) }

    // ---------------- 搜索 ----------------

    fn raw_search(&self, keywords: &str, ty: i32, limit: i32, offset: i32) -> Value {
        self.get("/search", &[
            ("keywords", keywords.to_string()),
            ("type", ty.to_string()),
            ("limit", limit.to_string()),
            ("offset", offset.to_string()),
        ])
    }

    pub fn search_songs(&self, keywords: &str, limit: i32, offset: i32) -> Vec<Song> {
        let root = self.raw_search(keywords, 1, limit, offset);
        let arr = result_array(&root, "songs");
        let list: Vec<Song> = arr.iter().filter_map(to_song).collect();
        self.enrich(list)
    }

    pub fn search_albums(&self, keywords: &str, limit: i32, offset: i32) -> Vec<Group> {
        let root = self.raw_search(keywords, 10, limit, offset);
        let arr = result_array(&root, "albums");
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let id = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            let ar = obj(&o, "artist").or_else(|| obj(&o, "ar"));
            out.push(Group {
                title: name.clone(),
                subtitle: ar.and_then(|x| s(x, &["name"])).unwrap_or_default(),
                cover: pic(s(&o, &["picUrl", "blurPicUrl", "pic"]), 300),
                artist: ar.and_then(|x| s(x, &["name"])).unwrap_or_default(),
                album: name,
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "album".to_string(),
                ..Default::default()
            });
        }
        out
    }

    pub fn search_artists(&self, keywords: &str, limit: i32, offset: i32) -> Vec<Group> {
        let root = self.raw_search(keywords, 100, limit, offset);
        let arr = result_array(&root, "artists");
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let id = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            out.push(Group {
                title: name.clone(),
                subtitle: "网易云歌手".to_string(),
                cover: pic(s(&o, &["picUrl", "img1v1Url", "pic"]), 300),
                artist: name,
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "artist".to_string(),
                ..Default::default()
            });
        }
        out
    }

    // ---------------- 播放地址 ----------------

    /// 并发探测：把「档位 × 3 种参数」的请求一起发出去
    fn probe_levels(&self, id: i64, levels: &[String]) -> Vec<(String, String)> {
        let mut jobs: Vec<(String, Vec<(&'static str, String)>)> = Vec::new();
        for lv in levels {
            let l = lv.clone();
            jobs.push((l.clone(), vec![("id", id.to_string()), ("level", l.clone())]));
            jobs.push((l.clone(), vec![("id", id.to_string()), ("level", l.clone()), ("randomCNIP", "true".to_string())]));
            jobs.push((l, vec![("id", id.to_string()), ("level", lv.clone()), ("realIP", FALLBACK_CN_IP.to_string())]));
        }
        // 并发：耗时从「所有请求之和」变成「最慢那一个」
        let handles: Vec<_> = jobs.into_iter().map(|(lv, ps)| {
            let ps: Vec<(&'static str, String)> = ps;
            std::thread::spawn(move || {
                let nm = netease();
                let r = nm.get_once("/song/url/v1", &ps)?;
                let first = first_of_data(&r)?;
                let u = url_of_hit(&first)?;
                if u.is_empty() { None } else { Some((lv, u)) }
            })
        }).collect();
        handles.into_iter().filter_map(|h| h.join().ok().flatten()).collect()
    }

    fn resolve_audio_url(&self, id: i64, quality: &str) -> Option<String> {
        let cands = Self::levels(quality);
        let hits = self.probe_levels(id, &cands);
        // 按档位优先级取能用的最高音质
        for lv in &cands {
            if let Some((_, u)) = hits.iter().find(|(l, _)| l == lv) { return Some(u.clone()); }
        }
        // v1 拿不到时走 /song/url/match 解灰匹配
        if let Some(r) = self.get_once("/song/url/match", &[("id", id.to_string())]) {
            if let Some(first) = first_of_data(&r) {
                if let Some(u) = url_of_hit(&first) { return Some(u); }
            }
        }
        // 兜底：拿游客 cookie 再试一次
        if let Some(anon) = self.anon_cookie() {
            let saved = self.cookie();
            self.set_cookie(anon);
            let r = self.probe_levels(id, &Self::levels(quality));
            self.set_cookie(saved);
            if let Some((_, u)) = r.into_iter().next() { return Some(u); }
        }
        None
    }

    pub fn audio_url(&self, id: i64, quality: &str) -> Option<String> {
        let key = format!("{}|{}|{}", id, quality, self.cookie().chars().take(8).collect::<String>());
        {
            let c = self.cache.lock();
            if let Some((u, t)) = c.get(&key) {
                if t.elapsed() < URL_TTL { return Some(u.clone()); }
            }
        }
        let u = self.resolve_audio_url(id, quality);
        if let Some(u) = &u {
            self.cache.lock().insert(key, (u.clone(), Instant::now()));
        }
        u
    }

    /// 探测这首歌实际有哪些音质
    pub fn available_levels(&self, id: i64) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for lv in NM_LEVELS {
            let r = self.get("/song/url/v1", &[("id", id.to_string()), ("level", (*lv).to_string())]);
            if code_of(&r) != 200 { continue; }
            if let Some(first) = first_of_data(&r) {
                if url_of_hit(&first).is_some() { out.push((*lv).to_string()); }
            }
        }
        out
    }

    /// 游客 cookie：未登录状态下部分接口会报「需要验证」，用它规避
    pub fn anon_cookie(&self) -> Option<String> {
        let r = self.get("/register/anonimous", &[]);
        s(&r, &["cookie"]).filter(|c| c.contains('=')).or_else(|| find_cookie(&r))
    }

    // ---------------- 歌词 / 列表 ----------------

    pub fn lyric(&self, id: i64) -> String {
        let r = self.get("/lyric", &[("id", id.to_string())]);
        obj(&r, "lrc").and_then(|x| s(x, &["lyric"])).unwrap_or_default()
    }

    /// 专辑 / 歌手 / 歌单的歌曲列表
    pub fn songs_of(&self, kind: &str, id: i64, limit: i32, offset: i32) -> Vec<Song> {
        if kind == "playlist" {
            let r = self.get("/playlist/detail", &[("id", id.to_string())]);
            let arr = obj(&r, "playlist")
                .and_then(|p| p.get("tracks"))
                .and_then(|x| x.as_array())
                .cloned()
                .unwrap_or_else(|| result_array(&r, "tracks"));
            let list: Vec<Song> = arr.iter().filter_map(to_song).collect();
            return self.enrich(list);
        }
        let root = if kind == "album" {
            self.get("/album", &[("id", id.to_string())])
        } else {
            self.get("/artist/songs", &[
                ("id", id.to_string()),
                ("limit", limit.to_string()),
                ("offset", offset.to_string()),
            ])
        };
        let mut arr = result_array(&root, "songs");
        if arr.is_empty() { arr = result_array(&root, "hotSongs"); }
        let list: Vec<Song> = arr.iter().filter_map(to_song).collect();
        self.enrich(list)
    }

    pub fn artist_albums(&self, id: i64, limit: i32, offset: i32) -> Vec<Group> {
        let root = self.get("/artist/album", &[
            ("id", id.to_string()),
            ("limit", limit.to_string()),
            ("offset", offset.to_string()),
        ]);
        let mut arr = result_array(&root, "hotAlbums");
        if arr.is_empty() { arr = result_array(&root, "albums"); }
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let aid = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if aid <= 0 || name.trim().is_empty() || !seen.insert(aid) { continue; }
            let ar = obj(&o, "artist").or_else(|| obj(&o, "ar"));
            out.push(Group {
                title: name.clone(),
                subtitle: ar.and_then(|x| s(x, &["name"])).unwrap_or_default(),
                cover: pic(s(&o, &["picUrl", "blurPicUrl", "pic"]), 300),
                artist: ar.and_then(|x| s(x, &["name"])).unwrap_or_default(),
                album: name,
                source: SRC_NETEASE.to_string(),
                nm_id: aid,
                nm_kind: "album".to_string(),
                ..Default::default()
            });
        }
        out
    }

    /// 歌手详情：拿歌手自己的头像，避免误用专辑封面
    pub fn artist_detail(&self, id: i64) -> Option<Group> {
        let root = self.get("/artist/detail", &[("id", id.to_string())]);
        let d = obj(&root, "data")?;
        let a = obj(d, "artist")?;
        let aid = id_of(a);
        let name = s(a, &["name"]).unwrap_or_default();
        if aid <= 0 || name.trim().is_empty() { return None; }
        Some(Group {
            title: name.clone(),
            subtitle: "网易云歌手".to_string(),
            cover: pic(s(a, &["picUrl", "img1v1Url", "cover"]), 300),
            artist: name,
            source: SRC_NETEASE.to_string(),
            nm_id: aid,
            nm_kind: "artist".to_string(),
            ..Default::default()
        })
    }

    /// 专辑详情：拿歌手名，让专辑页副标题显示真实歌手
    pub fn album_artist(&self, id: i64) -> Option<String> {
        let root = self.get("/album", &[("id", id.to_string())]);
        let d = obj(&root, "album")?;
        if let Some(n) = obj(d, "artist").and_then(|x| s(x, &["name"])) {
            if !n.trim().is_empty() { return Some(n); }
        }
        d.get("artists").and_then(|x| x.as_array())
            .and_then(|a| a.iter().filter_map(|o| s(o, &["name"])).find(|n| !n.trim().is_empty()))
    }

    // ---------------- 首页 ----------------

    pub fn top_list(&self) -> Vec<Group> {
        let root = self.get("/toplist", &[]);
        let arr = result_array(&root, "list");
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let id = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            out.push(Group {
                title: name,
                subtitle: s(&o, &["updateFrequency"]).unwrap_or_default(),
                cover: pic(s(&o, &["coverImgUrl", "picUrl", "cover"]), 300),
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "playlist".to_string(),
                ..Default::default()
            });
        }
        out
    }

    pub fn top_artists(&self, limit: i32, offset: i32) -> Vec<Group> {
        let root = self.get("/top/artists", &[("offset", offset.to_string()), ("limit", limit.to_string())]);
        let mut arr = result_array(&root, "artists");
        if arr.is_empty() { arr = result_array(&root, "list"); }
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let id = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            out.push(Group {
                title: name.clone(),
                subtitle: "网易云歌手".to_string(),
                cover: pic(s(&o, &["picUrl", "img1v1Url", "pic"]), 300),
                artist: name,
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "artist".to_string(),
                ..Default::default()
            });
        }
        out
    }

    pub fn newest_albums(&self, limit: i32) -> Vec<Group> {
        let root = self.get("/album/newest", &[]);
        let arr = result_array(&root, "albums");
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr.iter().take(limit as usize) {
            let id = id_of(o);
            let name = s(o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            let ar = obj(o, "artist").or_else(|| obj(o, "ar"));
            let artist = ar.and_then(|x| s(x, &["name"])).unwrap_or_default();
            out.push(Group {
                title: name.clone(),
                subtitle: artist.clone(),
                cover: pic(s(o, &["picUrl", "blurPicUrl", "pic"]), 300),
                artist,
                album: name,
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "album".to_string(),
                ..Default::default()
            });
        }
        out
    }

    pub fn recommend_songs(&self) -> Vec<Song> {
        let r = self.get("/recommend/songs/v1", &[]);
        let arr = obj(&r, "data")
            .and_then(|d| d.get("dailySongs"))
            .and_then(|x| x.as_array())
            .cloned()
            .or_else(|| r.get("data").and_then(|x| x.as_array()).cloned())
            .unwrap_or_else(|| result_array(&r, "dailySongs"));
        let list: Vec<Song> = arr.iter().filter_map(to_song).collect();
        self.enrich(list)
    }

    /// 新歌速递：type 0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国
    pub fn top_songs(&self, ty: i32) -> Vec<Song> {
        let r = self.get("/top/song", &[("type", ty.to_string())]);
        let arr = r.get("data").and_then(|x| x.as_array()).cloned()
            .unwrap_or_else(|| result_array(&r, "data"));
        let list: Vec<Song> = arr.iter().filter_map(to_song).collect();
        self.enrich(list)
    }

    pub fn hot_search(&self) -> Vec<String> {
        let r = self.get("/search/hot", &[]);
        let arr = obj(&r, "result").and_then(|x| x.get("hots")).and_then(|x| x.as_array()).cloned()
            .or_else(|| r.get("hots").and_then(|x| x.as_array()).cloned())
            .or_else(|| r.get("data").and_then(|x| x.as_array()).cloned())
            .unwrap_or_default();
        arr.iter().filter_map(|o| s(o, &["first", "searchWord", "name"])).collect()
    }

    // ---------------- 登录 ----------------

    pub fn qr_key(&self) -> Option<String> {
        let r = self.get("/login/qr/key", &[]);
        obj(&r, "data").and_then(|d| s(d, &["unikey", "key"]))
    }

    pub fn qr_image(&self, key: &str) -> Option<String> {
        let r = self.get("/login/qr/create", &[("key", key.to_string()), ("qrimg", "true".to_string())]);
        let img = obj(&r, "data").and_then(|d| s(d, &["qrimg"]))?;
        Some(if img.starts_with("data:") { img } else { format!("data:image/png;base64,{}", img) })
    }

    /// 800 过期 801 等待扫码 802 待确认 803 成功
    pub fn qr_check(&self, key: &str) -> (i64, Option<String>, String) {
        let r = self.get("/login/qr/check", &[("key", key.to_string())]);
        // 文档：扫码后返回 502 需加 noCookie=true
        if code_of(&r) == 502 {
            let r2 = self.get("/login/qr/check", &[("key", key.to_string()), ("noCookie", "true".to_string())]);
            let ck = s(&r2, &["cookie"]).filter(|c| c.contains('=')).or_else(|| find_cookie(&r2));
            return (code_of(&r2), ck, s(&r2, &["message"]).unwrap_or_default());
        }
        let ck = s(&r, &["cookie"]).filter(|c| c.contains('=')).or_else(|| find_cookie(&r));
        (code_of(&r), ck, s(&r, &["message"]).unwrap_or_default())
    }

    pub fn send_captcha(&self, phone: &str) -> bool {
        code_of(&self.get("/captcha/sent", &[("phone", phone.to_string())])) == 200
    }

    pub fn verify_captcha(&self, phone: &str, captcha: &str) -> bool {
        code_of(&self.get("/captcha/verify", &[("phone", phone.to_string()), ("captcha", captcha.to_string())])) == 200
    }

    pub fn login_by_captcha(&self, phone: &str, captcha: &str) -> Option<String> {
        let r = self.get("/login/cellphone", &[("phone", phone.to_string()), ("captcha", captcha.to_string())]);
        if code_of(&r) != 200 { return None; }
        s(&r, &["cookie"]).filter(|c| c.contains('=')).or_else(|| find_cookie(&r))
    }

    pub fn register(&self, phone: &str, password: &str, captcha: &str, nickname: &str) -> Option<String> {
        let r = self.get("/register/cellphone", &[
            ("phone", phone.to_string()),
            ("password", password.to_string()),
            ("captcha", captcha.to_string()),
            ("nickname", nickname.to_string()),
        ]);
        if code_of(&r) != 200 { return None; }
        s(&r, &["cookie"]).filter(|c| c.contains('=')).or_else(|| find_cookie(&r))
    }

    pub fn account_name(&self) -> Option<String> {
        let r = self.get("/user/account", &[]);
        if code_of(&r) != 200 { return None; }
        let p = obj(&r, "profile");
        p.and_then(|x| s(x, &["nickname"]))
            .or_else(|| obj(&r, "account").and_then(|x| s(x, &["userName"])))
            .or_else(|| p.and_then(|x| x.get("userId")).and_then(|v| v.as_i64()).map(|i| i.to_string()))
    }

    pub fn logged_in(&self) -> bool {
        if self.cookie().trim().is_empty() { return false; }
        let r = self.get("/login/status", &[]);
        let d = obj(&r, "data");
        d.and_then(|x| obj(x, "account")).is_some()
            || obj(&r, "account").is_some()
            || obj(&r, "profile").is_some()
    }

    pub fn uid(&self) -> i64 {
        let r = self.get("/user/account", &[]);
        if code_of(&r) != 200 { return 0; }
        obj(&r, "profile").and_then(|p| p.get("userId")).and_then(|v| v.as_i64())
            .or_else(|| obj(&r, "account").and_then(|a| a.get("id")).and_then(|v| v.as_i64()))
            .unwrap_or(0)
    }

    /// 我的网易云歌单：只保留自己创建的（收藏的不属于「我的」）
    pub fn user_playlists(&self, uid: i64) -> Vec<Group> {
        if uid <= 0 { return Vec::new(); }
        let root = self.get("/user/playlist", &[
            ("uid", uid.to_string()),
            ("limit", "1000".to_string()),
            ("offset", "0".to_string()),
        ]);
        let arr = result_array(&root, "playlist");
        let mut out: Vec<Group> = Vec::new();
        let mut seen = HashSet::new();
        for o in arr {
            let id = id_of(&o);
            let name = s(&o, &["name"]).unwrap_or_default();
            if id <= 0 || name.trim().is_empty() || !seen.insert(id) { continue; }
            let creator = obj(&o, "creator").and_then(|c| c.get("userId")).and_then(|v| v.as_i64());
            if let Some(c) = creator { if c != uid { continue; } }
            let cnt = o.get("trackCount").and_then(|v| v.as_i64()).unwrap_or(0);
            out.push(Group {
                title: name,
                subtitle: format!("网易云歌单 · {} 首", cnt),
                cover: pic(s(&o, &["coverImgUrl", "picUrl", "cover"]), 300),
                source: SRC_NETEASE.to_string(),
                nm_id: id,
                nm_kind: "playlist".to_string(),
                ..Default::default()
            });
        }
        out
    }

    pub fn playlist_add(&self, pid: i64, track_ids: &[i64]) -> bool {
        if pid <= 0 || track_ids.is_empty() { return false; }
        let ids = track_ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
        code_of(&self.get("/playlist/tracks", &[
            ("op", "add".to_string()), ("pid", pid.to_string()), ("tracks", ids),
        ])) == 200
    }

    pub fn playlist_del(&self, pid: i64, track_ids: &[i64]) -> bool {
        if pid <= 0 || track_ids.is_empty() { return false; }
        let ids = track_ids.iter().map(|i| i.to_string()).collect::<Vec<_>>().join(",");
        code_of(&self.get("/playlist/tracks", &[
            ("op", "del".to_string()), ("pid", pid.to_string()), ("tracks", ids),
        ])) == 200
    }

    pub fn playlist_rename(&self, pid: i64, name: &str) -> bool {
        if pid <= 0 || name.trim().is_empty() { return false; }
        code_of(&self.get("/playlist/update", &[("id", pid.to_string()), ("name", name.to_string())])) == 200
    }

    /// MV 地址（/mv/url）
    pub fn mv_url(&self, mv_id: i64) -> Option<String> {
        if mv_id <= 0 { return None; }
        let r = self.get("/mv/url", &[("id", mv_id.to_string())]);
        obj(&r, "data").and_then(|d| s(d, &["url"]))
            .or_else(|| s(&r, &["url"]))
            .filter(|u| u.starts_with("http"))
    }
}

/// 递归找 cookie：不同接口返回的位置不一样
fn find_cookie(o: &Value) -> Option<String> {
    for k in ["cookie", "cookies"] {
        if let Some(c) = s(o, &[k]) {
            if c.contains('=') { return Some(c); }
        }
    }
    if let Some(m) = o.as_object() {
        for (_, v) in m {
            if v.is_object() {
                if let Some(c) = find_cookie(v) { return Some(c); }
            }
        }
    }
    None
}

pub static NM: Lazy<Nm> = Lazy::new(Nm::new);
pub fn netease() -> &'static Nm { &NM }
