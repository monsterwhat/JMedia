import Foundation

// MARK: - JMusic API Manager
class JMusicAPIManager: ObservableObject {
    @Published var currentProfile: Profile?
    @Published var isLoading = false
    @Published var error: String?
    
    private let baseURL: String
    private let session = URLSession.shared
    
    init(baseURL: String) {
        self.baseURL = baseURL
    }
    
    // MARK: - Profile Management
    func getProfiles() async throws -> [Profile] {
        let response: ApiResponse<[Profile]> = try await performRequest("/api/profiles")
        return response.data ?? []
    }
    
    func getCurrentProfile() async throws -> Profile? {
        let response: ApiResponse<Profile> = try await performRequest("/api/profiles/current")
        return response.data
    }
    
    func switchToProfile(_ profileId: Int) async throws {
        _ = try await performRequest("/api/profiles/switch/\(profileId)", method: "POST")
    }
    
    // MARK: - Library Management
    func getPlaylists() async throws -> [Playlist] {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        let response: ApiResponse<[Playlist]> = try await performRequest("/api/music/playlists/\(profile.id)")
        return response.data ?? []
    }
    
    func getAllSongs() async throws -> [Song] {
        // This would need to be implemented on the server side
        // For now, we can get songs from playlists
        let playlists = try await getPlaylists()
        return playlists.flatMap { $0.songs }
    }
    
    func getPlaylist(_ id: Int) async throws -> Playlist {
        let response: ApiResponse<Playlist> = try await performRequest("/api/music/playlists/\(id)")
        return response.data!
    }
    
    // MARK: - Playback Control
    func getCurrentPlaybackState() async throws -> PlaybackState {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        let response: ApiResponse<PlaybackState> = try await performRequest("/api/music/playback/current/\(profile.id)")
        return response.data!
    }
    
    func togglePlayPause() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/toggle/\(profile.id)", method: "POST")
    }
    
    func play() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/play/\(profile.id)", method: "POST")
    }
    
    func pause() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/pause/\(profile.id)", method: "POST")
    }
    
    func next() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/next/\(profile.id)", method: "POST")
    }
    
    func previous() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/previous/\(profile.id)", method: "POST")
    }
    
    func selectSong(_ songId: Int) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/select/\(profile.id)/\(songId)", method: "POST")
    }
    
    func setVolume(_ volume: Float) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/volume/\(profile.id)/\(volume)", method: "POST")
    }
    
    func setPosition(_ seconds: Double) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/position/\(profile.id)/\(Int(seconds))", method: "POST")
    }
    
    func toggleShuffle() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/shuffle/\(profile.id)", method: "POST")
    }
    
    func toggleRepeat() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/repeat/\(profile.id)", method: "POST")
    }
    
    // MARK: - Queue Management
    func getQueue() async throws -> [Song] {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        let response: ApiResponse<[Song]> = try await performRequest("/api/music/queue/\(profile.id)")
        return response.data ?? []
    }
    
    func addToQueue(_ songId: Int) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/queue/add/\(profile.id)/\(songId)", method: "POST")
    }
    
    func queueAllFromPlaylist(_ playlistId: Int) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/playback/queue-all/\(profile.id)/\(playlistId)", method: "POST")
    }
    
    // MARK: - Server Info
    func getServerInfo() async throws -> ServerInfo {
        // This endpoint doesn't exist in the API, we'll create a simple one
        let response: ApiResponse<[String: Any]> = try await performRequest("/api/info")
        // Parse response to create ServerInfo
        return ServerInfo(name: "JMusic Server", version: "1.0")
    }
    
    // MARK: - HTTP Request Helper
    private func performRequest<T: Codable>(_ endpoint: String, method: String = "GET") async throws -> T {
        isLoading = true
        error = nil
        
        defer { isLoading = false }
        
        guard let url = URL(string: baseURL + endpoint) else {
            throw APIError.invalidURL
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            let (data, response) = try await session.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }
            
            guard 200...299 ~= httpResponse.statusCode else {
                throw APIError.serverError(httpResponse.statusCode)
            }
            
            let decoder = JSONDecoder()
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.networkError(error)
        }
    }
    

}

// MARK: - API Errors
enum APIError: LocalizedError {
    case noProfile
    case invalidURL
    case invalidResponse
    case serverError(Int)
    case networkError(Error)
    
    var errorDescription: String? {
        switch self {
        case .noProfile:
            return "No profile selected"
        case .invalidURL:
            return "Invalid server URL"
        case .invalidResponse:
            return "Invalid server response"
        case .serverError(let code):
            return "Server error: \(code)"
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        }
    }
}