import Foundation

// MARK: - API Response Wrapper
struct ApiResponse<T: Codable>: Codable {
    let data: T?
    let error: String?
}

// MARK: - Core Models
struct Song: Identifiable, Codable {
    let id: Int
    let title: String
    let artist: String
    let album: String
    let duration: Int
    let path: String
    let lyrics: String?
    let artwork: String?
    
    // Computed properties for mobile app
    var durationTimeInterval: TimeInterval {
        return TimeInterval(duration)
    }
    
    var streamURL: String {
        return "/api/music/stream/\(id)"
    }
    
    var displayDuration: String {
        let minutes = duration / 60
        let seconds = duration % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

struct Playlist: Identifiable, Codable {
    let id: Int
    let name: String
    let description: String?
    let isGlobal: Bool
    let songs: [Song]
    let profile: Profile?
    
    var songCount: Int {
        return songs.count
    }
}

struct Profile: Identifiable, Codable {
    let id: Int
    let name: String
    let isMainProfile: Bool
}

// MARK: - Playback Models
struct PlaybackState: Codable {
    let currentSongId: Int?
    let isPlaying: Bool
    let position: Double
    let volume: Float
    let shuffle: Bool
    let repeat: Bool
    
    var currentSong: Song?
}

struct MusicLibrary: Codable {
    let songs: [Song]
    let playlists: [Playlist]
    
    // Helper method to get songs for a specific playlist
    func getSongs(for playlist: Playlist) -> [Song] {
        return playlist.songs
    }
    
    // Helper method to get all artists
    func getAllArtists() -> [String] {
        return Array(Set(songs.map { $0.artist })).sorted()
    }
    
    // Helper method to get all albums
    func getAllAlbums() -> [String] {
        return Array(Set(songs.map { $0.album })).sorted()
    }
}

// MARK: - Server Models
struct ServerInfo: Codable {
    let id: UUID
    let name: String
    let version: String
    let lastSeen: Date
    
    init(id: UUID = UUID(), name: String, version: String = "1.0") {
        self.id = id
        self.name = name
        self.version = version
        self.lastSeen = Date()
    }
}

struct ServerConnection: Codable {
    let serverInfo: ServerInfo
    let url: String
    let port: Int
    let lastConnected: Date
    let currentProfile: Profile?
    
    init(serverInfo: ServerInfo, url: String, port: Int, currentProfile: Profile? = nil) {
        self.serverInfo = serverInfo
        self.url = url
        self.port = port
        self.lastConnected = Date()
        self.currentProfile = currentProfile
    }
    
    var fullURL: String {
        return "http://\(url):\(port)"
    }
    
    var displayName: String {
        return "\(serverInfo.name) (\(url):\(port))"
    }
}

class PlayerState: ObservableObject {
    @Published var currentSong: Song?
    @Published var isPlaying: Bool = false
    @Published var position: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var volume: Float = 0.8
    @Published var repeatMode: RepeatMode = .none
    @Published var isShuffled: Bool = false
    
    enum RepeatMode: String, CaseIterable {
        case none = "none"
        case one = "one"
        case all = "all"
    }
}