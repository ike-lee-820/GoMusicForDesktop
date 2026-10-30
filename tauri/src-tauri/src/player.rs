//! 播放引擎：rodio + symphonia。
//!
//! 输出设备与 Sink 只在专用播放线程内创建/销毁：音频设备对象在很多平台上
//! 不能安全地跨线程移动，放在线程里最稳妥。命令通过 channel 下发。

use parking_lot::Mutex;
use rodio::{Decoder, OutputStream, OutputStreamHandle, Sink, Source};
use std::collections::VecDeque;
use std::fs::File;
use std::io::BufReader;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub playing: bool,
    pub paused: bool,
    pub position_ms: i64,
    pub duration_ms: i64,
    /// 自然播完（前端据此决定下一首 / 单曲循环）
    pub ended: bool,
    pub cur: Option<String>,
}

enum Cmd {
    Play { path: String, start_ms: i64, rate: f32 },
    Pause,
    Resume,
    Stop,
    Seek(i64),
    Rate(f32),
    Quit,
}

struct Slot {
    sink: Option<Sink>,
    stream: Option<(OutputStream, OutputStreamHandle)>,
    /// 起播时刻（Resume 时重置）
    started: Option<Instant>,
    /// 暂停累计时长
    paused_accum: Duration,
    paused_at: Option<Instant>,
    offset_ms: i64,
    duration_ms: i64,
    rate: f32,
    cur: Option<String>,
}

impl Slot {
    fn ensure_stream(&mut self) -> Result<(), String> {
        if self.stream.is_none() {
            let (stream, handle) = OutputStream::try_default().map_err(|e| e.to_string())?;
            self.stream = Some((stream, handle));
        }
        Ok(())
    }

    /// 打开音频从头（或指定位置）播放。seek 也走这里：重新开文件并跳过前面的样本
    fn start(&mut self, path: &str, start_ms: i64, rate: f32) -> Result<(), String> {
        self.ensure_stream()?;
        // 先停掉旧的
        if let Some(s) = self.sink.take() { s.stop(); }
        let handle = self.stream.as_ref().map(|(_, h)| h.clone()).ok_or("没有音频输出")?;
        let sink = Sink::try_new(&handle).map_err(|e| e.to_string())?;

        let file = File::open(path).map_err(|e| e.to_string())?;
        let mut dec = Decoder::new(BufReader::new(file)).map_err(|e| e.to_string())?;
        let dur = dec.total_duration().map(|d| d.as_millis() as i64).unwrap_or(0);
        // 跳过前面的样本：不用 seek API，直接丢弃，兼容性最好
        if start_ms > 0 {
            let sr = dec.sample_rate() as f64;
            let ch = dec.channels().max(1) as f64;
            let n = (start_ms as f64 / 1000.0 * sr * ch) as u64;
            let mut i = 0u64;
            while i < n {
                if dec.next().is_none() { break; }
                i += 1;
            }
        }
        if (rate - 1.0).abs() > 0.001 {
            sink.append(dec.speed(rate));
        } else {
            sink.append(dec);
        }
        sink.play();
        self.sink = Some(sink);
        self.started = Some(Instant::now());
        self.paused_accum = Duration::ZERO;
        self.paused_at = None;
        self.offset_ms = start_ms;
        self.duration_ms = dur;
        self.rate = rate;
        self.cur = Some(path.to_string());
        Ok(())
    }

    fn position_ms(&self) -> i64 {
        let base = match self.started {
            None => return self.offset_ms,
            Some(_) => {},
        };
        let elapsed = match self.paused_at {
            Some(p) => p.duration_since(base).saturating_sub(self.paused_accum),
            None => Instant::now().duration_since(base).saturating_sub(self.paused_accum),
        };
        self.offset_ms + (elapsed.as_secs_f64() * self.rate as f64 * 1000.0) as i64
    }

    fn status(&self, ended: bool) -> Status {
        Status {
            playing: self.sink.is_some() && self.paused_at.is_none(),
            paused: self.paused_at.is_some(),
            position_ms: self.position_ms(),
            duration_ms: self.duration_ms,
            ended,
            cur: self.cur.clone(),
        }
    }
}

pub struct Player {
    tx: Mutex<Sender<Cmd>>,
    status: Arc<Mutex<Status>>,
    /// 播完一次就置位，被前端取走后清零
    ended: Arc<Mutex<bool>>,
}

impl Player {
    pub fn new() -> Self {
        let (tx, rx) = channel::<Cmd>();
        let status = Arc::new(Mutex::new(Status::default()));
        let ended = Arc::new(Mutex::new(false));
        let st = status.clone();
        let en = ended.clone();
        std::thread::Builder::new()
            .name("gomusic-player".into())
            .spawn(move || player_loop(rx, st, en))
            .expect("启动播放线程失败");
        Player { tx: Mutex::new(tx), status, ended }
    }

    fn send(&self, c: Cmd) {
        let _ = self.tx.lock().send(c);
    }

    pub fn play(&self, path: &str, start_ms: i64, rate: f32) {
        *self.ended.lock() = false;
        // 先给一个乐观状态，避免界面上短暂显示 0
        {
            let mut s = self.status.lock();
            s.playing = true;
            s.paused = false;
            s.position_ms = start_ms;
            s.ended = false;
            s.cur = Some(path.to_string());
        }
        self.send(Cmd::Play { path: path.to_string(), start_ms, rate });
    }

    pub fn pause(&self) { self.send(Cmd::Pause); }
    pub fn resume(&self) { self.send(Cmd::Resume); }
    pub fn stop(&self) { self.send(Cmd::Stop); }
    pub fn seek(&self, ms: i64) { self.send(Cmd::Seek(ms)); }
    pub fn set_rate(&self, r: f32) { self.send(Cmd::Rate(r)); }

    pub fn status(&self) -> Status {
        let mut s = self.status.lock().clone();
        let e = *self.ended.lock();
        if e { *self.ended.lock() = false; s.ended = true; }
        s
    }
}

impl Default for Player {
    fn default() -> Self { Player::new() }
}

fn player_loop(rx: Receiver<Cmd>, status: Arc<Mutex<Status>>, ended: Arc<Mutex<bool>>) {
    let mut slot = Slot {
        sink: None, stream: None, started: None, paused_accum: Duration::ZERO,
        paused_at: None, offset_ms: 0, duration_ms: 0, rate: 1.0, cur: None,
    };
    let mut cur_path: Option<String> = None;
    let mut cur_rate = 1.0f32;
    // 待处理的命令队列：播放过程中也要能收到停止
    let mut queue: VecDeque<Cmd> = VecDeque::new();
    loop {
        // 取命令（阻塞 50ms，顺便更新状态）
        match rx.recv_timeout(Duration::from_millis(50)) {
            Ok(c) => queue.push_back(c),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
        }
        while let Some(c) = queue.pop_front() {
            match c {
                Cmd::Quit => { return; }
                Cmd::Play { path, start_ms, rate } => {
                    cur_path = Some(path.clone());
                    cur_rate = rate;
                    if let Err(e) = slot.start(&path, start_ms, rate) {
                        let mut s = status.lock();
                        s.playing = false;
                        s.ended = true;
                        s.cur = None;
                        let _ = e;
                    }
                }
                Cmd::Pause => {
                    if let Some(s) = &slot.sink {
                        s.pause();
                        if slot.paused_at.is_none() { slot.paused_at = Some(Instant::now()); }
                    }
                }
                Cmd::Resume => {
                    if let Some(s) = &slot.sink {
                        s.play();
                        if let Some(p) = slot.paused_at.take() {
                            slot.paused_accum += Instant::now().duration_since(p);
                        }
                    }
                }
                Cmd::Stop => {
                    if let Some(s) = slot.sink.take() { s.stop(); }
                    slot.started = None;
                    slot.paused_at = None;
                    slot.paused_accum = Duration::ZERO;
                    slot.offset_ms = 0;
                    slot.duration_ms = 0;
                    cur_path = None;
                }
                Cmd::Seek(ms) => {
                    let p = match (&cur_path, slot.position_ms()) {
                        (Some(p), _) => p.clone(),
                        _ => continue,
                    };
                    if let Err(_) = slot.start(&p, ms, cur_rate) { continue; }
                }
                Cmd::Rate(r) => {
                    cur_rate = r;
                    // 倍速变了要重新开始（rodio 的 speed 是包在 source 上的）
                    if let (Some(p), pos) = (cur_path.clone(), slot.position_ms()) {
                        let _ = slot.start(&p, pos, r);
                    }
                }
            }
        }
        // 状态回写
        let end = match &slot.sink {
            Some(s) => s.empty() && slot.started.is_some(),
            None => false,
        };
        if end {
            *ended.lock() = true;
            slot.started = None;
            slot.sink = None;
        }
        *status.lock() = slot.status(end);
    }
}
