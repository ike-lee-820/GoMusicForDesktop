export interface Song {
  ssid: string
  title: string
  artist: string
  album: string
  coverUrl?: string | null
  repoName?: string | null
  owner?: string | null
  locked?: boolean | null
  source: string
  nmId: number
  nmAlbumId: number
  nmArtistId: number
}

export interface Group {
  title: string
  subtitle: string
  cover?: string | null
  songs: Song[]
  artist: string
  album: string
  source: string
  nmId: number
  nmKind: string
}

export interface LrcLine { timeMs: number; text: string }

export interface DlStatus {
  title: string
  index: number
  total: number
  done: number
  size: number
  bps: number
}

export interface Playlist { id: string; name: string; songs: string[]; cover?: string | null }

export interface DownloadRecord {
  ssid: string
  title: string
  artist: string
  album: string
  quality: string
  audio: string
  cover: string
  lyric: string
  meta: string
  time: number
}

export interface PrefsFile {
  playCounts: Record<string, number>
  history: string[]
  playlists: Playlist[]
  downloads: DownloadRecord[]
  quality: string
  playMode: number
  fontKind: number
  zhConvert: number
  speed: number
  themeColor?: number | null
  nmEnabled: boolean
  nmCookie: string
  nmAccount: string
  songSnapshots: Record<string, Song>
}

export interface PlayerStatus {
  playing: boolean
  paused: boolean
  positionMs: number
  durationMs: number
  ended: boolean
  cur?: string | null
}
