import Foundation

class ConnectionManager: ObservableObject {
    @Published var connectedServer: ServerConnection?
    @Published var discoveredServers: [ServerConnection] = []
    @Published var knownServers: [ServerInfo] = []
    @Published var isConnecting = false
    @Published var isScanning = false
    @Published var connectionError: String?
    @Published var apiManager: JMusicAPIManager?
    @Published var webSocketManager: JMusicWebSocketManager?
    
    private let userDefaults = UserDefaults.standard
    private let serverInfoKey = "known_servers"
    
    init() {
        loadKnownServers()
    }
    
    func parseQRCode(_ qrString: String) -> ServerConnection? {
        // Expected formats:
        // Local: "JMusic://192.168.1.100:8080/ServerID/ServerName"
        // Online: "JMusic://music.example.com:8080/ServerID/ServerName"
        // Both: "JMusic://192.168.1.100:8080,music.example.com:8080/ServerID/ServerName"
        
        guard qrString.hasPrefix("JMusic://") else {
            connectionError = "Invalid QR code format"
            return nil
        }
        
        let trimmedString = qrString.replacingOccurrences(of: "JMusic://", with: "")
        let components = trimmedString.components(separatedBy: "/")
        
        guard components.count >= 3 else {
            connectionError = "Invalid QR code format"
            return nil
        }
        
        let addressComponent = components[0]
        let serverIdString = components[1]
        let serverName = components[2]
        
        // Parse server ID
        guard let serverId = UUID(uuidString: serverIdString) else {
            connectionError = "Invalid server ID"
            return nil
        }
        
        let serverInfo = ServerInfo(id: serverId, name: serverName)
        
        // Handle multiple addresses (local and online)
        let addresses = addressComponent.components(separatedBy: ",")
        
        // Try local addresses first, then online
        for address in addresses {
            let addressParts = address.trimmingCharacters(in: .whitespaces).components(separatedBy: ":")
            guard addressParts.count == 2,
                  let port = Int(addressParts[1]) else {
                continue
            }
            
            let url = addressParts[0].trimmingCharacters(in: .whitespaces)
            
            // Test if this address is reachable
            if await isAddressReachable(url: url, port: port) {
                return ServerConnection(serverInfo: serverInfo, url: url, port: port)
            }
        }
        
        // If no address is immediately reachable, return the first one for manual connection
        if let firstAddress = addresses.first {
            let addressParts = firstAddress.trimmingCharacters(in: .whitespaces).components(separatedBy: ":")
            if addressParts.count == 2, let port = Int(addressParts[1]) {
                let url = addressParts[0].trimmingCharacters(in: .whitespaces)
                return ServerConnection(serverInfo: serverInfo, url: url, port: port)
            }
        }
        
        connectionError = "No valid server addresses found"
        return nil
    }
    
    private func isAddressReachable(url: String, port: Int) async -> Bool {
        let testURL = URL(string: "http://\(url):\(port)/api/profiles")!
        
        do {
            let (_, response) = try await URLSession.shared.data(from: testURL)
            if let httpResponse = response as? HTTPURLResponse {
                return httpResponse.statusCode == 200
            }
        } catch {
            // Address not reachable
        }
        
        return false
    }
    
    func discoverServers() async {
        isScanning = true
        connectionError = nil
        
        var discovered: [ServerConnection] = []
        
        // 1. Scan local network
        let localServers = await scanLocalNetwork()
        discovered.append(contentsOf: localServers)
        
        // 2. Try to reconnect to known servers with updated IPs
        let knownServersWithUpdatedIPs = await scanKnownServers()
        discovered.append(contentsOf: knownServersWithUpdatedIPs)
        
        // 3. Remove duplicates (same server ID)
        let uniqueServers = removeDuplicateServers(discovered)
        
        DispatchQueue.main.async {
            self.discoveredServers = uniqueServers
            self.isScanning = false
        }
    }
    
    private func scanLocalNetwork() async -> [ServerConnection] {
        let baseIPs = getLocalNetworkPrefixes()
        let commonPorts = [8080, 3000, 8081, 5000]
        
        var localServers: [ServerConnection] = []
        
        await withTaskGroup(of: ServerConnection?.self) { group in
            for baseIP in baseIPs {
                for port in commonPorts {
                    for i in 1...254 {
                        group.addTask {
                            let testIP = "\(baseIP).\(i)"
                            return await self.testServerConnection(ip: testIP, port: port)
                        }
                    }
                }
            }
            
            for await result in group {
                if let server = result {
                    localServers.append(server)
                }
            }
        }
        
        return localServers
    }
    
    private func scanKnownServers() async -> [ServerConnection] {
        var reconnectedServers: [ServerConnection] = []
        
        for knownServer in knownServers {
            // Try common local network ranges for this known server
            let baseIPs = getLocalNetworkPrefixes()
            let commonPorts = [8080, 3000, 8081, 5000]
            
            for baseIP in baseIPs {
                for port in commonPorts {
                    for i in 1...254 {
                        let testIP = "\(baseIP).\(i)"
                        if let server = await testServerConnectionWithID(ip: testIP, port: port, expectedServerID: knownServer.id) {
                            reconnectedServers.append(server)
                            break // Found the server, move to next known server
                        }
                    }
                }
            }
        }
        
        return reconnectedServers
    }
    
    private func testServerConnection(ip: String, port: Int) async -> ServerConnection? {
        let url = URL(string: "http://\(ip):\(port)/api/profiles")!
        
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            
            if let httpResponse = response as? HTTPURLResponse,
               httpResponse.statusCode == 200 {
                
                // Create a basic server info since we don't have a dedicated endpoint
                let serverInfo = ServerInfo(id: UUID(), name: "JMusic Server at \(ip)")
                
                return ServerConnection(serverInfo: serverInfo, url: ip, port: port)
            }
        } catch {
            // Server not found at this address, continue
        }
        
        return nil
    }
    
    private func testServerConnectionWithID(ip: String, port: Int, expectedServerID: UUID) async -> ServerConnection? {
        // For now, we can't match by ID since there's no server info endpoint
        // We'll just check if it's a JMusic server
        return await testServerConnection(ip: ip, port: port)
    }
    
    private func removeDuplicateServers(_ servers: [ServerConnection]) -> [ServerConnection] {
        var uniqueServers: [ServerConnection] = []
        var seenAddresses: Set<String> = []
        
        for server in servers {
            let address = "\(server.url):\(server.port)"
            if !seenAddresses.contains(address) {
                uniqueServers.append(server)
                seenAddresses.insert(address)
            }
        }
        
        return uniqueServers
    }
    
    private func getLocalNetworkPrefixes() -> [String] {
        // Get common local network prefixes
        // In production, you'd get actual network interface information
        return [
            "192.168.1",
            "192.168.0", 
            "10.0.0",
            "172.16.0",
            "192.168.2"
        ]
    }
    
    func connectToServer(_ server: ServerConnection) async {
        isConnecting = true
        connectionError = nil
        
        do {
            // Create API manager for this server
            let api = JMusicAPIManager(baseURL: server.fullURL)
            
            // Test connection by getting profiles
            let profiles = try await api.getProfiles()
            
            // Get current profile
            if let currentProfile = try await api.getCurrentProfile() {
                api.currentProfile = currentProfile
            } else if let firstProfile = profiles.first {
                // Switch to first available profile
                try await api.switchToProfile(firstProfile.id)
                api.currentProfile = firstProfile
            }
            
            // Success - save connection
            DispatchQueue.main.async {
                self.connectedServer = server
                self.apiManager = api
                self.saveKnownServer(server.serverInfo)
                
                // Setup WebSocket connection
                if let profileId = api.currentProfile?.id {
                    let webSocketManager = JMusicWebSocketManager(baseURL: server.fullURL)
                    webSocketManager.connect(profileId: profileId)
                    
                    // Store WebSocket manager
                    self.webSocketManager = webSocketManager
                }
                
                // Notify that connection is established
                NotificationCenter.default.post(
                    name: Notification.Name("ServerConnected"),
                    object: api
                )
            }
            
        } catch {
            connectionError = "Failed to connect: \(error.localizedDescription)"
        }
        
        isConnecting = false
    }
    
    func connectToManualServer(ip: String, port: Int, serverName: String) async {
        isConnecting = true
        connectionError = nil
        
        // Validate IP address format
        guard isValidIPAddress(ip) else {
            connectionError = "Invalid IP address format"
            isConnecting = false
            return
        }
        
        let serverInfo = ServerInfo(id: UUID(), name: serverName)
        let server = ServerConnection(serverInfo: serverInfo, url: ip, port: port)
        
        await connectToServer(server)
    }
    
    private func isValidIPAddress(_ ip: String) -> Bool {
        // Check for IP address format (e.g., 192.168.1.100)
        let ipRegex = #"^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"#
        let ipPredicate = NSPredicate(format: "SELF MATCHES %@", ipRegex)
        
        // Also check for hostname format (e.g., music.example.com)
        let hostnameRegex = #"^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*$"#
        let hostnamePredicate = NSPredicate(format: "SELF MATCHES %@", hostnameRegex)
        
        return ipPredicate.evaluate(with: ip) || hostnamePredicate.evaluate(with: ip)
    }
    
    private func saveKnownServer(_ serverInfo: ServerInfo) {
        if let index = knownServers.firstIndex(where: { $0.id == serverInfo.id }) {
            knownServers[index] = serverInfo
        } else {
            knownServers.append(serverInfo)
        }
        saveKnownServers()
    }
    
    private func loadKnownServers() {
        if let data = userDefaults.data(forKey: serverInfoKey) {
            do {
                let decoder = JSONDecoder()
                knownServers = try decoder.decode([ServerInfo].self, from: data)
            } catch {
                print("Failed to load known servers: \(error)")
            }
        }
    }
    
    private func saveKnownServers() {
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(knownServers)
            userDefaults.set(data, forKey: serverInfoKey)
        } catch {
            print("Failed to save known servers: \(error)")
        }
    }
    
    func disconnect() {
        // Disconnect WebSocket
        webSocketManager?.disconnect()
        webSocketManager = nil
        
        connectedServer = nil
        apiManager = nil
        
        // Notify disconnection
        NotificationCenter.default.post(
            name: Notification.Name("ServerDisconnected"),
            object: nil
        )
    }
}
}