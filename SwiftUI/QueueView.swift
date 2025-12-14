import SwiftUI

struct QueueView: View {
    @EnvironmentObject var audioManager: UnifiedAudioManager
    @EnvironmentObject var connectionManager: ConnectionManager
    @State private var queueSongs: [Song] = []
    @State private var localQueueSongs: [LocalSong] = []
    @State private var isLoading = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text("Queue")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: {
                        Task {
                            await clearQueue()
                        }
                    }) {
                        HStack {
                            Image(systemName: "trash")
                                .font(.system(size: 16))
                            Text("Clear")
                                .font(.system(size: 16, weight: .medium))
                        }
                        .foregroundColor(.red)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.red.opacity(0.1))
                        .cornerRadius(6)
                    }
                    .disabled(queueSongs.isEmpty && localQueueSongs.isEmpty)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Queue Content
                if isLoading {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        ProgressView()
                            .scaleEffect(1.5)
                            .tint(.green)
                        
                        Text("Loading Queue...")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                        
                        Spacer()
                    }
                } else if queueSongs.isEmpty && localQueueSongs.isEmpty {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        Image(systemName: "music.note.list")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("Queue is Empty")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(.white)
                        
                        Text("Add songs to see them here")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                        
                        Spacer()
                    }
                } else {
                    // Queue List
                    List {
                        // Server Queue Songs
                        ForEach(queueSongs) { song in
                            QueueSongRow(
                                song: song,
                                isLocal: false,
                                onPlay: {
                                    Task {
                                        await audioManager.playSong(song)
                                    }
                                },
                                onRemove: {
                                    Task {
                                        await removeFromQueue(song)
                                    }
                                }
                            )
                        }
                        
                        // Local Queue Songs
                        ForEach(localQueueSongs) { song in
                            QueueSongRow(
                                song: nil,
                                localSong: song,
                                isLocal: true,
                                onPlay: {
                                    Task {
                                        await audioManager.playLocalSong(song)
                                    }
                                },
                                onRemove: {
                                    removeFromLocalQueue(song)
                                }
                            )
                        }
                    }
                    .listStyle(PlainListStyle())
                    .background(Color.black)
                }
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            loadQueue()
        }
        .refreshable {
            await loadQueue()
        }
    }
    
    private func loadQueue() async {
        isLoading = true
        
        switch audioManager.currentSource {
        case .server(let apiManager):
            do {
                queueSongs = try await apiManager.getQueue()
                localQueueSongs = []
            } catch {
                print("Failed to load queue: \(error)")
            }
        case .local:
            // Local queue would be managed locally
            queueSongs = []
            localQueueSongs = []
        }
        
        isLoading = false
    }
    
    private func removeFromQueue(_ song: Song) async {
        guard case .server(let apiManager) = audioManager.currentSource else { return }
        
        // Find song index in queue
        if let index = queueSongs.firstIndex(where: { $0.id == song.id }) {
            do {
                // This would need to be implemented on server side
                // For now, we'll just remove from local array
                queueSongs.remove(at: index)
            } catch {
                print("Failed to remove from queue: \(error)")
            }
        }
    }
    
    private func removeFromLocalQueue(_ song: LocalSong) {
        if let index = localQueueSongs.firstIndex(where: { $0.id == song.id }) {
            localQueueSongs.remove(at: index)
        }
    }
    
    private func clearQueue() async {
        switch audioManager.currentSource {
        case .server(let apiManager):
            do {
                _ = try await apiManager.clearQueue()
                queueSongs = []
            } catch {
                print("Failed to clear queue: \(error)")
            }
        case .local:
            localQueueSongs = []
        }
    }
}

struct QueueSongRow: View {
    let song: Song?
    let localSong: LocalSong?
    let isLocal: Bool
    let onPlay: () -> Void
    let onRemove: () -> Void
    
    var body: some View {
        HStack(spacing: 12) {
            // Song info
            VStack(alignment: .leading, spacing: 4) {
                Text(song?.title ?? localSong?.title ?? "Unknown")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                
                HStack {
                    Text(song?.artist ?? localSong?.artist ?? "Unknown Artist")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    if let album = song?.album ?? localSong?.album {
                        Text("•")
                            .font(.system(size: 14))
                            .foregroundColor(.gray)
                        
                        Text(album)
                            .font(.system(size: 14))
                            .foregroundColor(.gray)
                    }
                    
                    Spacer()
                    
                    Text(isLocal ? "Local" : "Server")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(isLocal ? .orange : .green)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background((isLocal ? Color.orange : Color.green).opacity(0.2))
                        .cornerRadius(4)
                }
            }
            
            Spacer()
            
            // Controls
            HStack(spacing: 12) {
                Button(action: onPlay) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 24))
                        .foregroundColor(.green)
                }
                
                Button(action: onRemove) {
                    Image(systemName: "minus.circle")
                        .font(.system(size: 20))
                        .foregroundColor(.red)
                }
            }
        }
        .padding(.vertical, 8)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(8)
    }
}

// MARK: - Queue Manager Extensions
extension JMusicAPIManager {
    func clearQueue() async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/queue/clear/\(profile.id)", method: "POST")
    }
    
    func removeFromQueue(at index: Int) async throws {
        guard let profile = currentProfile else {
            throw APIError.noProfile
        }
        _ = try await performRequest("/api/music/queue/remove/\(profile.id)/\(index)", method: "POST")
    }
}