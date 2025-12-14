import SwiftUI
import PhotosUI

struct LocalLibraryView: View {
    @StateObject private var localLibraryManager = LocalLibraryManager()
    @State private var selectedSongs: Set<UUID> = []
    @State private var showingImporter = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text("Local Library")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Button(action: {
                        showingImporter = true
                    }) {
                        Image(systemName: "plus")
                            .font(.system(size: 20))
                            .foregroundColor(.green)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Content
                if localLibraryManager.localSongs.isEmpty {
                    VStack(spacing: 20) {
                        Spacer()
                        
                        Image(systemName: "music.note.list")
                            .font(.system(size: 60))
                            .foregroundColor(.gray)
                        
                        Text("No Local Songs")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundColor(.white)
                        
                        Text("Add music files to build your local library")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                        
                        Button(action: {
                            showingImporter = true
                        }) {
                            HStack {
                                Image(systemName: "plus.circle.fill")
                                    .font(.system(size: 20))
                                Text("Add Music Files")
                                    .font(.system(size: 16, weight: .medium))
                            }
                            .foregroundColor(.black)
                            .padding(.horizontal, 24)
                            .padding(.vertical, 12)
                            .background(Color.green)
                            .cornerRadius(8)
                        }
                        
                        Spacer()
                    }
                    .padding(.horizontal, 40)
                } else {
                    // Song List
                    List {
                        ForEach(localLibraryManager.localSongs) { song in
                            LocalSongRow(
                                song: song,
                                isSelected: selectedSongs.contains(song.id)
                            ) {
                                if selectedSongs.contains(song.id) {
                                    selectedSongs.remove(song.id)
                                } else {
                                    selectedSongs.insert(song.id)
                                }
                            } onPlay: {
                                // Play song - this would be handled by parent view
                                NotificationCenter.default.post(
                                    name: Notification.Name("PlayLocalSong"),
                                    object: song
                                )
                            }
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
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: [.audio, .mpeg4Audio, .mp3],
            allowsMultipleSelection: true
        ) { result in
            handleFileImport(result)
        }
        .onAppear {
            localLibraryManager.loadLocalSongs()
        }
    }
    
    private func handleFileImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            Task {
                await localLibraryManager.importSongs(from: urls)
            }
        case .failure(let error):
            print("File import failed: \(error)")
        }
    }
}

struct LocalSongRow: View {
    let song: LocalSong
    let isSelected: Bool
    let onSelect: () -> Void
    let onPlay: () -> Void
    
    var body: some View {
        HStack(spacing: 12) {
            // Selection checkbox
            Button(action: onSelect) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundColor(isSelected ? .green : .gray)
            }
            
            // Song info
            VStack(alignment: .leading, spacing: 4) {
                Text(song.title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                
                HStack {
                    Text(song.artist)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text("•")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text(song.album)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Spacer()
                    
                    Text(song.displayDuration)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                }
            }
            
            Spacer()
            
            // Play button
            Button(action: onPlay) {
                Image(systemName: "play.circle.fill")
                    .font(.system(size: 24))
                    .foregroundColor(.green)
            }
        }
        .padding(.vertical, 8)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(8)
    }
}

// MARK: - Local Library Manager
class LocalLibraryManager: ObservableObject {
    @Published var localSongs: [LocalSong] = []
    @Published var isImporting = false
    
    private let documentsURL: URL
    private let songsDirectory: URL
    
    init() {
        documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        songsDirectory = documentsURL.appendingPathComponent("LocalSongs")
        
        // Create songs directory if it doesn't exist
        try? FileManager.default.createDirectory(at: songsDirectory, withIntermediateDirectories: true)
    }
    
    func loadLocalSongs() {
        // Load songs from local storage
        // This is a simplified version - in production you'd have a proper database
        localSongs = []
    }
    
    func importSongs(from urls: [URL]) async {
        isImporting = true
        
        for url in urls {
            await importSingleSong(from: url)
        }
        
        isImporting = false
    }
    
    private func importSingleSong(from url: URL) async {
        do {
            // Get file properties
            let asset = AVURLAsset(url: url)
            
            guard let title = (asset.value(for: .commonKeyTitle) as? String) ?? url.deletingPathExtension().lastPathComponent,
                  let artist = (asset.value(for: .commonKeyArtist) as? String) ?? "Unknown Artist",
                  let album = (asset.value(for: .commonKeyAlbum) as? String) ?? "Unknown Album" else {
                return
            }
            
            let duration = asset.duration.seconds
            
            // Copy file to local storage
            let fileName = "\(UUID().uuidString).\(url.pathExtension)"
            let destinationURL = songsDirectory.appendingPathComponent(fileName)
            
            try? FileManager.default.removeItem(at: destinationURL) // Remove if exists
            try FileManager.default.copyItem(at: url, to: destinationURL)
            
            // Create local song
            let localSong = LocalSong(
                title: title,
                artist: artist,
                album: album,
                duration: duration,
                localURL: destinationURL
            )
            
            DispatchQueue.main.async {
                self.localSongs.append(localSong)
            }
            
        } catch {
            print("Failed to import song: \(error)")
        }
    }
    
    func deleteSong(_ song: LocalSong) {
        do {
            try FileManager.default.removeItem(at: song.localURL)
            localSongs.removeAll { $0.id == song.id }
        } catch {
            print("Failed to delete song: \(error)")
        }
    }
}