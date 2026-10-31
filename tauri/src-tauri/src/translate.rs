//! 歌词翻译：只走思知 AI 对话接口，并且**整篇一次提交**。
//!
//! 之前是「主翻译接口逐句并发，AI 只做兜底」。主接口返回的是机器译文、
//! 目标语言偶尔还会弄错（翻出来是英文），而 AI 的译文更贴合歌词语境，
//! 所以改成只用 AI。
//!
//! 全文一次提交（而不是一句一个请求）：给 AI 完整上下文，人称、语气、
//! 专有名词在全篇里才翻得一致，逐句翻会出现同一句前后不一致。
//! 代价是一次要等它把整篇生成完，所以超时给得比较长。
//!
//! 对齐方式：原文按「行号. 内容」一起提交，要求 AI 按同样的行号返回；
//! 解析时先按行号严格匹配，对不上再退化为顺序对齐，
//! 两种都对不上就整篇放弃 —— 错位比没有译文更糟。
//!
//! 放在 Rust 侧而不是前端 fetch：webview 发出的跨域请求受 CORS 限制，
//! 这个接口不一定带 CORS 头，从前端直接请求可能整个拿不到响应。

use reqwest::blocking::Client;
use serde_json::Value;
use std::sync::OnceLock;
use std::time::Duration;

const AI_URL: &str = "https://api.sizhi.com/chat";
const AI_APPID: &str = "b179db75c4804ae5954d2e7ad794e5bd";

/// 提示语：必须点明「简体中文」（否则它常用英文回答），
/// 并强调按行号一一对应、只输出译文
const AI_PROMPT: &str = concat!(
    "把下面这段歌词逐行翻译成简体中文。",
    "要求：顺口、诗意、精简的歌词式翻译；原句没有逗号就尽量不使用逗号分句；",
    "严格按行号一一对应，第 N 行原文只输出第 N 行的译文，行数必须与原文相同；",
    "只输出译文本身，禁止任何解释、标题、序号以外的内容；禁止用英文回答。\n",
    "歌词：\n",
);

fn client() -> Client {
    static C: OnceLock<Client> = OnceLock::new();
    C.get_or_init(|| {
        // 整篇生成比较慢，读超时给到 120 秒
        Client::builder()
            .timeout(Duration::from_secs(120))
            .build()
            .unwrap_or_else(|_| Client::new())
    })
    .clone()
}

/// 整篇翻译：一次请求翻完，返回与输入等长的列表，翻不出来的那一句是空串。
/// 空行直接跳过，不占用行号。
pub fn translate_all(texts: Vec<String>) -> Vec<String> {
    // 只提交非空行：空行让 AI 去「翻译」只会得到莫名其妙的回复
    let idx: Vec<usize> = texts
        .iter()
        .enumerate()
        .filter(|(_, t)| !t.trim().is_empty())
        .map(|(i, _)| i)
        .collect();
    if idx.is_empty() {
        return vec![String::new(); texts.len()];
    }
    let numbered = idx
        .iter()
        .map(|i| format!("{}. {}", i + 1, texts[*i].trim()))
        .collect::<Vec<_>>()
        .join("\n");
    let spoken = format!("{}{}", AI_PROMPT, numbered);
    let raw = match ask(&spoken) {
        Some(v) => v,
        None => return vec![String::new(); texts.len()],
    };
    let want: Vec<usize> = idx.iter().map(|i| i + 1).collect();
    let map = parse_numbered(&raw, &want);
    texts
        .iter()
        .enumerate()
        .map(|(i, t)| {
            if t.trim().is_empty() {
                return String::new();
            }
            match map.get(&(i + 1)) {
                Some(v) => {
                    let c = clean(v);
                    // 译文必须真的含中文：AI 偶尔还是会用英文回答，那种结果不要
                    if has_chinese(&c) {
                        c
                    } else {
                        String::new()
                    }
                }
                None => String::new(),
            }
        })
        .collect()
}

/// 译文里有没有汉字：接口返回英文说明目标语言没生效，不能用
fn has_chinese(s: &str) -> bool {
    s.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c))
}

/// 行号前缀，例如 "3. "  /  "3、"  /  "3) "
fn split_head(line: &str) -> Option<(usize, String)> {
    let s = line.trim();
    let end = s.find(|c: char| !c.is_ascii_digit())?;
    if end == 0 {
        return None;
    }
    let id: usize = s[..end].parse().ok()?;
    let rest = s[end..].trim_start();
    let rest = rest
        .strip_prefix(['.', '、', ')', '．', ':', '：'])
        .unwrap_or(rest)
        .trim();
    Some((id, rest.to_string()))
}

/// 把 AI 的回答按行号拆回每行。
///
/// AI 可能不守规矩（漏编号、串行、夹带解释），所以先按
/// 「行号 + 分隔符」严格匹配；匹配不齐时，若剥掉编号后剩下的行数
/// 刚好等于原文行数，就按顺序对齐；都对不上就整篇放弃。
fn parse_numbered(raw: &str, want: &[usize]) -> std::collections::HashMap<usize, String> {
    use std::collections::HashMap;
    let lines: Vec<&str> = raw
        .lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty())
        .collect();
    let mut by_id: HashMap<usize, String> = HashMap::new();
    for l in &lines {
        if let Some((id, body)) = split_head(l) {
            if !body.is_empty() {
                by_id.insert(id, body);
            }
        }
    }
    if want.iter().all(|w| by_id.contains_key(w)) {
        return by_id;
    }
    // 退化：顺序对齐
    let stripped: Vec<String> = lines
        .iter()
        .map(|l| split_head(l).map(|(_, b)| b).unwrap_or_else(|| l.to_string()))
        .filter(|s| !s.trim().is_empty())
        .collect();
    if stripped.len() == want.len() {
        return want
            .iter()
            .cloned()
            .zip(stripped.into_iter())
            .collect::<HashMap<_, _>>();
    }
    HashMap::new()
}

/// POST 优先：整篇歌词很长，拼进 URL 会超过服务端对 GET 长度的限制。
/// 少数部署只读 query，所以 POST 没拿到时再退回 GET。
fn ask(spoken: &str) -> Option<String> {
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let uid = format!("{:x}{:x}", ms, ms.wrapping_mul(31));
    let base = format!("{}?appid={}&userid={}", AI_URL, AI_APPID, uid);
    if let Some(v) = post(&base, spoken) {
        return Some(v);
    }
    get(&format!("{}&spoken={}", base, urlencode(spoken)))
}

fn post(url: &str, spoken: &str) -> Option<String> {
    let j: Value = client()
        .post(url)
        .form(&[("spoken", spoken)])
        .send()
        .ok()?
        .json()
        .ok()?;
    extract(&j)
}

fn get(url: &str) -> Option<String> {
    let j: Value = client().get(url).send().ok()?.json().ok()?;
    extract(&j)
}

/// status 0 表示成功，文本在 data.info.text
fn extract(j: &Value) -> Option<String> {
    if !num_is(j, "status", 0) {
        return None;
    }
    let s = j.get("data")?.get("info")?.get("text")?.as_str()?.trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// status 有时是数字、有时是字符串，两种都要认
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

/// 清掉 AI 偶尔加的包装：多余引号、「翻译：」这类前缀
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
