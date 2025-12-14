import Foundation
import AVFoundation

// MARK: - Audio Source Types
enum AudioSource {
    case server(JMusicAPIManager)
    case local
    
    var displayName: String {
        switch self {
        case .server:
            return "Server"
        case .local:
            return "Local"
        }
    }
}

// MARK: - Enhanced Song Models
struct LocalSong: Identifiable, Codable {
    let id = UUID()
    let title: String
    let artist: String
    let album: String
    let duration: TimeInterval
    let localURL: URL
    let artwork: String?
    
    var displayDuration: String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Unified Audio Manager
class UnifiedAudioManager: ObservableObject {
    @Published var currentSource: AudioSource = .local
    @Published var isPlaying = false
    @Published var currentSong: Song?
    @Published var currentLocalSong: LocalSong?
    @Published var position: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var volume: Float = 0.8
    @Published var repeatMode: PlayerState.RepeatMode = .none
    @Published var isShuffled = false
    @Published var isOffline = false
    
    private var audioPlayer: AVAudioPlayer?
    private var timer: Timer?
    private let connectionManager: ConnectionManager
    private let backgroundAudioManager = BackgroundAudioManager()
    
    init(connectionManager: ConnectionManager) {
        self.connectionManager = connectionManager
        setupNotifications()
        setupAudioSession()
    }
    
    deinit {
        timer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }
    
    // MARK: - Source Management
    func switchToServer() {
        guard let apiManager = connectionManager.apiManager else {
            print("No server connection available")
            return
        }
        
        currentSource = .server(apiManager)
        isOffline = false
        stopCurrentPlayback()
    }
    
    func switchToLocal() {
        currentSource = .local
        isOffline = true
        stopCurrentPlayback()
    }
    
    func autoDetectSource() {
        if let apiManager = connectionManager.apiManager {
            currentSource = .server(apiManager)
            isOffline = false
        } else {
            currentSource = .local
            isOffline = true
        }
    }
    
    // MARK: - Playback Control
    func playSong(_ song: Song) async {
        guard case .server(let apiManager) = currentSource else { return }
        
        stopCurrentPlayback()
        
        do {
            let streamURL = URL(string: "\(apiManager.baseURL)\(song.streamURL)")!
            let playerItem = AVPlayerItem(url: streamURL)
            
            // For streaming, we'll use AVPlayer instead of AVAudioPlayer
            // This is a simplified version - in production you'd want proper buffering
            currentSong = song
            currentLocalSong = nil
            duration = song.durationTimeInterval
            
            // Start position tracking
            startPositionTracking()
            
        } catch {
            print("Failed to play server song: \(error)")
        }
    }
    
    func playLocalSong(_ song: LocalSong) {
        stopCurrentPlayback()
        
        do {
            audioPlayer = try AVAudioPlayer(contentsOf: song.localURL)
            audioPlayer?.delegate = self
            audioPlayer?.volume = volume
            audioPlayer?.prepareToPlay()
            
            currentLocalSong = song
            currentSong = nil
            duration = audioPlayer?.duration ?? 0
            position = 0
            
            audioPlayer?.play()
            isPlaying = true
            
            // Update background audio
            updateBackgroundNowPlaying()
            
            startPositionTracking()
            
        } catch {
            print("Failed to play local song: \(error)")
        }
    }
    
    func togglePlayPause() async {
        if isPlaying {
            pause()
        } else {
            await play()
        }
    }
    
    @MainActor
    private func play() async {
        switch currentSource {
        case .server(let apiManager):
            if currentSong != nil {
                // Resume server playback
                do {
                    try await apiManager.play()
                    isPlaying = true
                    startPositionTracking()
                } catch {
                    print("Failed to resume server playback: \(error)")
                }
            }
        case .local:
            if let player = audioPlayer {
                player.play()
                isPlaying = true
                startPositionTracking()
            }
        }
    }
    
    @MainActor
    private func pause() async {
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.pause()
                isPlaying = false
                stopPositionTracking()
            } catch {
                print("Failed to pause server playback: \(error)")
            }
        case .local:
        audioPlayer?.pause()
        isPlaying = false
        
        // Update background audio
        backgroundAudioManager.updatePlaybackState(isPlaying: false, position: position)
        
        stopPositionTracking()
        }
    }
    
    func stop() async {
        stopCurrentPlayback()
    }
    
    func next() async {
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.next()
            } catch {
                print("Failed to skip to next: \(error)")
            }
        case .local:
            // Implement local next logic
            break
        }
    }
    
    func previous() async {
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.previous()
            } catch {
                print("Failed to skip to previous: \(error)")
            }
        case .local:
            // Implement local previous logic
            break
        }
    }
    
    func setVolume(_ volume: Float) async {
        self.volume = volume
        
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.setVolume(volume)
            } catch {
                print("Failed to set server volume: \(error)")
            }
        case .local:
            audioPlayer?.volume = volume
        }
    }
    
    func setPosition(_ position: TimeInterval) async {
        self.position = position
        
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.setPosition(position)
            } catch {
                print("Failed to set server position: \(error)")
            }
        case .local:
            audioPlayer?.currentTime = position
        }
    }
    
    func toggleShuffle() async {
        isShuffled.toggle()
        
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.toggleShuffle()
            } catch {
                print("Failed to toggle server shuffle: \(error)")
            }
        case .local:
            // Implement local shuffle
            break
        }
    }
    
    func toggleRepeat() async {
        switch repeatMode {
        case .none:
            repeatMode = .all
        case .all:
            repeatMode = .one
        case .one:
            repeatMode = .none
        }
        
        switch currentSource {
        case .server(let apiManager):
            do {
                try await apiManager.toggleRepeat()
            } catch {
                print("Failed to toggle server repeat: \(error)")
            }
        case .local:
            // Implement local repeat
            break
        }
    }
    
    // MARK: - Private Methods
    private func stopCurrentPlayback() {
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        stopPositionTracking()
        position = 0
    }
    
    private func startPositionTracking() {
        stopPositionTracking()
        
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updatePosition()
            }
        }
    }
    
    private func stopPositionTracking() {
        timer?.invalidate()
        timer = nil
    }
    
    @MainActor
    private func updatePosition() {
        switch currentSource {
        case .server:
            // For server playback, position would come from WebSocket or polling
            // This is simplified - in production you'd use WebSocket for real-time updates
            if position < duration {
                position += 1.0
            }
        case .local:
            if let player = audioPlayer {
                position = player.currentTime
            }
        }
    }
    
    private func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Failed to setup audio session: \(error)")
        }
    }
    
    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(serverConnected),
            name: Notification.Name("ServerConnected"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(serverDisconnected),
            name: Notification.Name("ServerDisconnected"),
            object: nil
        )
        
        // Remote control notifications
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRemotePlay),
            name: Notification.Name("RemotePlay"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRemotePause),
            name: Notification.Name("RemotePause"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRemoteNext),
            name: Notification.Name("RemoteNext"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRemotePrevious),
            name: Notification.Name("RemotePrevious"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRemoteSeek),
            name: Notification.Name("RemoteSeek"),
            object: nil
        )
        
        // WebSocket notifications
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleWebSocketStateUpdate),
            name: Notification.Name("WebSocketPlaybackStateUpdate"),
            object: nil
        )
    }
    
    @objc private func serverConnected() {
        autoDetectSource()
    }
    
    @objc private func serverDisconnected() {
        autoDetectSource()
    }
    
    @objc private func handleRemotePlay() {
        Task {
            await play()
        }
    }
    
    @objc private func handleRemotePause() {
        Task {
            await pause()
        }
    }
    
    @objc private func handleRemoteNext() {
        Task {
            await next()
        }
    }
    
    @objc private func handleRemotePrevious() {
        Task {
            await previous()
        }
    }
    
    @objc private func handleRemoteSeek(_ notification: Notification) {
        if let position = notification.object as? TimeInterval {
            Task {
                await setPosition(position)
            }
        }
    }
    
    @objc private func handleWebSocketStateUpdate(_ notification: Notification) {
        if let playbackState = notification.object as? PlaybackState {
            Task { @MainActor in
                // Update local state with server state
                self.isPlaying = playbackState.isPlaying
                self.position = playbackState.position
                self.volume = playbackState.volume
                self.isShuffled = playbackState.shuffle
                self.repeatMode = playbackState.repeat ? .all : .none
                
                // Update background audio
                self.updateBackgroundNowPlaying()
            }
        }
    }
    
    private func updateBackgroundNowPlaying() {
        switch currentSource {
        case .server:
            if let song = currentSong {
                backgroundAudioManager.updateNowPlayingInfo(
                    title: song.title,
                    artist: song.artist,
                    album: song.album,
                    duration: song.durationTimeInterval
                )
            }
        case .local:
            if let localSong = currentLocalSong {
                backgroundAudioManager.updateNowPlayingInfo(
                    title: localSong.title,
                    artist: localSong.artist,
                    album: localSong.album,
                    duration: localSong.duration
                )
            }
        }
        
        backgroundAudioManager.updatePlaybackState(isPlaying: isPlaying, position: position)
    }
}

// MARK: - AVAudioPlayerDelegate
extension UnifiedAudioManager: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if flag {
            Task {
                await next()
            }
        }
    }
    
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        print("Audio decode error: \(error?.localizedDescription ?? "Unknown error")")
    }
}