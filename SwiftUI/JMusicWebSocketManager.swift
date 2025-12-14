import Foundation
import Combine
import SwiftUI

// MARK: - WebSocket Manager
class JMusicWebSocketManager: ObservableObject {
    @Published var isConnected = false
    @Published var connectionError: String?
    
    private var webSocketTask: URLSessionWebSocketTask?
    private var cancellables = Set<AnyCancellable>()
    private let baseURL: String
    private var profileId: Int?
    
    init(baseURL: String) {
        self.baseURL = baseURL
    }
    
    // MARK: - Connection Management
    func connect(profileId: Int) {
        self.profileId = profileId
        disconnect() // Ensure clean connection
        
        let url = URL(string: "\(baseURL)/api/music/ws/\(profileId)")!
        let request = URLRequest(url: url)
        
        webSocketTask = URLSession.shared.webSocketTask(with: request)
        webSocketTask?.resume()
        
        setupMessageHandling()
    }
    
    func disconnect() {
        webSocketTask?.cancel(with: .goingAway)
        webSocketTask = nil
        isConnected = false
    }
    
    // MARK: - Message Handling
    private func setupMessageHandling() {
        guard let webSocketTask = webSocketTask else { return }
        
        // Handle incoming messages
        webSocketTask.receive { [weak self] result in
            switch result {
            case .failure(let error):
                DispatchQueue.main.async {
                    self?.connectionError = error.localizedDescription
                }
            case .success(let message):
                self?.handleMessage(message)
            }
        }
    }
    
    private func handleMessage(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .string(let text):
            handleTextMessage(text)
        case .data:
            break // Handle binary data if needed
        @unknown default:
            break
        }
    }
    
    private func handleTextMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = message["type"] as? String else {
            return
        }
        
        switch type {
        case "state":
            handleStateUpdate(message)
        case "history-update":
            handleHistoryUpdate(message)
        default:
            print("Unknown message type: \(type)")
        }
    }
    
    private func handleStateUpdate(_ message: [String: Any]) {
        guard let payload = message["payload"] as? [String: Any] else { return }
        
        // Extract playback state
        let currentSongId = payload["currentSongId"] as? Int
        let isPlaying = payload["isPlaying"] as? Bool ?? false
        let position = payload["position"] as? Double ?? 0.0
        let volume = payload["volume"] as? Float ?? 0.8
        let shuffle = payload["shuffle"] as? Bool ?? false
        let repeat = payload["repeat"] as? Bool ?? false
        
        // Create playback state object
        let playbackState = PlaybackState(
            currentSongId: currentSongId,
            isPlaying: isPlaying,
            position: position,
            volume: volume,
            shuffle: shuffle,
            repeat: repeat
        )
        
        // Notify about state update
        NotificationCenter.default.post(
            name: Notification.Name("WebSocketPlaybackStateUpdate"),
            object: playbackState
        )
    }
    
    private func handleHistoryUpdate(_ message: [String: Any]) {
        // Handle history updates if needed
        NotificationCenter.default.post(
            name: Notification.Name("WebSocketHistoryUpdate"),
            object: message
        )
    }
    
    // MARK: - Sending Messages
    func sendMessage(type: String, payload: [String: Any]) {
        guard isConnected else { return }
        
        let message: [String: Any] = [
            "type": type,
            "payload": payload
        ]
        
        do {
            let data = try JSONSerialization.data(withJSONObject: message)
            let string = String(data: data, encoding: .utf8)!
            
            webSocketTask?.send(.string(string))
        } catch {
            print("Failed to serialize WebSocket message: \(error)")
        }
    }
    
    // MARK: - Control Methods
    func setProfile(_ profileId: Int) {
        sendMessage(type: "setProfile", payload: ["profileId": profileId])
    }
    
    func seek(_ position: Double) {
        sendMessage(type: "seek", payload: ["value": position])
    }
    
    func setVolume(_ volume: Float) {
        sendMessage(type: "volume", payload: ["value": volume])
    }
    
    func next() {
        sendMessage(type: "next", payload: [:])
    }
    
    func previous() {
        sendMessage(type: "previous", payload: [:])
    }
    
    func togglePlay() {
        sendMessage(type: "toggle-play", payload: [:])
    }
}

