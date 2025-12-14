import SwiftUI
import AVFoundation

struct PlayerView: View {
    @EnvironmentObject var audioManager: UnifiedAudioManager
    @EnvironmentObject var connectionManager: ConnectionManager
    @State private var timer: Timer?
    
    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button(action: {}) {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 24))
                            .foregroundColor(.white)
                    }
                    
                    Spacer()
                    
                    Text("Now Playing")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: {}) {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 24))
                            .foregroundColor(.white)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 30)
                
                // Artwork
                VStack(spacing: 0) {
                    Spacer()
                    
                    if let artwork = playerState.currentSong?.artwork {
                        // Load actual artwork if available
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.gray)
                            .frame(width: geometry.size.width * 0.7, height: geometry.size.width * 0.7)
                    } else {
                        // Default artwork
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(red: 0.16, green: 0.16, blue: 0.16))
                            .overlay(
                                Image(systemName: "music.note")
                                    .font(.system(size: 80))
                                    .foregroundColor(.gray)
                            )
                            .frame(width: geometry.size.width * 0.7, height: geometry.size.width * 0.7)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color(red: 0.2, green: 0.2, blue: 0.2), lineWidth: 1)
                            )
                    }
                    
                    Spacer()
                }
                .padding(.vertical, geometry.size.height * 0.05)
                
                // Source Indicator
                HStack {
                    Spacer()
                    
                    HStack(spacing: 8) {
                        Image(systemName: audioManager.currentSource == .server ? "wifi" : "iphone")
                            .font(.system(size: 16))
                            .foregroundColor(audioManager.isOffline ? .orange : .green)
                        
                        Text(audioManager.currentSource.displayName)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(audioManager.isOffline ? .orange : .green)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color(red: 0.16, green: 0.16, blue: 0.16))
                    .cornerRadius(12)
                    
                    Spacer()
                }
                .padding(.horizontal, 20)
                
                // Song Info
                VStack(spacing: 8) {
                    Text(getCurrentSongTitle())
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                    
                    Text(getCurrentSongArtist())
                        .font(.system(size: 16))
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                    
                    // Lyrics Button
                    Button(action: {
                        // Navigate to lyrics - this would need navigation setup
                        NotificationCenter.default.post(
                            name: Notification.Name("ShowLyrics"),
                            object: nil
                        )
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "text.quote")
                                .font(.system(size: 14))
                            Text("Lyrics")
                                .font(.system(size: 14, weight: .medium))
                        }
                        .foregroundColor(.green)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.green.opacity(0.1))
                        .cornerRadius(12)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 30)
                
                // Progress Bar
                VStack(spacing: 8) {
                    ProgressView(value: audioManager.position, total: audioManager.duration)
                        .progressViewStyle(LinearProgressViewStyle(tint: .green))
                        .scaleEffect(x: 1, y: 2, anchor: .center)
                    
                    HStack {
                        Text(formatTime(audioManager.position))
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                        
                        Spacer()
                        
                        Text(formatTime(audioManager.duration))
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 40)
                
                // Controls
                HStack(spacing: 0) {
                    // Sleep Timer
                    Button(action: {
                        // Navigate to sleep timer
                        NotificationCenter.default.post(
                            name: Notification.Name("ShowSleepTimer"),
                            object: nil
                        )
                    }) {
                        Image(systemName: "moon")
                            .font(.system(size: 20))
                            .foregroundColor(.gray)
                    }
                    
                    Spacer()
                    
                    // Shuffle
                    Button(action: {
                        Task {
                            await audioManager.toggleShuffle()
                        }
                    }) {
                        Image(systemName: "shuffle")
                            .font(.system(size: 20))
                            .foregroundColor(audioManager.isShuffled ? .green : .gray)
                    }
                    
                    Spacer()
                    
                    // Previous
                    Button(action: {
                        Task {
                            await audioManager.previous()
                        }
                    }) {
                        Image(systemName: "skip.backward.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white)
                    }
                    
                    Spacer()
                    
                    // Play/Pause
                    Button(action: {
                        Task {
                            await audioManager.togglePlayPause()
                        }
                    }) {
                        Image(systemName: audioManager.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 70))
                            .foregroundColor(.green)
                    }
                    
                    Spacer()
                    
                    // Next
                    Button(action: {
                        Task {
                            await audioManager.next()
                        }
                    }) {
                        Image(systemName: "skip.forward.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white)
                    }
                    
                    Spacer()
                    
                    // Repeat
                    Button(action: {
                        Task {
                            await audioManager.toggleRepeat()
                        }
                    }) {
                        Image(systemName: repeatIcon)
                            .font(.system(size: 20))
                            .foregroundColor(audioManager.repeatMode != .none ? .green : .gray)
                    }
                    
                    Spacer()
                    
                    // More Options
                    Button(action: {
                        // Show more options
                    }) {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 20))
                            .foregroundColor(.gray)
                    }
                }
                    }) {
                        Image(systemName: "shuffle")
                            .font(.system(size: 20))
                            .foregroundColor(audioManager.isShuffled ? .green : .gray)
                    }
                    
                    Spacer()
                    
                    // Repeat
                    Button(action: {
                        Task {
                            await audioManager.toggleRepeat()
                        }
                    }) {
                        Image(systemName: repeatIcon)
                            .font(.system(size: 20))
                            .foregroundColor(audioManager.repeatMode != .none ? .green : .gray)
                    }
                    .frame(width: 50, height: 50)
                    
                    Spacer()
                    
                    // Previous
                    Button(action: {
                        Task {
                            await audioManager.previous()
                        }
                    }) {
                        Image(systemName: "skip.backward.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white)
                    }
                    
                    Spacer()
                    
                    // Next
                    Button(action: {
                        Task {
                            await audioManager.next()
                        }
                    }) {
                        Image(systemName: "skip.forward.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white)
                    }
                    .frame(width: 50, height: 50)
                    
                    Spacer()
                    
                    // Play/Pause
                    Button(action: {
                        Task {
                            await audioManager.togglePlayPause()
                        }
                    }) {
                        Image(systemName: audioManager.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 70))
                            .foregroundColor(.green)
                    }
                    .frame(width: 70, height: 70)
                    
                    Spacer()
                    
                    // Next
                    Button(action: { audioManager.skipToNext() }) {
                        Image(systemName: "skip.forward.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.white)
                    }
                    .frame(width: 50, height: 50)
                    
                    Spacer()
                    
                    // Repeat
                    Button(action: { toggleRepeatMode() }) {
                        Image(systemName: repeatIcon)
                            .font(.system(size: 20))
                            .foregroundColor(playerState.repeatMode != .none ? .green : .gray)
                    }
                    .frame(width: 50, height: 50)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 40)
                
                Spacer()
            }
        }
        .background(Color.black)
        .navigationBarHidden(true)
        .onAppear {
            setupMockSong()
            setupNotifications()
        }
        .onDisappear {
            removeNotifications()
        }
    }
    
    private var repeatIcon: String {
        switch audioManager.repeatMode {
        case .none:
            return "repeat"
        case .one:
            return "repeat.1"
        case .all:
            return "repeat"
        }
    }
    
    private func getCurrentSongTitle() -> String {
        switch audioManager.currentSource {
        case .server:
            return audioManager.currentSong?.title ?? "No Song Playing"
        case .local:
            return audioManager.currentLocalSong?.title ?? "No Song Playing"
        }
    }
    
    private func getCurrentSongArtist() -> String {
        switch audioManager.currentSource {
        case .server:
            return audioManager.currentSong?.artist ?? ""
        case .local:
            return audioManager.currentLocalSong?.artist ?? ""
        }
    }
    
    private func setupMockSong() {
        // Setup is now handled by UnifiedAudioManager
    }
    
    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playLocalSong),
            name: Notification.Name("PlayLocalSong"),
            object: nil
        )
    }
    
    private func removeNotifications() {
        NotificationCenter.default.removeObserver(self)
    }
    
    @objc private func playLocalSong(_ notification: Notification) {
        if let localSong = notification.object as? LocalSong {
            Task {
                await audioManager.playLocalSong(localSong)
            }
        }
    }
    
    private func formatTime(_ interval: TimeInterval) -> String {
        let minutes = Int(interval) / 60
        let seconds = Int(interval) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

#Preview {
    PlayerView()
}