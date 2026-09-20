# SoundTube Tasks & Execution Roadmap

## Phase 1: Project Foundation & Build System Setup
- [x] Initialize Android project structure with Kotlin 2.0+ and Gradle Version Catalog.
- [x] Add NewPipeExtractor (`v0.24.4`), AndroidX Media3 (`1.4.1`), Room (`2.6.1`), and Material 3 dependencies.
- [x] Configure `coreLibraryDesugaring` and ProGuard keep rules for Mozilla Rhino in NewPipeExtractor.
- [x] Implement `SoundTubeApplication` initializing Downloader and NewPipe extractor instance.

## Phase 2: Domain Models & Core Contracts
- [x] Define stream and playback models: `AudioStreamInfo`, `VideoFeedItem`, `DownloadItemState`.
- [x] Define Room entities: `PlaybackHistoryEntity`, `ChannelEntity`, `DownloadRecordEntity`.
- [x] Implement Room DAOs with Flow queries for subscriptions, history, and downloads.
- [x] Implement `StreamExtractorRepository` wrapping NewPipeExtractor with coroutines and error mapping.

## Phase 3: Background Audio Engine & Playback Resumption (Media3)
- [ ] Create `PlaybackService` extending AndroidX `MediaSessionService`.
- [ ] Configure `ExoPlayer` instance tuned specifically for audio playback (buffer sizes, wake lock).
- [ ] Implement `PlaybackResumptionManager` saving progress on position change and restoring on track start.
- [ ] Configure system media notification controls (play/pause, seek forward/back, title/channel metadata).

## Phase 4: Offline Audio Download Engine (P0)
- [ ] Configure Media3 `DownloadManager` with dedicated cache directory and single-track audio downloader.
- [ ] Implement `DownloadRepository` tracking download status, progress Flow, and local file management.
- [ ] Expose offline playback fallback: automatically switch DataSource to local cache if track is downloaded.

## Phase 5: Local Subscriptions & Feed Ingestion
- [ ] Implement channel subscription toggle (Subscribe / Unsubscribe) in Room database.
- [ ] Implement feed aggregation usecase fetching recent uploads across subscribed channels.
- [ ] Add channel details screen showing audio uploads list.

## Phase 6: Accessible Presentation Layer (Jetpack Compose & TalkBack / Jieshuo)
- [ ] Build Search Screen with instant accessibility readout (`liveRegion`) and search history.
- [ ] Build Player Screen and persistent Mini-Player with unambiguous accessibility labels and state descriptions.
- [ ] Build Library / Downloads Screen with offline playback actions and storage cleanup.
- [ ] Build Subscriptions / Feed Screen with custom accessibility actions for quick play and unsubscribe.
- [ ] Verify linear focus order, touch targets (>= 48dp), and zero emoji noise in screen reader labels.

## Phase 7: Verification & Build
- [ ] Run unit tests for Room DAOs, Extractor repository, and Playback resumption logic.
- [ ] Execute `./gradlew assembleDebug` and verify APK integrity.
