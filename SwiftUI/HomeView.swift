import SwiftUI

struct HomeView: View {
    @StateObject private var playerState = PlayerState()
    
    var body: some View {
        NavigationView {
            GeometryReader { geometry in
                VStack(spacing: 0) {
                    // Header
                    VStack(spacing: 16) {
                        Text("JMusic")
                            .font(.system(size: 48, weight: .bold))
                            .foregroundColor(.white)
                        
                        Text("Your Music, Your Way")
                            .font(.system(size: 16))
                            .foregroundColor(.gray)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, geometry.size.height * 0.1)
                    .padding(.bottom, geometry.size.height * 0.05)
                    
                    // Content Cards
                    VStack(spacing: 16) {
                        NavigationLink(destination: PlayerView()) {
                            HomeCard(title: "Now Playing", subtitle: "Continue listening", icon: "play.circle.fill")
                        }
                        
                        NavigationLink(destination: LibraryView()) {
                            HomeCard(title: "Library", subtitle: "Browse your collection", icon: "music.note.list")
                        }
                    }
                    .padding(.horizontal, 20)
                    
                    Spacer()
                }
            }
            .background(Color.black)
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .environmentObject(playerState)
    }
}

struct HomeCard: View {
    let title: String
    let subtitle: String
    let icon: String
    
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 30))
                .foregroundColor(.green)
                .frame(width: 50, height: 50)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(.white)
                
                Text(subtitle)
                    .font(.system(size: 16))
                    .foregroundColor(.gray)
            }
            
            Spacer()
        }
        .padding(24)
        .background(Color(red: 0.16, green: 0.16, blue: 0.16))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color(red: 0.2, green: 0.2, blue: 0.2), lineWidth: 1)
        )
    }
}

#Preview {
    HomeView()
}