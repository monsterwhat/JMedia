import Foundation
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - Background Audio Manager
class BackgroundAudioManager: NSObject, ObservableObject {
    private let audioSession = AVAudioSession.sharedInstance()
    
    override init() {
        super.init()
        setupAudioSession()
        setupRemoteControls()
    }
    
    private func setupAudioSession() {
        do {
            // Configure audio session for background playback
            try audioSession.setCategory(
                .playback,
                mode: .default,
                options: [.allowAirPlay, .allowBluetooth, .allowBluetoothA2DP]
            )
            
            try audioSession.setActive(true)
            
            // Configure now playing info
            setupNowPlayingInfo()
            
        } catch {
            print("Failed to setup audio session: \(error)")
        }
    }
    
    private func setupRemoteControls() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Play command
        commandCenter.playCommand.addTarget { [weak self] event in
            self?.handlePlayCommand()
            return .success
        }
        
        // Pause command
        commandCenter.pauseCommand.addTarget { [weak self] event in
            self?.handlePauseCommand()
            return .success
        }
        
        // Next command
        commandCenter.nextTrackCommand.addTarget { [weak self] event in
            self?.handleNextCommand()
            return .success
        }
        
        // Previous command
        commandCenter.previousTrackCommand.addTarget { [weak self] event in
            self?.handlePreviousCommand()
            return .success
        }
        
        // Change playback position
        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            self?.handleSeekCommand(event.positionTime)
            return .success
        }
        
        // Enable commands
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.isEnabled = true
    }
    
    private func setupNowPlayingInfo() {
        var nowPlayingInfo = [String: Any]()
        
        // Set default values
        nowPlayingInfo[MPMediaItemPropertyTitle] = "JMusic"
        nowPlayingInfo[MPMediaItemPropertyArtist] = "Ready to play"
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = "Mobile Music App"
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = 0
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    // MARK: - Remote Control Handlers
    private func handlePlayCommand() {
        NotificationCenter.default.post(
            name: Notification.Name("RemotePlay"),
            object: nil
        )
    }
    
    private func handlePauseCommand() {
        NotificationCenter.default.post(
            name: Notification.Name("RemotePause"),
            object: nil
        )
    }
    
    private func handleNextCommand() {
        NotificationCenter.default.post(
            name: Notification.Name("RemoteNext"),
            object: nil
        )
    }
    
    private func handlePreviousCommand() {
        NotificationCenter.default.post(
            name: Notification.Name("RemotePrevious"),
            object: nil
        )
    }
    
    private func handleSeekCommand(_ position: TimeInterval) {
        NotificationCenter.default.post(
            name: Notification.Name("RemoteSeek"),
            object: position
        )
    }
    
    // MARK: - Public Methods
    func updateNowPlayingInfo(
        title: String,
        artist: String,
        album: String,
        duration: TimeInterval,
        artwork: UIImage? = nil
    ) {
        var nowPlayingInfo = [String: Any]()
        
        nowPlayingInfo[MPMediaItemPropertyTitle] = title
        nowPlayingInfo[MPMediaItemPropertyArtist] = artist
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        
        if let artwork = artwork {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(
                boundsSize: artwork.size,
                requestHandler: { size in
                    return artwork
                }
            )
        }
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    func updatePlaybackState(isPlaying: Bool, position: TimeInterval) {
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
        
        if isPlaying {
            MPNowPlayingInfoCenter.default().playbackRate = 1.0
            MPNowPlayingInfoCenter.default().playbackTime = position
        } else {
            MPNowPlayingInfoCenter.default().playbackRate = 0.0
        }
    }
    
    func clearNowPlayingInfo() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}