import SwiftUI

struct ContentView: View {
    @State private var selectedTab = 0
    @StateObject private var connectionManager = ConnectionManager()
    @StateObject private var audioManager: UnifiedAudioManager
    
    init() {
        let connectionManager = ConnectionManager()
        let audioManager = UnifiedAudioManager(connectionManager: connectionManager)
        self._connectionManager = StateObject(wrappedValue: connectionManager)
        self._audioManager = StateObject(wrappedValue: audioManager)
    }
    
    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Image(systemName: "house.fill")
                    Text("Home")
                }
                .tag(0)
            
            PlayerView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "play.circle.fill")
                    Text("Player")
                }
                .tag(1)
            
            SearchView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "magnifyingglass")
                    Text("Search")
                }
                .tag(2)
            
            LibraryView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "music.note.list")
                    Text("Library")
                }
                .tag(3)
            
            QueueView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "list.bullet")
                    Text("Queue")
                }
                .tag(4)
            
            LyricsView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "text.quote")
                    Text("Lyrics")
                }
                .tag(5)
            
            SleepTimerView()
                .environmentObject(audioManager)
                .tabItem {
                    Image(systemName: "moon")
                    Text("Sleep")
                }
                .tag(6)
            
            LocalLibraryView()
                .environmentObject(audioManager)
                .tabItem {
                    Image(systemName: "folder.fill")
                    Text("Local")
                }
                .tag(7)
            
            SettingsView()
                .environmentObject(audioManager)
                .environmentObject(connectionManager)
                .tabItem {
                    Image(systemName: "gearshape.fill")
                    Text("Settings")
                }
                .tag(8)

        }
        .accentColor(.green)
        .onAppear {
            audioManager.autoDetectSource()
        }
    }
}

#Preview {
    ContentView()
}