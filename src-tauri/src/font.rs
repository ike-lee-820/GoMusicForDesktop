//! 思源宋体（Noto Serif SC，可变字重）按需下载。
//!
//! 之前 @font-face 直接指向 CDN，那个地址实际上拿不到字体（返回 403），
//! 所以宋体一直是「选了但没生效」。改成客户端下载：
//! 文件存到应用数据目录，下载过就一直在，不依赖外部 CDN。
//!
//! 格式用 TTF 可变字体（带 wght 轴），所以字重滑块仍然可用。
//! 源用 GitHub raw，并依次套 gh 加速源 —— 国内直连 raw 通常不通。
//! 下载先写临时文件、完成后再改名，避免半截文件被当成字体加载。

use reqwest::blocking::Client;
use std::io::{Read, Write};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager};

const PATH: &str =
    "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Serif/Variable/TTF/Subset/NotoSerifSC-VF.ttf";

/// gh 加速源：把完整 URL 拼在后面即可。都失败就直连
const MIRRORS: &[&str] = &[
    "https://gh-proxy.com/",
    "https://ghfast.top/",
    "https://gh.llkk.cc/",
    "https://mirror.ghproxy.com/",
    "https://gh-proxy.net/",
];

/// 小于这个大小就当没下完：正常字体 25MB 上下
const MIN_BYTES: u64 = 2_000_000;

fn client() -> Client {
    Client::builder()
        .timeout(std::time::Duration::from_secs(180))
        .build()
        .unwrap_or_else(|_| Client::new())
}

pub fn font_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("fonts");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir.join("song.ttf"))
}

/// 字体是否已下载好
pub fn is_ready(app: &AppHandle) -> bool {
    match font_path(app) {
        Ok(p) => p
            .metadata()
            .map(|m| m.len() >= MIN_BYTES)
            .unwrap_or(false),
        Err(_) => false,
    }
}

/// 下载字体，返回本地文件路径。进度通过 font-progress 事件推送。
pub async fn download(app: AppHandle) -> Result<String, String> {
    let dst = font_path(&app)?;
    if let Ok(m) = dst.metadata() {
        if m.len() >= MIN_BYTES {
            return Ok(dst.to_string_lossy().to_string());
        }
    }
    let handle = app.clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let tmp = dst.with_extension("ttf.tmp");
        let urls: Vec<String> = MIRRORS.iter().map(|m| format!("{}{}", m, PATH)).collect();
        let urls: Vec<String> = urls.into_iter().chain(std::iter::once(PATH.to_string())).collect();
        let mut last = String::from("未知错误");
        for u in &urls {
            let _ = std::fs::remove_file(&tmp);
            let res = (|| -> Result<(), String> {
                let mut resp = client()
                    .get(u)
                    .send()
                    .map_err(|e| e.to_string())?
                    .error_for_status()
                    .map_err(|e| e.to_string())?;
                let total = resp.content_length().unwrap_or(0);
                let mut out = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
                let mut buf = [0u8; 64 * 1024];
                let mut done: u64 = 0;
                loop {
                    let n = resp.read(&mut buf).map_err(|e| e.to_string())?;
                    if n == 0 {
                        break;
                    }
                    out.write_all(&buf[..n]).map_err(|e| e.to_string())?;
                    done += n as u64;
                    if total > 0 {
                        let pct = (done * 100 / total).min(100) as u8;
                        let _ = handle.emit("font-progress", pct);
                    }
                }
                drop(out);
                Ok(())
            })();
            match res {
                Ok(()) => {
                    let ok = std::fs::metadata(&tmp)
                        .map(|m| m.len() >= MIN_BYTES)
                        .unwrap_or(false);
                    if ok {
                        let _ = std::fs::remove_file(&dst);
                        std::fs::rename(&tmp, &dst).map_err(|e| e.to_string())?;
                        let _ = handle.emit("font-progress", 100u8);
                        return Ok(dst.to_string_lossy().to_string());
                    }
                    last = format!(
                        "文件不完整（{} 字节）",
                        std::fs::metadata(&tmp).map(|m| m.len()).unwrap_or(0)
                    );
                }
                Err(e) => last = e,
            }
        }
        let _ = std::fs::remove_file(&tmp);
        Err(last)
    })
    .await
    .map_err(|e| e.to_string())?
}
