import { invoke } from "@tauri-apps/api/core"
import type {
  DlStatus, DownloadRecord, Group, LrcLine, Playlist, PrefsFile, PlayerStatus, QrStatus,
  Song, UpdateInfo,
} from "./types"

export interface Library {
  songs: Song[]
  offline: boolean
  artists: Record<string, { image?: string | null; bio?: string | null }>
  downloads: DownloadRecord[]
  playCounts: Record<string, number>
}

export interface NmSearchResult { songs: Song[]; albums: Group[]; artists: Group[] }

export interface HomeData {
  recommend: Song[]
  newSongs: Song[]
  hotWords: string[]
  topArtists: Group[]
  newAlbums: Group[]
  tops: Group[]
}

export const api = {
  loadLibrary: () => invoke<Library>("load_library"),
  getPrefs: () => invoke<PrefsFile>("get_prefs"),
  setPref: (key: string, value: string) => invoke<void>("set_pref", { key, value }),

  playSong: (song: Song, quality: string) => invoke<PlayerStatus>("play_song", { song, quality }),
  pause: () => invoke<void>("player_pause"),
  resume: () => invoke<void>("player_resume"),
  stop: () => invoke<void>("player_stop"),
  seek: (ms: number) => invoke<void>("player_seek", { ms }),
  setRate: (rate: number) => invoke<void>("player_set_rate", { rate }),
  status: () => invoke<PlayerStatus>("player_status"),
  loadLyric: (ssid: string) => invoke<LrcLine[]>("load_lyric", { ssid }),

  nmSearch: (kind: number, keyword: string, limit: number, offset: number) =>
    invoke<NmSearchResult>("nm_search", { kind, keyword, limit, offset }),
  nmHome: (newSongType: number) => invoke<HomeData>("nm_home", { newSongType }),
  nmSongsOf: (kind: string, id: number, limit: number, offset: number) =>
    invoke<Song[]>("nm_songs_of", { kind, id, limit, offset }),
  nmArtistAlbums: (id: number, limit: number, offset: number) =>
    invoke<Group[]>("nm_artist_albums", { id, limit, offset }),
  nmAvailableLevels: (ssid: string) => invoke<string[]>("nm_available_levels", { ssid }),
  nmQrKey: () => invoke<string | null>("nm_qr_key"),
  nmQrImage: (key: string) => invoke<string | null>("nm_qr_image", { key }),
  nmQrCheck: (key: string) => invoke<QrStatus>("nm_qr_check", { key }),
  nmLoginCaptcha: (phone: string, captcha: string) =>
    invoke<string | null>("nm_login_captcha", { phone, captcha }),
  nmSendCaptcha: (phone: string) => invoke<boolean>("nm_send_captcha", { phone }),
  nmAccountName: () => invoke<string | null>("nm_account_name"),
  nmLoggedIn: () => invoke<boolean>("nm_logged_in"),
  nmMvUrl: (mvId: number) => invoke<string | null>("nm_mv_url", { mvId }),

  createPlaylist: (name: string, songs: string[], cover?: string | null) =>
    invoke<Playlist[]>("create_playlist", { name, songs, cover }),
  renamePlaylist: (id: string, name: string) => invoke<Playlist[]>("rename_playlist", { id, name }),
  deletePlaylist: (id: string) => invoke<Playlist[]>("delete_playlist", { id }),
  addToPlaylist: (id: string, ids: string[]) => invoke<Playlist[]>("add_to_playlist", { id, ids }),
  removeFromPlaylist: (id: string, ssid: string) =>
    invoke<Playlist[]>("remove_from_playlist", { id, ssid }),
  moveInPlaylist: (id: string, from: number, to: number) =>
    invoke<Playlist[]>("move_in_playlist", { id, from, to }),
  rememberSongs: (songs: Song[]) => invoke<void>("remember_songs", { songs }),

  downloadSongs: (songs: Song[], quality: string) =>
    invoke<void>("download_songs", { songs, quality }),
  downloadStatus: () => invoke<DlStatus | null>("download_status"),
  deleteDownload: (ssid: string) => invoke<DownloadRecord[]>("delete_download", { ssid }),
  openDownloadDir: () => invoke<void>("open_download_dir"),

  removeHistory: (ssid: string) => invoke<string[]>("remove_history", { ssid }),
  clearHistory: () => invoke<string[]>("clear_history"),

  appVersion: () => invoke<string>("app_version"),
  checkUpdate: () => invoke<UpdateInfo | null>("check_update"),
  openUrl: (url: string) => invoke<void>("open_url", { url }),

  nmTopList: () => invoke<Group[]>("nm_top_list"),
  nmArtistDetail: (id: number) => invoke<Group | null>("nm_artist_detail", { id }),
  nmUserPlaylists: () => invoke<Group[]>("nm_user_playlists"),
  nmPlaylistAdd: (pid: number, ids: string[]) =>
    invoke<boolean>("nm_playlist_add", { pid, ids }),
  nmPlaylistDel: (pid: number, ids: string[]) =>
    invoke<boolean>("nm_playlist_del", { pid, ids }),
  nmPlaylistRename: (pid: number, name: string) =>
    invoke<boolean>("nm_playlist_rename", { pid, name }),
  qualityLabel: (k: string) => invoke<string>("quality_label_of", { k }),
  nmLevelLabel: (k: string) => invoke<string>("nm_level_label_of", { k }),
}
