import SwiftUI

struct LyricsView: View {
    @EnvironmentObject var audioManager: UnifiedAudioManager
    @EnvironmentObject var connectionManager: ConnectionManager
    @State private var lyrics: String = ""
    @State private var isLoading = false
    @State private var showingFullLyrics = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text("Lyrics")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: {
                        showingFullLyrics.toggle()
                    }) {
                        Image(systemName: showingFullLyrics ? "arrow.down" : "arrow.up")
                            .font(.system(size: 20))
                            .foregroundColor(.green)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Content
                if isLoading {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        ProgressView()
                            .scaleEffect(1.5)
                            .tint(.green)
                        
                        Text("Loading Lyrics...")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                        
                        Spacer()
                    }
                } else if lyrics.isEmpty {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        Image(systemName: "text.quote")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("No Lyrics Available")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(.white)
                        
                        Text("Lyrics aren't available for this song")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                        
                        Spacer()
                    }
                } else {
                    // Lyrics Display
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            // Song Info
                            VStack(spacing: 8) {
                                Text(getCurrentSongTitle())
                                    .font(.system(size: 20, weight: .bold))
                                    .foregroundColor(.white)
                                    .multilineTextAlignment(.center)
                                
                                Text(getCurrentSongArtist())
                                    .font(.system(size: 16))
                                    .foregroundColor(.gray)
                                    .multilineTextAlignment(.center)
                            }
                            .padding(.bottom, 20)
                            
                            // Lyrics Text
                            Text(lyrics)
                                .font(.system(size: 16, weight: .regular))
                                .foregroundColor(.white)
                                .lineSpacing(4)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 20)
                        }
                        .padding(.vertical, 20)
                    }
                }
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            loadLyrics()
        }
        .onChange(of: audioManager.currentSong) { _ in
            loadLyrics()
        }
        .onChange(of: audioManager.currentLocalSong) { _ in
            loadLyrics()
        }
    }
    
    private func loadLyrics() {
        guard !showingFullLyrics else { return }
        
        isLoading = true
        
        Task {
            let loadedLyrics = await fetchLyrics()
            
            DispatchQueue.main.async {
                self.lyrics = loadedLyrics
                self.isLoading = false
            }
        }
    }
    
    private func fetchLyrics() async -> String {
        switch audioManager.currentSource {
        case .server(let apiManager):
            guard let song = audioManager.currentSong else { return "" }
            
            do {
                let response: ApiResponse<String> = try await apiManager.performRequest("/api/song/\(song.id)/lyrics")
                return response.data ?? ""
            } catch {
                print("Failed to fetch lyrics: \(error)")
                return ""
            }
            
        case .local:
            // For local songs, we could try to fetch from embedded metadata
            // or use a lyrics service
            return ""
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
}

// MARK: - Lyrics API Extension
extension JMusicAPIManager {
    func getLyrics(for songId: Int) async throws -> String {
        let response: ApiResponse<String> = try await performRequest("/api/song/\(songId)/lyrics")
        return response.data ?? ""
    }
    
    func generateLyrics(for songId: Int, model: String = "base") async throws -> String {
        let response: ApiResponse<String> = try await performRequest("/api/song/\(songId)/generate-lyrics?model=\(model)")
        return response.data ?? ""
    }
}