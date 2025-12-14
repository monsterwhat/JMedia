import SwiftUI

struct SleepTimerView: View {
    @EnvironmentObject var audioManager: UnifiedAudioManager
    @State private var isTimerActive = false
    @State private var selectedMinutes: Int = 30
    @State private var customMinutes: String = ""
    @State private var remainingTime: TimeInterval = 0
    @State private var timer: Timer?
    
    private let presetMinutes = [5, 10, 15, 30, 45, 60, 90, 120]
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                HStack {
                    Text("Sleep Timer")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.white)
                    
                    Spacer()
                    
                    if isTimerActive {
                        Button(action: {
                            stopTimer()
                        }) {
                            HStack {
                                Image(systemName: "stop.circle.fill")
                                    .font(.system(size: 16))
                                Text("Stop")
                                    .font(.system(size: 16, weight: .medium))
                            }
                            .foregroundColor(.red)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.red.opacity(0.1))
                            .cornerRadius(6)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 20)
                
                // Timer Display
                if isTimerActive {
                    VStack(spacing: 20) {
                        Text("Sleep timer active")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.green)
                        
                        Text(formatTime(remainingTime))
                            .font(.system(size: 48, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                        
                        Text("Music will pause in")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                    }
                    .padding(.vertical, 40)
                } else {
                    // Timer Setup
                    VStack(spacing: 24) {
                        Text("Set Sleep Timer")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(.white)
                        
                        // Preset Buttons
                        VStack(spacing: 12) {
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 12) {
                                ForEach(presetMinutes, id: \.self) { minutes in
                                    SleepTimerButton(
                                        minutes: minutes,
                                        isSelected: selectedMinutes == minutes
                                    ) {
                                        selectedMinutes = minutes
                                        customMinutes = ""
                                    }
                                }
                            }
                        }
                        
                        // Custom Input
                        VStack(spacing: 12) {
                            Text("Custom Time")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundColor(.white)
                            
                            HStack(spacing: 12) {
                                TextField("Enter minutes", text: $customMinutes)
                                    .textFieldStyle(PlainTextFieldStyle())
                                    .foregroundColor(.white)
                                    .keyboardType(.numberPad)
                                    .padding(12)
                                    .background(Color(red: 0.12, green: 0.12, blue: 0.12))
                                    .cornerRadius(8)
                                
                                Text("minutes")
                                    .font(.system(size: 16))
                                    .foregroundColor(.gray)
                            }
                        }
                        
                        // Start Button
                        Button(action: {
                            startTimer()
                        }) {
                            HStack {
                                Image(systemName: "moon.fill")
                                    .font(.system(size: 20))
                                Text("Start Timer")
                                    .font(.system(size: 18, weight: .semibold))
                            }
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(Color.green)
                            .cornerRadius(12)
                        }
                        .disabled(getTimerMinutes() <= 0)
                    }
                    .padding(.horizontal, 20)
                }
                
                Spacer()
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onDisappear {
            stopTimer()
        }
    }
    
    private func getTimerMinutes() -> Int {
        if !customMinutes.isEmpty {
            return Int(customMinutes) ?? 0
        }
        return selectedMinutes
    }
    
    private func startTimer() {
        let minutes = getTimerMinutes()
        guard minutes > 0 else { return }
        
        isTimerActive = true
        remainingTime = TimeInterval(minutes * 60)
        
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            if remainingTime > 0 {
                remainingTime -= 1
            } else {
                stopTimer()
                pausePlayback()
            }
        }
    }
    
    private func stopTimer() {
        timer?.invalidate()
        timer = nil
        isTimerActive = false
        remainingTime = 0
    }
    
    private func pausePlayback() {
        Task {
            await audioManager.pause()
        }
        
        // Show notification
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            // This would show a local notification or alert
            print("Sleep timer: Music paused")
        }
    }
    
    private func formatTime(_ interval: TimeInterval) -> String {
        let hours = Int(interval) / 3600
        let minutes = (Int(interval) % 3600) / 60
        let seconds = Int(interval) % 60
        
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }
}

struct SleepTimerButton: View {
    let minutes: Int
    let isSelected: Bool
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text("\(minutes)m")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(isSelected ? .black : .white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(isSelected ? Color.green : Color(red: 0.16, green: 0.16, blue: 0.16))
                .cornerRadius(8)
        }
    }
}