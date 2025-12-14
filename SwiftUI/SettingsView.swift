import SwiftUI
import AVFoundation

struct SettingsView: View {
    @StateObject private var connectionManager = ConnectionManager()
    @State private var showingScanner = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text("Settings")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                    
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 30)
                
                ScrollView {
                    VStack(spacing: 20) {
                        // Connection Status Section
                        ConnectionStatusCard(connectionManager: connectionManager)
                        
                        // Connect Section
                        ConnectSection(connectionManager: connectionManager, showingScanner: $showingScanner)
                        
                        // Manual Connection Section
                        ManualConnectionSection(connectionManager: connectionManager)
                        
                        // Discovered Servers Section
                        DiscoveredServersSection(connectionManager: connectionManager)
                        
                        // App Settings Section
                        AppSettingsSection()
                    }
                    .padding(.horizontal, 20)
                }
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .sheet(isPresented: $showingScanner) {
            QRCodeScannerView(connectionManager: connectionManager)
        }
    }
}

struct ConnectionStatusCard: View {
    @ObservedObject var connectionManager: ConnectionManager
    
    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Image(systemName: connectionManager.connectedServer != nil ? "wifi" : "wifi.slash")
                    .font(.system(size: 24))
                    .foregroundColor(connectionManager.connectedServer != nil ? .green : .gray)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(connectionManager.connectedServer?.name ?? "Not Connected")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(.white)
                    
                    Text(connectionManager.connectedServer?.fullURL ?? "No server connection")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                }
                
                Spacer()
                
                if connectionManager.connectedServer != nil {
                    Button("Disconnect") {
                        connectionManager.disconnect()
                    }
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.red)
                }
            }
            
            if connectionManager.isConnecting {
                HStack {
                    ProgressView()
                        .scaleEffect(0.8)
                        .tint(.green)
                    
                    Text("Connecting...")
                        .font(.system(size: 14))
                        .foregroundColor(.green)
                }
            }
            
            if let error = connectionManager.connectionError {
                HStack {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 16))
                        .foregroundColor(.red)
                    
                    Text(error)
                        .font(.system(size: 14))
                        .foregroundColor(.red)
                        .multilineTextAlignment(.leading)
                }
            }
        }
        .padding(20)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(connectionManager.connectedServer != nil ? Color.green : Color(red: 0.2, green: 0.2, blue: 0.2), lineWidth: 1)
        )
    }
}

struct ConnectSection: View {
    @ObservedObject var connectionManager: ConnectionManager
    @Binding var showingScanner: Bool
    
    var body: some View {
        VStack(spacing: 16) {
            Text("Connect to Server")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            
            Button(action: {
                showingScanner = true
            }) {
                HStack {
                    Image(systemName: "qrcode")
                        .font(.system(size: 20))
                    
                    Text("Scan QR Code")
                        .font(.system(size: 16, weight: .medium))
                }
                .foregroundColor(.black)
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(Color.green)
                .cornerRadius(8)
            }
            
            Button(action: {
                Task {
                    await connectionManager.discoverServers()
                }
            }) {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 20))
                    
                    if connectionManager.isScanning {
                        HStack {
                            ProgressView()
                                .scaleEffect(0.8)
                                .tint(.black)
                            Text("Scanning...")
                                .font(.system(size: 16, weight: .medium))
                        }
                    } else {
                        Text("Discover Servers")
                            .font(.system(size: 16, weight: .medium))
                    }
                }
                .foregroundColor(.black)
                .frame(maxWidth: .infinity)
                .padding(16)
                .background(Color.green.opacity(0.8))
                .cornerRadius(8)
            }
            .disabled(connectionManager.isScanning)
            
            Text("Scan a QR code or discover JMusic servers on your local network")
                .font(.system(size: 14))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .padding(20)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(12)
    }
}

struct SavedConnectionsSection: View {
    @ObservedObject var connectionManager: ConnectionManager
    
    var body: some View {
        VStack(spacing: 16) {
            Text("Discovered Servers")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            
            if connectionManager.discoveredServers.isEmpty && !connectionManager.isScanning {
                Text("No servers found. Try scanning or discovering servers.")
                    .font(.system(size: 14))
                    .foregroundColor(.gray)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            } else if connectionManager.isScanning {
                HStack {
                    ProgressView()
                        .scaleEffect(0.8)
                        .tint(.green)
                    Text("Scanning network...")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
            } else {
                ForEach(connectionManager.discoveredServers) { server in
                    DiscoveredServerRow(server: server, connectionManager: connectionManager)
                }
            }
        }
        .padding(20)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(12)
    }
}

struct DiscoveredServerRow: View {
    let server: ServerConnection
    @ObservedObject var connectionManager: ConnectionManager
    
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(server.serverInfo.name)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                
                HStack {
                    Text(server.url)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    Text(":\(server.port)")
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                    
                    if connectionManager.connectedServer?.serverInfo.id == server.serverInfo.id {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundColor(.green)
                    }
                }
            }
            
            Spacer()
            
            if connectionManager.connectedServer?.serverInfo.id == server.serverInfo.id {
                Text("Connected")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.green)
            } else {
                Button("Connect") {
                    Task {
                        await connectionManager.connectToServer(server)
                    }
                }
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.green)
            }
        }
        .padding(.vertical, 8)
    }
}

struct ManualConnectionSection: View {
    @ObservedObject var connectionManager: ConnectionManager
    @State private var ipAddress: String = ""
    @State private var port: String = "8080"
    @State private var serverName: String = ""
    @State private var isExpanded = false
    
    var body: some View {
        VStack(spacing: 16) {
            Button(action: {
                withAnimation(.easeInOut(duration: 0.3)) {
                    isExpanded.toggle()
                }
            }) {
                HStack {
                    Image(systemName: "plus.circle")
                        .font(.system(size: 20))
                        .foregroundColor(.green)
                    
                    Text("Manual Connection")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 16))
                        .foregroundColor(.gray)
                }
            }
            
            if isExpanded {
                VStack(spacing: 16) {
                    VStack(spacing: 12) {
                        // IP Address Input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("IP Address or Hostname")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                            
                            TextField("192.168.1.100 or music.example.com", text: $ipAddress)
                                .textFieldStyle(PlainTextFieldStyle())
                                .padding(12)
                                .background(Color(red: 0.12, green: 0.12, blue: 0.12))
                                .cornerRadius(8)
                                .foregroundColor(.white)
                                .keyboardType(.URL)
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                        }
                        
                        // Port Input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Port")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                            
                            TextField("8080", text: $port)
                                .textFieldStyle(PlainTextFieldStyle())
                                .padding(12)
                                .background(Color(red: 0.12, green: 0.12, blue: 0.12))
                                .cornerRadius(8)
                                .foregroundColor(.white)
                                .keyboardType(.numberPad)
                        }
                        
                        // Server Name Input
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Server Name (Optional)")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.white)
                            
                            TextField("My JMusic Server", text: $serverName)
                                .textFieldStyle(PlainTextFieldStyle())
                                .padding(12)
                                .background(Color(red: 0.12, green: 0.12, blue: 0.12))
                                .cornerRadius(8)
                                .foregroundColor(.white)
                                .autocapitalization(.words)
                        }
                    }
                    
                    // Connect Button
                    Button(action: {
                        Task {
                            let portNumber = Int(port) ?? 8080
                            let name = serverName.isEmpty ? "Manual Server" : serverName
                            await connectionManager.connectToManualServer(
                                ip: ipAddress.trimmingCharacters(in: .whitespaces),
                                port: portNumber,
                                serverName: name
                            )
                        }
                    }) {
                        HStack {
                            if connectionManager.isConnecting {
                                ProgressView()
                                    .scaleEffect(0.8)
                                    .tint(.black)
                                Text("Connecting...")
                                    .font(.system(size: 16, weight: .medium))
                            } else {
                                Image(systemName: "network")
                                    .font(.system(size: 20))
                                Text("Connect")
                                    .font(.system(size: 16, weight: .medium))
                            }
                        }
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .padding(16)
                        .background(ipAddress.isEmpty ? Color.gray : Color.green)
                        .cornerRadius(8)
                    }
                    .disabled(ipAddress.isEmpty || connectionManager.isConnecting)
                    
                    // Error Display
                    if let error = connectionManager.connectionError {
                        HStack {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 16))
                                .foregroundColor(.red)
                            
                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(.red)
                                .multilineTextAlignment(.leading)
                        }
                        .padding(12)
                        .background(Color.red.opacity(0.1))
                        .cornerRadius(8)
                    }
                }
                .padding(.top, 8)
            }
        }
        .padding(20)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(12)
    }
}

struct AppSettingsSection: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("App Settings")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            
            VStack(spacing: 12) {
                SettingsRow(title: "Audio Quality", value: "High")
                SettingsRow(title: "Download Quality", value: "Lossless")
                SettingsRow(title: "Cache Size", value: "1.2 GB")
                SettingsRow(title: "Auto-sync", value: "Enabled")
            }
        }
        .padding(20)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(12)
    }
}

struct SettingsRow: View {
    let title: String
    let value: String
    
    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 16))
                .foregroundColor(.white)
            
            Spacer()
            
            Text(value)
                .font(.system(size: 16))
                .foregroundColor(.gray)
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    SettingsView()
}