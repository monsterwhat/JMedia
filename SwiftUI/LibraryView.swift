import SwiftUI

struct LibraryView: View {
    @State private var selectedTab = 0
    @State private var songs: [Song] = []
    @State private var playlists: [Playlist] = []
    
    let tabs = ["Playlists", "Albums", "Artists"]
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Button(action: {}) {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 24))
                            .foregroundColor(.white)
                    }
                    
                    Spacer()
                    
                    Text("Your Library")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: {}) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 24))
                            .foregroundColor(.white)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Tabs
                HStack(spacing: 0) {
                    ForEach(0..<tabs.count, id: \.self) { index in
                        Button(action: { selectedTab = index }) {
                            VStack(spacing: 8) {
                                Text(tabs[index])
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(selectedTab == index ? .white : .gray)
                                
                                Rectangle()
                                    .fill(selectedTab == index ? Color.green : Color.clear)
                                    .frame(height: 2)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 20)
                
                // Content
                TabView(selection: $selectedTab) {
                    // Playlists Tab
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(playlists) { playlist in
                                PlaylistRow(playlist: playlist)
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .tag(0)
                    
                    // Albums Tab
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(songs, id: \.album) { song in
                                AlbumRow(song: song)
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .tag(1)
                    
                    // Artists Tab
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(songs, id: \.artist) { song in
                                ArtistRow(song: song)
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .tag(2)
                }
                .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))
                .animation(.easeInOut(duration: 0.3), value: selectedTab)
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            setupMockData()
            setupNotificationObserver()
        }
        .onDisappear {
            removeNotificationObserver()
        }
    }
    
    private func setupMockData() {
        songs = [
            Song(title: "Sample Song 1", artist: "Artist One", album: "Album One", duration: 180, url: ""),
            Song(title: "Sample Song 2", artist: "Artist Two", album: "Album Two", duration: 210, url: ""),
            Song(title: "Sample Song 3", artist: "Artist Three", album: "Album Three", duration: 195, url: ""),
            Song(title: "Sample Song 4", artist: "Artist Four", album: "Album Four", duration: 240, url: ""),
        ]
        
        playlists = [
            Playlist(name: "Favorites", songs: Array(songs.prefix(2))),
            Playlist(name: "Recently Played", songs: Array(songs.suffix(2))),
            Playlist(name: "Workout Mix", songs: songs),
        ]
    }
    
    private func setupNotificationObserver() {
        NotificationCenter.default.addObserver(
            forName: Notification.Name("LibrarySynced"),
            object: nil,
            queue: .main
        ) { notification in
            if let musicLibrary = notification.object as? MusicLibrary {
                self.songs = musicLibrary.songs
                self.playlists = musicLibrary.playlists
            }
        }
    }
    
    private func removeNotificationObserver() {
        NotificationCenter.default.removeObserver(self, name: Notification.Name("LibrarySynced"), object: nil)
    }
}

struct PlaylistRow: View {
    let playlist: Playlist
    
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color(red: 0.16, green: 0.16, blue: 0.16))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: "music.note.list")
                        .font(.system(size: 20))
                        .foregroundColor(.gray)
                )
            
            VStack(alignment: .leading, spacing: 4) {
                Text(playlist.name)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                
                Text("\(playlist.songs.count) songs")
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            
            Spacer()
            
            Button(action: {}) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 20))
                    .foregroundColor(.gray)
            }
        }
        .padding(.vertical, 8)
    }
}

struct AlbumRow: View {
    let song: Song
    
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color(red: 0.16, green: 0.16, blue: 0.16))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: "opticaldisc")
                        .font(.system(size: 20))
                        .foregroundColor(.gray)
                )
            
            VStack(alignment: .leading, spacing: 4) {
                Text(song.album)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                
                Text(song.artist)
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            
            Spacer()
            
            Button(action: {}) {
                Image(systemName: "play.circle")
                    .font(.system(size: 24))
                    .foregroundColor(.green)
            }
        }
        .padding(.vertical, 8)
    }
}

struct ArtistRow: View {
    let song: Song
    
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 25)
                .fill(Color(red: 0.16, green: 0.16, blue: 0.16))
                .frame(width: 50, height: 50)
                .overlay(
                    Image(systemName: "person.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.gray)
                )
            
            VStack(alignment: .leading, spacing: 4) {
                Text(song.artist)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                
                Text("Artist")
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
            }
            
            Spacer()
            
            Button(action: {}) {
                Image(systemName: "play.circle")
                    .font(.system(size: 24))
                    .foregroundColor(.green)
            }
        }
        .padding(.vertical, 8)
    }
}

#Preview {
    LibraryView()
}