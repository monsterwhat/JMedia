import SwiftUI

struct SearchView: View {
    @EnvironmentObject var audioManager: UnifiedAudioManager
    @EnvironmentObject var connectionManager: ConnectionManager
    @State private var searchText = ""
    @State private var searchResults: [SearchResult] = []
    @State private var isSearching = false
    @State private var searchScope: SearchScope = .all
    
    enum SearchScope: String, CaseIterable {
        case all = "All"
        case server = "Server"
        case local = "Local"
    }
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Search Header
                VStack(spacing: 16) {
                    // Search Input
                    HStack(spacing: 12) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                        
                        TextField("Search songs, artists, albums...", text: $searchText)
                            .textFieldStyle(PlainTextFieldStyle())
                            .foregroundColor(.white)
                            .onSubmit {
                                performSearch()
                            }
                        
                        if !searchText.isEmpty {
                            Button(action: {
                                searchText = ""
                                searchResults = []
                            }) {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 16))
                                    .foregroundColor(.gray)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color(red: 0.16, green: 0.16, blue: 0.16))
                    .cornerRadius(10)
                    
                    // Search Scope
                    Picker("Search Scope", selection: $searchScope) {
                        ForEach(SearchScope.allCases, id: \.self) { scope in
                            Text(scope.rawValue).tag(scope)
                        }
                    }
                    .pickerStyle(SegmentedPickerStyle())
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Search Results
                if isSearching {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        ProgressView()
                            .scaleEffect(1.5)
                            .tint(.green)
                        
                        Text("Searching...")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                        
                        Spacer()
                    }
                } else if searchResults.isEmpty && !searchText.isEmpty {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("No Results Found")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(.white)
                        
                        Text("Try different keywords or check spelling")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                        
                        Spacer()
                    }
                } else if searchResults.isEmpty {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("Search Your Music")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(.white)
                        
                        Text("Find songs, artists, and albums from your library")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                        
                        Spacer()
                    }
                } else {
                    // Results List
                    List {
                        ForEach(searchResults) { result in
                            SearchResultRow(
                                result: result,
                                onPlay: {
                                    playResult(result)
                                },
                                onAddToQueue: {
                                    addToQueue(result)
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
        .onChange(of: searchText) { newValue in
            if newValue.isEmpty {
                searchResults = []
            }
        }
    }
    
    private func performSearch() {
        guard !searchText.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        
        isSearching = true
        
        Task {
            var results: [SearchResult] = []
            
            switch searchScope {
            case .all:
                // Search both server and local
                let serverResults = await searchServer()
                let localResults = searchLocal()
                results = serverResults + localResults
                
            case .server:
                results = await searchServer()
                
            case .local:
                results = searchLocal()
            }
            
            DispatchQueue.main.async {
                self.searchResults = results
                self.isSearching = false
            }
        }
    }
    
    private func searchServer() async -> [SearchResult] {
        guard case .server(let apiManager) = audioManager.currentSource else { return [] }
        
        // This would need to be implemented on server side
        // For now, we'll search through cached songs
        do {
            let songs = try await apiManager.getAllSongs()
            let filteredSongs = songs.filter { song in
                song.title.localizedCaseInsensitiveContains(searchText) ||
                song.artist.localizedCaseInsensitiveContains(searchText) ||
                song.album.localizedCaseInsensitiveContains(searchText)
            }
            
            return filteredSongs.map { song in
                SearchResult(
                    id: song.id,
                    title: song.title,
                    artist: song.artist,
                    album: song.album,
                    duration: song.durationTimeInterval,
                    artwork: song.artwork,
                    source: .server,
                    song: song,
                    localSong: nil
                )
            }
        } catch {
            print("Failed to search server: \(error)")
            return []
        }
    }
    
    private func searchLocal() -> [SearchResult] {
        // This would search through local songs
        // For now, return empty array
        return []
    }
    
    private func playResult(_ result: SearchResult) {
        Task {
            switch result.source {
            case .server:
                if let song = result.song {
                    await audioManager.playSong(song)
                }
            case .local:
                if let localSong = result.localSong {
                    await audioManager.playLocalSong(localSong)
                }
            }
        }
    }
    
    private func addToQueue(_ result: SearchResult) {
        Task {
            switch result.source {
            case .server:
                if let song = result.song,
                   case .server(let apiManager) = audioManager.currentSource {
                    do {
                        try await apiManager.addToQueue(song.id)
                    } catch {
                        print("Failed to add to queue: \(error)")
                    }
                }
            case .local:
                // Add to local queue
                break
            }
        }
    }
}

struct SearchResult: Identifiable {
    let id: Int
    let title: String
    let artist: String
    let album: String
    let duration: TimeInterval
    let artwork: String?
    let source: AudioSource
    let song: Song?
    let localSong: LocalSong?
    
    var displayDuration: String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

struct SearchResultRow: View {
    let result: SearchResult
    let onPlay: () -> Void
    let onAddToQueue: () -> Void
    
    var body: some View {
        HStack(spacing: 12) {
            // Artwork
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(red: 0.16, green: 0.16, blue: 0.16))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: "music.note")
                        .font(.system(size: 20))
                        .foregroundColor(.gray)
                )
            
            // Song Info
            VStack(alignment: .leading, spacing: 4) {
                Text(result.title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                
                HStack {
                    Text(result.artist)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text("•")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text(result.album)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Spacer()
                    
                    Text(result.displayDuration)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text(result.source.displayName)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(result.source == .server ? .green : .orange)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background((result.source == .server ? Color.green : Color.orange).opacity(0.2))
                        .cornerRadius(4)
                }
            }
            
            Spacer()
            
            // Actions
            HStack(spacing: 12) {
                Button(action: onAddToQueue) {
                    Image(systemName: "plus.circle")
                        .font(.system(size: 20))
                        .foregroundColor(.green)
                }
                
                Button(action: onPlay) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 24))
                        .foregroundColor(.green)
                }
            }
        }
        .padding(.vertical, 8)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(8)
    }
}

extension String {
    func localizedCaseInsensitiveContains(_ string: String) -> Bool {
        return self.localizedStandardContains(string)
    }
}