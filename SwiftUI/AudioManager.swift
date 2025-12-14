import Foundation
import AVFoundation
import Combine

class AudioManager: ObservableObject {
    private var player: AVAudioPlayer?
    private var songs: [Song] = []
    private var currentSongIndex: Int = 0
    
    func loadSong(_ song: Song) {
        guard let url = URL(string: song.url) else { return }
        
        do {
            player = try AVAudioPlayer(contentsOf: url)
            player?.prepareToPlay()
            player?.delegate = self
        } catch {
            print("Error loading song: \(error)")
        }
    }
    
    func play() {
        player?.play()
    }
    
    func pause() {
        player?.pause()
    }
    
    func stop() {
        player?.stop()
        player?.currentTime = 0
    }
    
    func skipToNext() {
        // Implementation for skipping to next song
        currentSongIndex = (currentSongIndex + 1) % songs.count
        if currentSongIndex < songs.count {
            loadSong(songs[currentSongIndex])
            play()
        }
    }
    
    func skipToPrevious() {
        // Implementation for skipping to previous song
        currentSongIndex = currentSongIndex > 0 ? currentSongIndex - 1 : songs.count - 1
        if currentSongIndex < songs.count {
            loadSong(songs[currentSongIndex])
            play()
        }
    }
    
    func setVolume(_ volume: Float) {
        player?.volume = volume
    }
    
    func seekTo(_ time: TimeInterval) {
        player?.currentTime = time
    }
    
    func getCurrentTime() -> TimeInterval {
        return player?.currentTime ?? 0
    }
    
    func getDuration() -> TimeInterval {
        return player?.duration ?? 0
    }
    
    func setSongs(_ songs: [Song]) {
        self.songs = songs
        self.currentSongIndex = 0
    }
}

extension AudioManager: AVAudioPlayerDelegate {
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        // Handle when song finishes playing
        if flag {
            skipToNext()
        }
    }
}