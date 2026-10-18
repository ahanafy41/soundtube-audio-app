# SoundTube Tasks & Execution Roadmap

## Phase 1: Project Foundation & Build System Setup
- [x] Initialize Android project structure with Kotlin 2.0+ and Gradle Version Catalog.
- [x] Add NewPipeExtractor (v0.24.4), AndroidX Media3 (1.4.1), Room (2.6.1), and Material 3 dependencies.
- [x] Configure coreLibraryDesugaring and ProGuard keep rules for Mozilla Rhino in NewPipeExtractor.
- [x] Implement SoundTubeApplication initializing Downloader and NewPipe extractor instance.

## Phase 2: Domain Models & Core Contracts
- [x] Define stream and playback models: AudioStreamInfo, VideoFeedItem, DownloadItemState.
- [x] Define Room entities: PlaybackHistoryEntity, ChannelEntity, DownloadRecordEntity.
- [x] Implement Room DAOs with Flow queries for subscriptions, history, and downloads.
- [x] Implement StreamExtractorRepository wrapping NewPipeExtractor with coroutines and error mapping.

## Phase 3: Background Audio Engine & Playback Resumption (Media3)
- [x] Create PlaybackService extending AndroidX MediaSessionService.
- [x] Configure ExoPlayer instance tuned specifically for audio playback (buffer sizes, wake lock).
- [x] Implement PlaybackResumptionManager saving progress on position change and restoring on track start.
- [x] Configure system media notification controls (play/pause, seek forward/back, title/channel metadata).

## Phase 4: Offline Audio Download Engine (P0)
- [x] Configure Media3 DownloadManager with dedicated cache directory and single-track audio downloader.
- [x] Implement DownloadRepository tracking download status, progress Flow, and local file management.
- [x] Expose offline playback fallback: automatically switch DataSource to local cache if track is downloaded.

## Phase 5: Local Subscriptions & Feed Ingestion
- [x] Implement channel subscription toggle (Subscribe / Unsubscribe) in Room database.
- [x] Implement feed aggregation usecase fetching recent uploads across subscribed channels.
- [x] Add channel details screen showing audio uploads list.

## Phase 6: Accessible Presentation Layer (Jetpack Compose & TalkBack / Jieshuo)
- [x] Build Search Screen with instant accessibility readout (liveRegion) and search history.
- [x] Build Player Screen and persistent Mini-Player with unambiguous accessibility labels and state descriptions.
- [x] Build Library / Downloads Screen with offline playback actions and storage cleanup.
- [x] Build Subscriptions / Feed Screen with custom accessibility actions for quick play and unsubscribe.
- [x] Verify linear focus order, touch targets (>= 48dp), and zero emoji noise in screen reader labels.

## Phase 7: Google / YouTube Authentication & Encrypted Credential Management
- [x] Add androidx.security:security-crypto:1.1.0-alpha06 and androidx.webkit:webkit:1.12.0 dependencies.
- [x] Implement EncryptedAuthStorage wrapper using EncryptedSharedPreferences for secure token and cookie persistence.
- [x] Implement YouTubeAuthWebView with modern Chrome mobile User-Agent to bypass Google 403 error.
- [x] Extract SAPISID, HSID, SSID, SID, and LOGIN_INFO cookies upon successful login completion.
- [x] Implement session revocation and logout clearing stored cookies and WebView session state.

## Phase 8: InnerTube API Client & Subscriptions Synchronization
- [x] Implement SapisidHashGenerator computing SHA-1 dynamic token (SAPISIDHASH {timestamp}_{hash}).
- [x] Implement YouTubeInnerTubeClient invoking POST /youtubei/v1/browse with browseId: FEchannels.
- [x] Implement pagination parser for InnerTube continuation tokens across large subscription libraries.
- [x] Add insertAll(subscriptions: List<Subscription>) to SubscriptionDao.
- [x] Implement SyncSubscriptionsUseCase reconciling remote subscriptions with Room database.

## Phase 9: Google Takeout CSV Subscriptions Import & Export
- [x] Implement TakeoutCsvParser parsing Google Takeout subscriptions.csv format with robust quote handling.
- [x] Implement TakeoutCsvExporter generating RFC-compliant CSV from current Room subscriptions.
- [x] Integrate Storage Access Framework launcher for file picker and document creator intents.

## Phase 10: Accessible Account & Sync UI for TalkBack and Jieshuo
- [x] Implement AccountSyncScreen and modal sheet in Compose with clear TalkBack / Jieshuo landmarks.
- [x] Implement liveRegion polite status announcements for synchronization progress, error states, and import counts.
- [x] Add explicit top-bar dismiss action for WebView sign-in sheet preventing screen reader trapping.
- [x] Integrate Account Management button and sync status indicator directly into MainActivity Subscriptions tab.

## Phase 11: Verification, Integration Testing & Release Build
- [x] Unit test SapisidHashGenerator with known test vectors and timestamp bounds.
- [x] Unit test TakeoutCsvParser with malformed and escaped CSV lines.
- [ ] Execute ./gradlew test and verify Room DAO migrations.
- [ ] Run ./gradlew assembleDebug and verify APK compilation without regressions.

## Phase 12: Core Experience & Public Downloads Enhancements (REQ-011 through REQ-015)
- [x] Configure dedicated public storage directory (/sdcard/Download/SoundTube/) and Android MediaScanner integration (REQ-015).
- [x] Update storage, media, and notification permissions in AndroidManifest.xml and request runtime permissions in MainActivity (REQ-015).
- [x] Implement multi-quality video download (MP4) alongside high-quality audio download (M4A) via NewPipeExtractor and AudioDownloadManager (REQ-014).
- [x] Clean video list item cards: single tap plays immediately, long-click opens options modal sheet with Custom Accessibility Actions for TalkBack and Jieshuo (REQ-013).
- [x] Build rich HomeScreen landing tab with subscriptions feed, quick filter chips, and refresh (REQ-012).
- [x] Add permanent top-bar search action and channel details navigation (ChannelDetailsScreen) with dedicated uploads feed (REQ-011, REQ-012).
