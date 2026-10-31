//! 歌词翻译。
//!
//! 主接口：POST translate.mapi.myocd.de5.net/translate
//!   body {"text": "...", "target_lang": "zh"}，返回里的 data 就是译文。
//! 这个接口没有频率限制，所以点一次就把整篇歌词并发发出去，
//! 而不是一句一句等。
//!
//! 备用：主接口失效时走思知 AI 对话接口，同样没有限制。
//! AI 输出比较自由，所以把「要什么样的译文」写进提示语里，
//! 拿到结果后再剥掉它偶尔加上的引号和前缀。
//!
//! 放在 Rust 侧而不是前端 fetch：webview 发出的跨域请求受 CORS 限制，
//! 这两个接口不一定带 CORS 头，从前端直接请求可能整个拿不到响应。

use reqwest::blocking::Client;
use serde_json::Value;
use std::sync::OnceLock;
use std::time::Duration;

const MAIN_URL: &str = "https://translate.mapi.myocd.de5.net/translate";
const AI_URL: &str = "https://api.sizhi.com/chat";
const AI_APPID: &str = "b179db75c4804ae5954d2e7ad794e5bd";
const AI_PROMPT: &str =
    "输出中文翻译，要顺口且诗意且精简的歌词式翻译，原句没有逗号尽量不使用逗号分句，禁止给出其他内容纯翻译：";

/// 每批并发多少个：一次性撒几十个请求容易把接口拖慢，
/// 分批仍是「一次翻译完整篇」，只是批内并发、批间串行。
const CONCURRENCY: usize = 8;

fn client() -> Client {
    static C: OnceLock<Client> = OnceLock::new();
    C.get_or_init(|| {
        Client::builder()
            .timeout(Duration::from_secs(12))
            .build()
            .unwrap_or_else(|_| Client::new())
    })
    .clone()
}

/// 整篇翻译：并发请求，返回与输入等长的列表，翻不出来的那一句是空串。
pub fn translate_all(texts: Vec<String>) -> Vec<String> {
    let c = client();
    let mut out = vec![String::new(); texts.len()];
    for (ci, chunk) in texts.chunks(CONCURRENCY).enumerate() {
        let base = ci * CONCURRENCY;
        let handles: Vec<_> = chunk
            .iter()
            .map(|t| {
                let c = c.clone();
                let t = t.clone();
                std::thread::spawn(move || translate_one(&c, &t).unwrap_or_default())
            })
            .collect();
        for (k, h) in handles.into_iter().enumerate() {
            out[base + k] = h.join().unwrap_or_default();
        }
    }
    out
}

/// 单句：主接口拿不到就用 AI 兜底
fn translate_one(c: &Client, text: &str) -> Option<String> {
    let t = text.trim();
    if t.is_empty() {
        return None;
    }
    if let Some(v) = main_api(c, t) {
        return Some(clean(&v));
    }
    ai_api(c, t).map(|v| clean(&v))
}

fn main_api(c: &Client, text: &str) -> Option<String> {
    let body = serde_json::json!({ "text": text, "target_lang": "zh" });
    let j: Value = c.post(MAIN_URL).json(&body).send().ok()?.json().ok()?;
    // code 200 才算成功；接口偶发返回非 200，这时交给备用
    if !num_is(&j, "code", 200) {
        return None;
    }
    let s = j.get("data")?.as_str()?.trim();
    if s.is_empty() || s == "null" {
        None
    } else {
        Some(s.to_string())
    }
}

fn ai_api(c: &Client, text: &str) -> Option<String> {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let uid = format!("{:x}{:x}", ms, ms.wrapping_mul(31));
    let url = format!(
        "{}?appid={}&userid={}&spoken={}",
        AI_URL,
        AI_APPID,
        uid,
        urlencode(&format!("{}{}", AI_PROMPT, text))
    );
    let j: Value = c.get(&url).send().ok()?.json().ok()?;
    if !num_is(&j, "status", 0) {
        return None;
    }
    let s = j
        .get("data")?
        .get("info")?
        .get("text")?
        .as_str()?
        .trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// code / status 有时是数字、有时是字符串，两种都要认
fn num_is(j: &Value, key: &str, want: i64) -> bool {
    match j.get(key) {
        Some(Value::Number(n)) => n.as_i64() == Some(want),
        Some(Value::String(s)) => s.trim().parse::<i64>() == Ok(want),
        _ => false,
    }
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 清掉 AI 偶尔加的包装：多余引号、「翻译：」这类前缀。
/// 主接口本来就是干净的，这一步对它无害。
fn clean(s: &str) -> String {
    let mut t = s
        .trim()
        .trim_matches(|c| matches!(c, '"' | '\'' | '“' | '”' | '「' | '」'))
        .trim();
    for p in ["翻译：", "翻译:", "译文：", "译文:"] {
        if let Some(rest) = t.strip_prefix(p) {
            t = rest.trim();
            break;
        }
    }
    t.to_string()
}
