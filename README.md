# NOIZ — Music. Mood. Repeat.

A Java Swing desktop music player. MP3 decoding is added through the MP3SPI dependency in `pom.xml` (Maven downloads it automatically).

## Requirements
- JDK 17 or newer
- Apache Maven 3.8+
- Windows, macOS, or Linux desktop

## Run it
1. Extract this folder.
2. Open a terminal/CMD inside the `NOIZ-Desktop` folder (the folder containing `pom.xml`).
3. Run:

   ```bash
   mvn clean compile
   mvn exec:java
   ```

The first run needs an internet connection so Maven can download MP3SPI and its supporting libraries. After dependencies have downloaded, playback can run offline.

## Features
- MP3 playback through MP3SPI; WAV, AIFF and AU through Java Sound where supported
- Import multiple audio files using the file picker
- Drag audio files from File Explorer/Finder into the NOIZ window
- Search the music library by filename/path
- Play, pause, previous, next, seek/progress and volume controls
- Shuffle and repeat modes (off/all/one)
- Create playlists and add tracks to playlists
- Delete playlists
- Favourites section with add/remove toggle
- Recently played list (up to 30 tracks)
- Saves library, playlists, favourites and recent tracks between launches
- Keyboard shortcuts: Space = play/pause, Left/Right = previous/next, Ctrl+O (or Command+O) = import
- Dark purple desktop interface

## Notes
- This version uses filenames as track titles; it does not yet read embedded ID3 tags or show album artwork.
- Audio format support can vary by operating system and Java audio providers.
- Imported tracks are referenced by their file paths. Moving or deleting the original music files can make a saved library entry unavailable.
