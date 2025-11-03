# JMusicClient: A Decentralized Music Streaming Application

JMusicClient is a robust and innovative music streaming application designed to provide a truly decentralized and efficient way to manage, play, and share your music library. Unlike traditional streaming services, JMusicClient prioritizes user privacy and local data control, ensuring your music experience remains personal and secure. Built with Java and the high-performance Quarkus framework, it offers a rich web-based user interface accessible from any device, coupled with powerful backend services for music management, real-time updates, and peer-to-peer sharing.

This project leverages modern technologies to deliver a seamless user experience with a strong emphasis on user-centric principles:

*   **Decentralized Architecture & User Privacy:** JMusicClient is built on a decentralized model, giving users full control over their music and data. There are no central servers storing your personal information or listening habits. Your music library and preferences are managed locally, ensuring unparalleled privacy. Crucially, no data used within this application is shared with anyone unless manually specified by the user in the settings. This sharing functionality will never be automatically enabled, and the 'data' shared is exclusively related to torrents (metadata and peer information) and occurs only between explicitly approved peers.
*   **Local Data Management:** All your music files and metadata are stored and managed directly on your local machine. This not only enhances privacy but also provides faster access to your library and reduces reliance on external services.
*   **Web-Based UI:** A responsive and intuitive frontend built with HTML, CSS, and JavaScript, allowing users to access their music from any web browser.
*   **Comprehensive Music Library Management:** Organize your vast collection of songs, create custom playlists, and manage your audio files with ease. The application utilizes `jaudiotagger` for efficient audio metadata processing.
*   **Real-time Interactivity:** Powered by WebSockets, JMusicClient provides instant updates on playback state, playlist changes, and other critical events, ensuring a dynamic and engaging user experience.
*   **Peer-to-Peer Music Sharing:** At its core, JMusicClient incorporates a sophisticated torrent-like system, utilizing the `bt-core` library, to facilitate decentralized music sharing and discovery within a peer-to-peer network. This enables users to share and explore new music in a community-driven environment without compromising their privacy.
*   **Robust REST API:** A well-defined RESTful API, built with Quarkus REST and Jackson, offers extensive capabilities for programmatic control of the application, allowing for integration with other services or custom clients. API endpoints are managed within the `src/main/java/API/Rest` package.
*   **Efficient Data Management:** The application uses Hibernate ORM with Panache for simplified database interactions, backed by an H2 database for reliable and fast local data storage.

**Efficiency Note:** This software is engineered for optimal performance and resource utilization, aiming to be at least 50% more efficient, if not more, than traditional streaming services in its resource usage. This results in a significantly smaller ecological footprint, almost null when compared to traditional models, offering a streamlined and responsive experience.

**Technology Stack:**

*   **Backend:** Java, Quarkus
*   **Frontend:** HTML, CSS, JavaScript
*   **Database:** H2
*   **Real-time Communication:** WebSockets
*   **Peer-to-Peer:** bt-core (a BitTorrent library)

## Installation

JMusicClient is designed for easy installation and use by end-users. Pre-built executables are available in the [GitHub Releases](https://github.com/monsterwhat/JMusic/releases) section.

*   **Windows Executable:** Download the `JMusic.exe` from the releases page. Simply run the executable to start the application.
*   **Cross-Platform Uberjar:** Download the `JMusicClient-runner.jar ` from the releases page. Ensure you have Java 21 or newer installed on your system. You can run it via the command line or double clicking it:
    ```bash
    java -jar JMusicClient-runner.jar
    ```

*   **Upcoming Native Executables:** We are actively working on providing native executables for Linux, macOS, and Windows in an upcoming beta phase. These will offer optimized performance and standalone deployment without requiring a separate Java installation.

## For Developers

These instructions are for developers who want to build and run the project from source.

### Prerequisites

*   Java 21 or newer
*   Maven 3.8.x or newer

### Building and Running

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/monsterwhat/JMusic.git
    cd JMusicClient/com.playdeca.JMusicClient
    ```
 
2.  **Run in development mode:**
    ```bash
    mvn quarkus:dev
    ```
    This will start the application in development mode, enabling live coding.

3.  **Build for production:**
    ```bash
    mvn clean package
    ```
    This will create a `quarkus-run.jar` in the `target/quarkus-app/` directory. You can run it using:
    ```bash
    java -jar target/quarkus-app/quarkus-run.jar
    ```

## Usage

Once the application is running, you can access the web interface by opening your browser and navigating to:

```
http://localhost
```

From there, you can manage your music library, create playlists, and stream music.

## API Documentation

The application exposes a REST API for programmatic control and data access. You can find the API endpoints and their functionalities within the `src/main/java/API/Rest` package.

## Contributing

We welcome contributions to JMusicClient! If you'd like to contribute, please follow these steps:

1.  Fork the repository.
2.  Create a new branch for your feature or bug fix.
3.  Make your changes and ensure they are well-tested.
4.  Submit a pull request with a clear description of your changes.

## License

This project is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html) - see the `GPL-3.0.txt` file for details. This license ensures that you can freely use, modify, and distribute this software, provided that any derivative works are also released under the same free software license, preventing the creation of proprietary applications from this codebase.
