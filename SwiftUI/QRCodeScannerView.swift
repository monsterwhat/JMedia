import SwiftUI
import AVFoundation

struct QRCodeScannerView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var connectionManager: ConnectionManager
    @StateObject private var scannerModel = QRCodeScannerModel()
    
    var body: some View {
        NavigationView {
            ZStack {
                // Camera preview
                CameraPreview(scannerModel: scannerModel)
                    .ignoresSafeArea()
                
                // Overlay
                VStack {
                    // Top overlay
                    Rectangle()
                        .fill(Color.black.opacity(0.7))
                        .frame(height: 100)
                    
                    // Scanning area
                    Spacer()
                    
                    // Scanning frame
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.green, lineWidth: 3)
                        .frame(width: 250, height: 250)
                        .overlay(
                            VStack {
                                HStack {
                                    Rectangle()
                                        .fill(Color.green)
                                        .frame(width: 20, height: 3)
                                    Spacer()
                                    Rectangle()
                                        .fill(Color.green)
                                        .frame(width: 20, height: 3)
                                }
                                Spacer()
                                HStack {
                                    Rectangle()
                                        .fill(Color.green)
                                        .frame(width: 20, height: 3)
                                    Spacer()
                                    Rectangle()
                                        .fill(Color.green)
                                        .frame(width: 20, height: 3)
                                }
                            }
                        )
                    
                    Spacer()
                    
                    // Bottom overlay with instructions
                    Rectangle()
                        .fill(Color.black.opacity(0.7))
                        .frame(height: 200)
                        .overlay(
                            VStack(spacing: 16) {
                                Text("Scan QR Code")
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundColor(.white)
                                
                                Text("Position the QR code within the frame to connect to your JMusic server")
                                    .font(.system(size: 14))
                                    .foregroundColor(.gray)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 40)
                                
                                Button("Cancel") {
                                    dismiss()
                                }
                                .font(.system(size: 16, weight: .medium))
                                .foregroundColor(.green)
                            }
                        )
                }
                
                // Success overlay
                if scannerModel.isScanning {
                    Color.black.opacity(0.8)
                        .overlay(
                            VStack(spacing: 20) {
                                ProgressView()
                                    .scaleEffect(1.5)
                                    .tint(.green)
                                
                                Text("Connecting to server...")
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundColor(.white)
                            }
                        )
                }
            }
            .navigationBarHidden(true)
            .onReceive(scannerModel.$scannedCode) { code in
                if let code = code {
                    Task {
                        await handleScannedCode(code)
                    }
                }
            }
            .onReceive(scannerModel.$connectionError) { error in
                if let error = error {
                    // Show error and allow retry
                    scannerModel.showError(error)
                }
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
    
    private func handleScannedCode(_ code: String) async {
        scannerModel.isScanning = true
        
        if let server = connectionManager.parseQRCode(code) {
            await connectionManager.connectToServer(server)
            
            if connectionManager.connectedServer != nil {
                // Success - dismiss scanner
                dismiss()
            }
        }
        
        scannerModel.isScanning = false
    }
}

class QRCodeScannerModel: NSObject, ObservableObject {
    @Published var scannedCode: String?
    @Published var isScanning = false
    @Published var connectionError: String?
    
    func showError(_ error: String) {
        self.connectionError = error
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            self.connectionError = nil
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let scannerModel: QRCodeScannerModel
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: UIScreen.main.bounds)
        
        // Create camera preview
        let captureSession = AVCaptureSession()
        
        guard let captureDevice = AVCaptureDevice.default(for: .video) else {
            print("No camera available")
            return view
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: captureDevice)
            
            if captureSession.canAddInput(input) {
                captureSession.addInput(input)
            }
            
            // Add metadata output for QR code scanning
            let metadataOutput = AVCaptureMetadataOutput()
            metadataOutput.setMetadataObjectsDelegate(context.coordinator, queue: DispatchQueue.main)
            
            if captureSession.canAddOutput(metadataOutput) {
                captureSession.addOutput(metadataOutput)
                metadataOutput.metadataObjectTypes = [.qr]
            }
            
            // Create preview layer
            let previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
            previewLayer.frame = view.frame
            previewLayer.videoGravity = .resizeAspectFill
            view.layer.addSublayer(previewLayer)
            
            // Start session
            DispatchQueue.global(qos: .userInitiated).async {
                captureSession.startRunning()
            }
            
        } catch {
            print("Error setting up camera: \(error)")
        }
        
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(scannerModel: scannerModel)
    }
    
    class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let scannerModel: QRCodeScannerModel
        private var lastScannedTime: Date = Date()
        
        init(scannerModel: QRCodeScannerModel) {
            self.scannerModel = scannerModel
        }
        
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            // Prevent multiple scans of the same code
            let now = Date()
            if now.timeIntervalSince(lastScannedTime) < 2.0 {
                return
            }
            
            guard let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let stringValue = metadataObject.stringValue else {
                return
            }
            
            lastScannedTime = now
            scannerModel.scannedCode = stringValue
        }
    }
}

#Preview {
    QRCodeScannerView(connectionManager: ConnectionManager())
}