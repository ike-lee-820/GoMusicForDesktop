pub mod api;
mod font;
pub mod commands;
pub mod models;
pub mod netease;
pub mod player;
pub mod prefs;
pub mod store;
pub mod translate;

use tauri::Manager;

/// 崩溃时把堆栈写进 ~/.gomusic/log/，方便定位
fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("崩溃：{:?}", info);
        let dir = prefs::log_dir();
        let name = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
        let _ = std::fs::write(dir.join(format!("panic_{}.txt", name)), msg);
    }));
}

pub fn run() {
    install_panic_hook();
    // 启动时把已保存的 cookie 注入网易云
    netease::netease().set_cookie(prefs::PREFS.lock().nm_cookie.clone());

    tauri::Builder::default()
        .manage(commands::App::default())
        .invoke_handler(tauri::generate_handler![
            commands::load_library,
            commands::get_prefs,
            commands::set_pref,
            commands::play_song,
            commands::player_pause,
            commands::player_resume,
            commands::player_stop,
            commands::player_seek,
            commands::player_set_rate,
            commands::player_status,
            commands::load_lyric,
            commands::nm_search,
            commands::nm_home,
            commands::nm_songs_of,
            commands::nm_artist_albums,
            commands::nm_available_levels,
            commands::nm_qr_key,
            commands::nm_qr_image,
            commands::nm_qr_check,
            commands::nm_login_captcha,
            commands::nm_send_captcha,
            commands::nm_account_name,
            commands::nm_logged_in,
            commands::nm_mv_url,
            commands::translate_lines,
            commands::create_playlist,
            commands::rename_playlist,
            commands::delete_playlist,
            commands::add_to_playlist,
            commands::remove_from_playlist,
            commands::move_in_playlist,
            commands::remember_songs,
            commands::download_songs,
            commands::download_mv,
            commands::download_status,
            commands::delete_download,
            commands::open_download_dir,
            commands::app_version,
            commands::song_font_ready,
            commands::song_font_download,
            commands::quality_label_of,
            commands::nm_level_label_of,
            commands::nm_top_list,
            commands::nm_artist_detail,
            commands::nm_user_playlists,
            commands::nm_playlist_add,
            commands::nm_playlist_del,
            commands::nm_playlist_rename,
            commands::check_update,
            commands::open_url,
            commands::remove_history,
            commands::clear_history,
        ])
        .run(tauri::generate_context!())
        .expect("启动 GoMusic 失败");
}
