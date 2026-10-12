import AVFoundation
import Foundation
import ImageIO
import MediaPlayer
import UIKit

private final class ArtworkDownload: NSObject, URLSessionDataDelegate {
  private let maximumBytes: Int
  private let completion: (Data?) -> Void
  private let lock = NSLock()
  private var data = Data()
  private var session: URLSession?
  private var task: URLSessionDataTask?
  private var finished = false

  init(maximumBytes: Int, completion: @escaping (Data?) -> Void) {
    self.maximumBytes = maximumBytes
    self.completion = completion
    super.init()
  }

  func start(url: URL) {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.requestCachePolicy = .returnCacheDataElseLoad
    let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    let task = session.dataTask(with: url)

    lock.lock()
    let shouldStart = !finished
    if shouldStart {
      self.session = session
      self.task = task
    }
    lock.unlock()

    if shouldStart {
      task.resume()
    } else {
      session.invalidateAndCancel()
    }
  }

  func cancel() {
    finish(useDownloadedData: false)
  }

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    let expectedLength = response.expectedContentLength
    guard expectedLength <= 0 || expectedLength <= Int64(maximumBytes) else {
      completionHandler(.cancel)
      finish(useDownloadedData: false)
      return
    }
    completionHandler(.allow)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
    lock.lock()
    let exceedsMaximum = !finished && chunk.count > maximumBytes - data.count
    if !finished && !exceedsMaximum {
      data.append(chunk)
    }
    lock.unlock()

    if exceedsMaximum {
      dataTask.cancel()
      finish(useDownloadedData: false)
    }
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didCompleteWithError error: Error?
  ) {
    finish(useDownloadedData: error == nil)
  }

  private func finish(useDownloadedData: Bool) {
    let result: Data?
    let sessionToInvalidate: URLSession?

    lock.lock()
    guard !finished else {
      lock.unlock()
      return
    }
    finished = true
    result = useDownloadedData ? data : nil
    task = nil
    data.removeAll(keepingCapacity: false)
    sessionToInvalidate = session
    session = nil
    lock.unlock()

    sessionToInvalidate?.invalidateAndCancel()
    completion(result)
  }
}

@objc(AudioKitImpl)
public final class AudioKitImpl: NSObject {

  @objc public var eventEmitter: ((String, [String: Any]) -> Void)?

  private var player: AVPlayer?
  private var playerItem: AVPlayerItem?
  private var timeObserver: Any?
  private var config: [String: Any] = [:]
  private var playbackSpeed: Float = 1.0
  private var wasPlayingBeforeInterruption = false
  private var abRepeatStart: Double = 0
  private var abRepeatEnd: Double = 0
  private var abRepeatEnabled = false
  private var currentSourceId: String?
  private var currentMetadata: [String: String] = [:]
  private var currentArtwork: MPMediaItemArtwork?
  private var artworkGeneration = 0
  private var artworkDownload: ArtworkDownload?
  private let artworkQueue = DispatchQueue(label: "com.audiokit.artwork", qos: .utility)
  private let artworkCache = NSCache<NSString, UIImage>()
  private var audioSessionActive = false
  private var remotePlayTarget: Any?
  private var remotePauseTarget: Any?
  private var remoteToggleTarget: Any?
  private var remoteNextTarget: Any?
  private var remotePrevTarget: Any?
  private var preloadAssets: [String: AVURLAsset] = [:]
  // Lightweight poll that detects when ANOTHER app/web (e.g. YouTube in Safari)
  // starts audio against our exclusive, non-mixing .playback session. iOS does
  // not post silenceSecondaryAudioHint to a non-mixing session and does not
  // reliably deliver interruption/.began to a backgrounded .playback app in that
  // case, so we poll AVAudioSession.isOtherAudioPlaying while playing and pause
  // ourselves. The timer runs ONLY while actually playing and is invalidated on
  // pause/stop/release/deinit to avoid battery drain and leaks.
  private var otherAudioPollTimer: Timer?
  // Guards against repeated pause+emit while other audio stays active; reset once
  // we are no longer playing (see stopOtherAudioPoll()).
  private var didPauseForOtherAudio = false
  // Timestamp of the last play/resume. Right after we start, iOS can briefly
  // report isOtherAudioPlaying == true (session still settling / another app's
  // session not yet yielded), which caused a FALSE pause on physical devices.
  // We ignore the poll during a short grace window after start.
  private var otherAudioGraceUntil: Date = .distantPast
  // Number of CONSECUTIVE poll ticks that saw isOtherAudioPlaying == true. We
  // only pause after it stays true for 2 ticks, so a single transient blip does
  // not stop our own playback.
  private var otherAudioTrueStreak = 0

  private let cacheDirectory: URL = {
    let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
    let dir = base.appendingPathComponent("RNAudioKitCache", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
  }()

  private let resumeDefaultsKey = "RNAudioKit_resume_positions"

  override init() {
    super.init()
    artworkCache.totalCostLimit = 8 * 1024 * 1024
    registerLifecycleObservers()
    registerAudioObservers()
  }

  deinit {
    artworkDownload?.cancel()
    removeRemoteCommandTargets()
    removeObservers()
    stopOtherAudioPoll()
  }

  // MARK: - Configure

  @objc public func configure(
    _ config: [String: Any],
    completion: @escaping (NSError?) -> Void
  ) {
    self.config = config
    setupRemoteCommands()
    completion(nil)
  }

  // MARK: - Playback

  @objc public func play(
    _ source: [String: Any],
    completion: @escaping (NSError?) -> Void
  ) {
    guard let uri = source["uri"] as? String, let url = resolveURL(from: uri) else {
      completion(makeError("Invalid audio source URI for playback"))
      return
    }

    do {
      try activateAudioSession()
    } catch {
      completion(error as NSError)
      return
    }

    invalidateArtwork()
    let generation = artworkGeneration
    currentSourceId = uri
    currentMetadata = [
      "title": source["title"] as? String ?? "",
      "artist": source["artist"] as? String ?? "",
      "album": source["album"] as? String ?? "",
      "artwork": source["artwork"] as? String ?? "",
    ]

    removeTimeObserver()
    let item = AVPlayerItem(url: url)
    playerItem = item

    if player == nil {
      player = AVPlayer(playerItem: item)
    } else {
      player?.replaceCurrentItem(with: item)
    }

    player?.volume = 1.0
    addTimeObserver()
    player?.play()
    player?.rate = playbackSpeed

    updateNowPlayingInfo()
    if let artwork = currentMetadata["artwork"], !artwork.isEmpty {
      loadArtwork(artwork, generation: generation, sourceId: uri)
    }
    emit("play", playbackStateDict())
    startOtherAudioPoll()
    completion(nil)
  }

  @objc public func pauseWithCompletion(_ completion: @escaping (NSError?) -> Void) {
    player?.pause()
    stopOtherAudioPoll()
    emit("pause", playbackStateDict())
    completion(nil)
  }

  @objc public func resumeWithCompletion(_ completion: @escaping (NSError?) -> Void) {
    guard player?.currentItem != nil else {
      completion(nil)
      return
    }
    do {
      try activateAudioSession()
    } catch {
      completion(error as NSError)
      return
    }

    resumeAfterActivation(completion)
  }

  private func resumeAfterActivation(_ completion: @escaping (NSError?) -> Void) {
    let currentSeconds = CMTimeGetSeconds(player?.currentTime() ?? .zero)
    let durationSeconds = CMTimeGetSeconds(player?.currentItem?.duration ?? .zero)
    let atEnd = durationSeconds > 0 && currentSeconds >= durationSeconds - 0.05

    if atEnd {
      player?.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
        guard let self else {
          completion(nil)
          return
        }
        self.player?.play()
        self.player?.rate = self.playbackSpeed
        self.emit("resume", self.playbackStateDict())
        self.startOtherAudioPoll()
        completion(nil)
      }
      return
    }

    player?.play()
    player?.rate = playbackSpeed
    emit("resume", playbackStateDict())
    startOtherAudioPoll()
    completion(nil)
  }

  @objc public func stopWithCompletion(_ completion: @escaping (NSError?) -> Void) {
    invalidateArtwork()
    currentSourceId = nil
    currentMetadata.removeAll()
    clearNowPlayingInfo()
    removeTimeObserver()
    stopOtherAudioPoll()
    player?.pause()
    player?.seek(to: .zero)
    if configBool("releaseOnStop", default: true) {
      player?.replaceCurrentItem(with: nil)
      playerItem = nil
    }
    if configBool("autoDestroy", default: false) {
      player = nil
    }
    emit("stop", playbackStateDict())
    deactivateAudioSession(completion: completion)
  }

  @objc public func seekTo(_ positionMs: Double, completion: @escaping (NSError?) -> Void) {
    let time = CMTime(seconds: positionMs / 1000.0, preferredTimescale: 1000)
    player?.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { _ in
      completion(nil)
    }
  }

  @objc public func setVolume(_ volume: Double, completion: @escaping (NSError?) -> Void) {
    player?.volume = Float(volume)
    completion(nil)
  }

  @objc public func setPlaybackSpeed(_ speed: Double, completion: @escaping (NSError?) -> Void) {
    playbackSpeed = Float(speed)
    if player?.rate != 0 {
      player?.rate = playbackSpeed
    }
    completion(nil)
  }

  // MARK: - Preload / Duration

  @objc public func preload(
    _ urlString: String,
    completion: @escaping ([String: Any], NSError?) -> Void
  ) {
    loadAssetDuration(urlString) { durationMs, error in
      if let error {
        completion([:], error)
      } else {
        completion(["durationMs": durationMs], nil)
      }
    }
  }

  @objc public func getDuration(
    _ urlString: String,
    completion: @escaping (Double, NSError?) -> Void
  ) {
    loadAssetDuration(urlString, completion: completion)
  }

  private func loadAssetDuration(
    _ urlString: String,
    completion: @escaping (Double, NSError?) -> Void
  ) {
    guard let url = resolveURL(from: urlString) else {
      completion(0, makeError("Invalid audio source URI for duration"))
      return
    }
    if let cached = preloadAssets[urlString] {
      let ms = CMTimeGetSeconds(cached.duration) * 1000.0
      completion(ms.isFinite ? ms : 0, nil)
      return
    }

    let asset = AVURLAsset(url: url)
    preloadAssets[urlString] = asset
    asset.loadValuesAsynchronously(forKeys: ["duration", "playable"]) {
      var error: NSError?
      let status = asset.statusOfValue(forKey: "duration", error: &error)
      DispatchQueue.main.async {
        if status == .loaded {
          let ms = CMTimeGetSeconds(asset.duration) * 1000.0
          completion(ms.isFinite ? ms : 0, nil)
        } else {
          completion(0, error ?? self.makeError("Failed to load duration"))
        }
      }
    }
  }

  // MARK: - Cache

  @objc public func cache(
    _ urlString: String,
    completion: @escaping ([String: Any], NSError?) -> Void
  ) {
    guard urlString.hasPrefix("http") else {
      completion(["path": urlString, "cached": true], nil)
      return
    }

    let dest = cacheFileURL(for: urlString)

    if FileManager.default.fileExists(atPath: dest.path) {
      completion(["path": dest.path, "cached": true], nil)
      return
    }

    enforceCacheLimit()

    guard let url = URL(string: urlString) else {
      completion(["path": urlString, "cached": false], makeError("Invalid URL"))
      return
    }

    URLSession.shared.downloadTask(with: url) { tempURL, _, error in
      DispatchQueue.main.async {
        if let error {
          completion(["path": urlString, "cached": false], error as NSError)
          return
        }
        guard let tempURL else {
          completion(["path": urlString, "cached": false], self.makeError("Download failed"))
          return
        }
        do {
          try FileManager.default.moveItem(at: tempURL, to: dest)
          completion(["path": dest.path, "cached": true], nil)
        } catch {
          completion(["path": urlString, "cached": false], error as NSError)
        }
      }
    }.resume()
  }

  @objc public func clearCacheWithCompletion(_ completion: @escaping (NSError?) -> Void) {
    do {
      let files = try FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
      for file in files {
        try FileManager.default.removeItem(at: file)
      }
      completion(nil)
    } catch {
      completion(error as NSError)
    }
  }

  @objc public var cacheSize: Int {
    guard let files = try? FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: [.fileSizeKey]) else {
      return 0
    }
    return files.reduce(0) { sum, url in
      let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      return sum + size
    }
  }

  private func enforceCacheLimit() {
    let limit = configInt("cacheSizeLimit", default: 100 * 1024 * 1024)
    if limit <= 0 || cacheSize <= limit { return }

    guard let files = try? FileManager.default.contentsOfDirectory(
      at: cacheDirectory,
      includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
    ) else { return }

    let sorted = files.sorted {
      let d0 = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      let d1 = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      return d0 < d1
    }

    var total = cacheSize
    for file in sorted where total > limit {
      let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      try? FileManager.default.removeItem(at: file)
      total -= size
    }
  }

  // MARK: - Waveform / Silence

  @objc public func getWaveform(
    _ urlString: String,
    sampleCount: Int,
    completion: @escaping ([NSNumber], NSError?) -> Void
  ) {
    guard let url = resolveURL(from: urlString) else {
      completion([], makeError("Invalid audio source URI for waveform"))
      return
    }
    let asset = AVURLAsset(url: url)

    asset.loadValuesAsynchronously(forKeys: ["tracks", "playable"]) {
      DispatchQueue.global(qos: .userInitiated).async {
        var loadError: NSError?
        let tracksStatus = asset.statusOfValue(forKey: "tracks", error: &loadError)
        guard tracksStatus == .loaded else {
          DispatchQueue.main.async {
            completion([], loadError ?? self.makeError("Failed to load audio tracks"))
          }
          return
        }

        do {
          let samples = try self.extractWaveformSamples(asset: asset, sampleCount: max(10, sampleCount))
          DispatchQueue.main.async { completion(samples.map { NSNumber(value: $0) }, nil) }
        } catch {
          DispatchQueue.main.async { completion([], error as NSError) }
        }
      }
    }
  }

  @objc public func detectSilenceGaps(
    _ urlString: String,
    thresholdDb: Double,
    minSilenceMs: Double,
    completion: @escaping ([[String: Any]], NSError?) -> Void
  ) {
    guard let url = resolveURL(from: urlString) else {
      completion([], makeError("Invalid audio source URI for silence detection"))
      return
    }
    let asset = AVURLAsset(url: url)

    asset.loadValuesAsynchronously(forKeys: ["tracks"]) {
      DispatchQueue.global(qos: .userInitiated).async {
        do {
          let samples = try self.extractWaveformSamples(asset: asset, sampleCount: 500)
          let gaps = self.findSilenceGaps(
            samples: samples,
            durationMs: CMTimeGetSeconds(asset.duration) * 1000.0,
            threshold: Float(thresholdDb),
            minSilenceMs: minSilenceMs
          )
          DispatchQueue.main.async { completion(gaps, nil) }
        } catch {
          DispatchQueue.main.async { completion([], error as NSError) }
        }
      }
    }
  }

  private func extractWaveformSamples(asset: AVURLAsset, sampleCount: Int) throws -> [Float] {
    guard let track = asset.tracks(withMediaType: .audio).first else {
      throw makeError("No audio track")
    }

    let reader = try AVAssetReader(asset: asset)
    let outputSettings: [String: Any] = [
      AVFormatIDKey: kAudioFormatLinearPCM,
      AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsFloatKey: false,
      AVLinearPCMBitDepthKey: 16,
      AVLinearPCMIsNonInterleaved: false,
    ]
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: outputSettings)
    reader.add(output)
    reader.startReading()

    var allSamples: [Int16] = []
    while let buffer = output.copyNextSampleBuffer() {
      guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
      let length = CMBlockBufferGetDataLength(block)
      var data = Data(count: length)
      data.withUnsafeMutableBytes { ptr in
        if let base = ptr.baseAddress {
          CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: base)
        }
      }
      let samples = data.withUnsafeBytes { ptr in
        Array(ptr.bindMemory(to: Int16.self))
      }
      allSamples.append(contentsOf: samples)
    }

    if allSamples.isEmpty { return [] }

    let chunkSize = max(1, allSamples.count / sampleCount)
    var result: [Float] = []
    for i in 0..<sampleCount {
      let start = i * chunkSize
      let end = min(start + chunkSize, allSamples.count)
      if start >= end { result.append(0); continue }
      let chunk = allSamples[start..<end]
      let peak = chunk.map { abs(Float($0)) / Float(Int16.max) }.max() ?? 0
      result.append(min(1, peak))
    }
    return result
  }

  private func findSilenceGaps(
    samples: [Float],
    durationMs: Double,
    threshold: Float,
    minSilenceMs: Double
  ) -> [[String: Any]] {
    let thresholdLinear = pow(10, threshold / 20)
    let msPerSample = durationMs / Double(max(1, samples.count))
    var gaps: [[String: Any]] = []
    var gapStart: Double? = nil

    for (index, amp) in samples.enumerated() {
      let ms = Double(index) * msPerSample
      if amp < thresholdLinear {
        if gapStart == nil { gapStart = ms }
      } else if let start = gapStart {
        if ms - start >= minSilenceMs {
          gaps.append(["startMs": start, "endMs": ms])
        }
        gapStart = nil
      }
    }
    return gaps
  }

  // MARK: - Resume position

  @objc public func resumePositionForSource(_ sourceId: String) -> Double {
    let dict = UserDefaults.standard.dictionary(forKey: resumeDefaultsKey) as? [String: Double]
    return dict?[sourceId] ?? 0
  }

  @objc public func setResumePosition(_ positionMs: Double, forSource sourceId: String) {
    var dict = UserDefaults.standard.dictionary(forKey: resumeDefaultsKey) as? [String: Double] ?? [:]
    dict[sourceId] = positionMs
    UserDefaults.standard.set(dict, forKey: resumeDefaultsKey)
  }

  // MARK: - A-B Repeat

  @objc public func setABRepeatStart(_ startMs: Double, end endMs: Double) {
    abRepeatStart = startMs
    abRepeatEnd = endMs
    abRepeatEnabled = true
  }

  @objc public func clearABRepeat() {
    abRepeatEnabled = false
  }

  // MARK: - State

  @objc public var playbackState: [String: Any] {
    playbackStateDict()
  }

  private func playbackStateDict() -> [String: Any] {
    let currentSeconds = CMTimeGetSeconds(player?.currentTime() ?? .zero)
    let durationSeconds = CMTimeGetSeconds(player?.currentItem?.duration ?? .zero)
    let rate = player?.rate ?? 0
    let isPlaying = rate > 0
    let itemStatus = player?.currentItem?.status
    let buffering = player?.currentItem?.isPlaybackLikelyToKeepUp == false && itemStatus == .readyToPlay
    let loading = itemStatus == .unknown
    let completed = player?.currentItem?.status == .readyToPlay &&
      durationSeconds > 0 && currentSeconds >= durationSeconds - 0.05

    var status = "idle"
    if completed { status = "completed" }
    else if loading { status = "loading" }
    else if buffering { status = "buffering" }
    else if isPlaying { status = "playing" }
    else if player?.currentItem != nil { status = "paused" }

    return [
      "status": status,
      "playing": isPlaying,
      "paused": !isPlaying && player?.currentItem != nil,
      "buffering": buffering,
      "loading": loading,
      "completed": completed,
      "duration": durationSeconds.isFinite ? durationSeconds * 1000.0 : 0,
      "position": currentSeconds.isFinite ? currentSeconds * 1000.0 : 0,
      "speed": Double(playbackSpeed),
      "volume": Double(player?.volume ?? 1),
    ]
  }

  @objc public func destroyWithCompletion(_ completion: @escaping (NSError?) -> Void) {
    invalidateArtwork()
    currentSourceId = nil
    currentMetadata.removeAll()
    clearNowPlayingInfo()
    removeRemoteCommandTargets()
    removeTimeObserver()
    stopOtherAudioPoll()
    player?.pause()
    player = nil
    playerItem = nil
    preloadAssets.removeAll()
    deactivateAudioSession(completion: completion)
  }

  // MARK: - Observers

  private func addTimeObserver() {
    guard let player else { return }
    let interval = CMTime(seconds: 0.25, preferredTimescale: 1000)
    timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
      guard let self else { return }
      let positionMs = CMTimeGetSeconds(time) * 1000.0
      let durationMs = CMTimeGetSeconds(self.player?.currentItem?.duration ?? .zero) * 1000.0

      if self.abRepeatEnabled && positionMs >= self.abRepeatEnd {
        self.seekTo(self.abRepeatStart) { _ in }
      }

      if durationMs > 0 && positionMs >= durationMs - 50 {
        self.emit("complete", self.playbackStateDict())
      }

      self.emit("progress", [
        "position": positionMs,
        "duration": durationMs.isFinite ? durationMs : 0,
      ])
      self.updateNowPlayingInfo()
    }
  }

  private func removeTimeObserver() {
    if let observer = timeObserver, let player {
      player.removeTimeObserver(observer)
    }
    timeObserver = nil
  }

  private func registerLifecycleObservers() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appDidEnterBackground),
      name: UIApplication.didEnterBackgroundNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appWillEnterForeground),
      name: UIApplication.willEnterForegroundNotification,
      object: nil
    )
  }

  private func registerAudioObservers() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleInterruption),
      name: AVAudioSession.interruptionNotification,
      object: AVAudioSession.sharedInstance()
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(screenDidLock),
      name: UIApplication.protectedDataWillBecomeUnavailableNotification,
      object: nil
    )
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleRouteChange),
      name: AVAudioSession.routeChangeNotification,
      object: AVAudioSession.sharedInstance()
    )
    // Secondary-audio hint: iOS posts this when ANOTHER app/web (e.g. YouTube
    // in Safari) actually starts audio against our non-mixing .playback
    // session. Unlike interruptionNotification, this hint IS reliably delivered
    // for competing app/web audio, closing the gap where our MP3 kept mixing.
    // It is NOT posted on plain backgrounding or screen lock, so background
    // playback with no competing audio stays working untouched.
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleSilenceSecondaryAudioHint),
      name: AVAudioSession.silenceSecondaryAudioHintNotification,
      object: AVAudioSession.sharedInstance()
    )
  }

  private func removeObservers() {
    NotificationCenter.default.removeObserver(self)
    removeTimeObserver()
  }

  @objc private func appDidEnterBackground() {
    if configBool("stopOnBackground", default: false) {
      stopWithCompletion { _ in }
      return
    }
    if !configBool("backgroundPlayback", default: false) {
      pauseWithCompletion { _ in }
      return
    }
    // backgroundPlayback is on: normal backgrounding / screen lock must KEEP
    // PLAYING. Only pause when another app actually owns audio right now (e.g.
    // the user switched to YouTube in Safari, which backgrounds us). The guarded
    // check never pauses merely because we backgrounded with no competing audio.
    pauseIfOtherAudioPlaying()
  }

  @objc private func appWillEnterForeground() {
    // no-op — app controls resume
  }

  @objc private func handleInterruption(_ notification: Notification) {
    guard let info = notification.userInfo,
          let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
          let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

    if type == .began {
      wasPlayingBeforeInterruption = (player?.rate ?? 0) > 0
      if configBool("pauseOnIncomingCall", default: true) {
        pauseWithCompletion { _ in }
      }
      emit("interruption", ["type": "began", "reason": "interruption"])
    } else if type == .ended {
      emit("interruption", ["type": "ended"])
      let shouldResume = configBool("resumeAfterInterruption", default: false) ||
        configBool("resumeAfterCall", default: false)
      if shouldResume && wasPlayingBeforeInterruption {
        resumeWithCompletion { _ in }
      }
    }
  }

  @objc private func handleSilenceSecondaryAudioHint(_ notification: Notification) {
    guard let info = notification.userInfo,
          let typeValue = info[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
          let type = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: typeValue) else { return }

    if type == .begin {
      // Another app/web started audio (the exact YouTube-in-Safari case that
      // interruptionNotification does NOT reliably report for our .playback
      // session). Pause the SAME way handleInterruption(.began) does and emit
      // the IDENTICAL began payload so the JS layer (which already self-pauses
      // on interruption/began) stays in sync with zero JS change.
      wasPlayingBeforeInterruption = (player?.rate ?? 0) > 0
      pauseWithCompletion { _ in }
      emit("interruption", ["type": "began", "reason": "interruption"])
    } else if type == .end {
      // The other audio ended. Do NOT auto-resume — resumeAfterInterruption /
      // resumeAfterCall are false and AudioKit.setAutoResume(false); the user
      // resumes manually. Emit ended only for symmetry with
      // handleInterruption(.ended).
      emit("interruption", ["type": "ended"])
    }
  }

  @objc private func screenDidLock() {
    if configBool("stopOnScreenLock", default: false) {
      stopWithCompletion { _ in }
    }
  }

  @objc private func handleRouteChange(_ notification: Notification) {
    guard let info = notification.userInfo,
          let reasonValue = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
          let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

    let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue }
    emit("routeChange", ["outputs": outputs])

    // A route change can accompany another app grabbing audio; prompt-check so
    // we pause right away rather than waiting for the next poll tick. Guarded so
    // it only pauses when another app is actually playing.
    pauseIfOtherAudioPlaying()

    if reason == .oldDeviceUnavailable {
      let headphones = ["Headphones", "HeadsetMic"]
      let bluetooth = ["BluetoothA2DP", "BluetoothHFP", "BluetoothLE"]
      let oldRoute = info[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription
      let oldOutputs = oldRoute?.outputs.map { $0.portType.rawValue } ?? []

      if configBool("pauseOnHeadphoneDisconnect", default: true) &&
        oldOutputs.contains(where: { headphones.contains($0) }) {
        pauseWithCompletion { _ in }
      }
      if configBool("pauseOnBluetoothDisconnect", default: true) &&
        oldOutputs.contains(where: { bluetooth.contains($0) }) {
        pauseWithCompletion { _ in }
      }
    }
  }

  // MARK: - Other-audio detection (iOS exclusive .playback fix)

  // Start the repeating poll ONLY after a successful play/resume (while rate>0).
  // Scheduling/invalidation are guarded onto the main thread so the Timer lives
  // on the main runloop. [weak self] prevents a retain cycle via the Timer.
  private func startOtherAudioPoll() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      // Reset the de-dupe guard for this fresh playback session so a new "other
      // audio" event can pause us again.
      self.didPauseForOtherAudio = false
      self.otherAudioTrueStreak = 0
      // Ignore the poll for a short window after start so a settling session's
      // transient isOtherAudioPlaying == true does not falsely pause us.
      self.otherAudioGraceUntil = Date().addingTimeInterval(1.5)
      self.otherAudioPollTimer?.invalidate()
      let timer = Timer(timeInterval: 0.75, repeats: true) { [weak self] _ in
        self?.pauseIfOtherAudioPlaying()
      }
      // .common so the poll keeps firing during scroll / UI tracking.
      RunLoop.main.add(timer, forMode: .common)
      self.otherAudioPollTimer = timer
    }
  }

  // Invalidate the poll on pause/stop/release/deinit and reset the de-dupe guard
  // so no battery drain or retain cycle persists while we are not playing.
  private func stopOtherAudioPoll() {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.otherAudioPollTimer?.invalidate()
      self.otherAudioPollTimer = nil
      self.didPauseForOtherAudio = false
    }
  }

  // Pause + emit exactly once if another app owns audio while we are playing.
  // isOtherAudioPlaying is a Bool point-in-time poll; combined with the rate>0
  // check this never fires on plain backgrounding/screen lock with no competing
  // audio, so normal background playback keeps working untouched.
  private func pauseIfOtherAudioPlaying() {
    let isPlaying = (player?.rate ?? 0) > 0
    guard isPlaying else {
      // No longer playing: reset the guards so the next competing-audio event
      // after a resume can pause us again.
      didPauseForOtherAudio = false
      otherAudioTrueStreak = 0
      return
    }
    // Grace window right after start: a settling session can momentarily report
    // other audio even when nothing is really playing. Ignore it, so our own
    // playback is never falsely paused immediately after Play.
    if Date() < otherAudioGraceUntil {
      otherAudioTrueStreak = 0
      return
    }
    guard AVAudioSession.sharedInstance().isOtherAudioPlaying else {
      // Not (or no longer) competing: reset the streak so a future real event
      // needs its own fresh confirmation.
      otherAudioTrueStreak = 0
      return
    }
    // Require the signal to persist for 2 consecutive ticks before pausing, so a
    // single transient isOtherAudioPlaying blip cannot stop our playback.
    otherAudioTrueStreak += 1
    guard otherAudioTrueStreak >= 2 else { return }
    // Already paused for this active other-audio session; avoid repeated emits
    // while the other audio keeps playing.
    guard !didPauseForOtherAudio else { return }
    didPauseForOtherAudio = true

    // Mirror handleInterruption(.began): record prior playing state, pause via
    // the real pause, and emit the IDENTICAL began payload the JS layer already
    // self-pauses on. Do NOT auto-resume — the user resumes manually.
    wasPlayingBeforeInterruption = true
    pauseWithCompletion { _ in }
    emit("interruption", ["type": "began", "reason": "interruption"])
  }

  // MARK: - Audio session & Now Playing

  private func activateAudioSession() throws {
    let session = AVAudioSession.sharedInstance()
    let background = configBool("backgroundPlayback", default: false)
    let focus = configString("audioFocus", default: "exclusive")
    let highQuality = configBool("highQualityPlayback", default: true)

    let category: AVAudioSession.Category = background ? .playback : .soloAmbient
    var options: AVAudioSession.CategoryOptions = []
    if focus == "duck" { options.insert(.duckOthers) }
    if focus == "mix" { options.insert(.mixWithOthers) }
    if highQuality {
      options.insert(.allowBluetoothA2DP)
      options.insert(.allowAirPlay)
    }

    // setCategory can fail on some physical devices for a given option combo
    // (OSStatus -50). Fall back to the category with no extra options so
    // playback still activates instead of failing the whole play() with -50.
    do {
      try session.setCategory(category, mode: .default, options: options)
    } catch {
      NSLog("[MP3][native] setCategory(options:%lu) failed: %@ — retrying with no options", options.rawValue, String(describing: error))
      try session.setCategory(category, mode: .default, options: [])
    }

    // A 5 ms preferred IO buffer is rejected on real hardware that cannot honor
    // it, throwing OSStatus -50. It is only a hint, so never let it fail
    // activation — log and continue with the system default buffer.
    if highQuality {
      do {
        try session.setPreferredIOBufferDuration(0.005)
      } catch {
        NSLog("[MP3][native] setPreferredIOBufferDuration(0.005) failed, using default: %@", String(describing: error))
      }
    }

    try session.setActive(true)
    audioSessionActive = true
  }

  private func deactivateAudioSession(completion: @escaping (NSError?) -> Void) {
    guard audioSessionActive else {
      completion(nil)
      return
    }
    do {
      try AVAudioSession.sharedInstance().setActive(
        false,
        options: .notifyOthersOnDeactivation
      )
      audioSessionActive = false
      completion(nil)
    } catch {
      completion(error as NSError)
    }
  }

  private func setupRemoteCommands() {
    removeRemoteCommandTargets()
    let center = MPRemoteCommandCenter.shared()
    let enabled = configBool("enableHeadphoneControls", default: false) ||
      configBool("enableLockScreenControls", default: false)

    center.playCommand.isEnabled = enabled
    center.pauseCommand.isEnabled = enabled
    center.togglePlayPauseCommand.isEnabled = enabled
    center.changePlaybackPositionCommand.isEnabled = false
    center.skipForwardCommand.isEnabled = false
    center.skipBackwardCommand.isEnabled = false
    center.nextTrackCommand.isEnabled = enabled
    center.previousTrackCommand.isEnabled = enabled
    guard enabled else { return }

    remotePlayTarget = center.playCommand.addTarget { [weak self] _ in
      guard let self else { return .commandFailed }
      return self.acceptRemoteResume()
    }
    remotePauseTarget = center.pauseCommand.addTarget { [weak self] _ in
      guard let self, self.player?.currentItem != nil else { return .noSuchContent }
      DispatchQueue.main.async { [weak self] in
        self?.pauseWithCompletion { _ in }
      }
      return .success
    }
    remoteToggleTarget = center.togglePlayPauseCommand.addTarget { [weak self] _ in
      guard let self, self.player?.currentItem != nil else { return .noSuchContent }
      if (self.player?.rate ?? 0) > 0 {
        DispatchQueue.main.async { [weak self] in
          self?.pauseWithCompletion { _ in }
        }
        return .success
      }
      return self.acceptRemoteResume()
    }
    remoteNextTarget = center.nextTrackCommand.addTarget { [weak self] _ in
      guard let self else { return .commandFailed }
      self.emit("remoteNext", [:])
      return .success
    }
    remotePrevTarget = center.previousTrackCommand.addTarget { [weak self] _ in
      guard let self else { return .commandFailed }
      self.emit("remotePrevious", [:])
      return .success
    }
  }

  private func acceptRemoteResume() -> MPRemoteCommandHandlerStatus {
    guard player?.currentItem != nil else { return .noSuchContent }
    do {
      try activateAudioSession()
    } catch {
      return .commandFailed
    }
    DispatchQueue.main.async { [weak self] in
      self?.resumeAfterActivation { _ in }
    }
    return .success
  }

  private func removeRemoteCommandTargets() {
    let center = MPRemoteCommandCenter.shared()
    if let target = remotePlayTarget { center.playCommand.removeTarget(target) }
    if let target = remotePauseTarget { center.pauseCommand.removeTarget(target) }
    if let target = remoteToggleTarget { center.togglePlayPauseCommand.removeTarget(target) }
    if let target = remoteNextTarget { center.nextTrackCommand.removeTarget(target) }
    if let target = remotePrevTarget { center.previousTrackCommand.removeTarget(target) }
    center.playCommand.isEnabled = false
    center.pauseCommand.isEnabled = false
    center.togglePlayPauseCommand.isEnabled = false
    center.nextTrackCommand.isEnabled = false
    center.previousTrackCommand.isEnabled = false
    remotePlayTarget = nil
    remotePauseTarget = nil
    remoteToggleTarget = nil
    remoteNextTarget = nil
    remotePrevTarget = nil
  }

  private func updateNowPlayingInfo() {
    guard configBool("showNotification", default: false) ||
          configBool("enableLockScreenControls", default: false) else {
      return
    }

    let state = playbackStateDict()
    var info: [String: Any] = [
      MPMediaItemPropertyPlaybackDuration: state["duration"] ?? 0,
      MPNowPlayingInfoPropertyElapsedPlaybackTime: (state["position"] as? Double ?? 0) / 1000.0,
      MPNowPlayingInfoPropertyPlaybackRate: (state["playing"] as? Bool ?? false) ? playbackSpeed : 0,
    ]

    if let title = currentMetadata["title"], !title.isEmpty {
      info[MPMediaItemPropertyTitle] = title
    }
    if let artist = currentMetadata["artist"], !artist.isEmpty {
      info[MPMediaItemPropertyArtist] = artist
    }
    if let album = currentMetadata["album"], !album.isEmpty {
      info[MPMediaItemPropertyAlbumTitle] = album
    }
    if let artwork = currentArtwork {
      info[MPMediaItemPropertyArtwork] = artwork
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
  }

  private func clearNowPlayingInfo() {
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
  }

  // MARK: - Artwork

  private func invalidateArtwork() {
    artworkGeneration += 1
    artworkDownload?.cancel()
    artworkDownload = nil
    currentArtwork = nil
  }

  private func loadArtwork(_ value: String, generation: Int, sourceId: String) {
    if let cached = artworkCache.object(forKey: value as NSString) {
      DispatchQueue.main.async { [weak self] in
        self?.applyArtwork(cached, generation: generation, sourceId: sourceId)
      }
      return
    }

    if value.hasPrefix("http://") || value.hasPrefix("https://") {
      guard let url = URL(string: value),
            let scheme = url.scheme?.lowercased(),
            ["http", "https"].contains(scheme),
            url.host != nil else { return }
      let download = ArtworkDownload(maximumBytes: 10 * 1024 * 1024) { [weak self] data in
        guard let self else { return }
        defer {
          DispatchQueue.main.async { [weak self] in
            guard let self,
                  generation == self.artworkGeneration,
                  sourceId == self.currentSourceId else { return }
            self.artworkDownload = nil
          }
        }
        guard let data, let image = self.decodeArtwork(data) else { return }
        self.cacheAndApplyArtwork(image, key: value, generation: generation, sourceId: sourceId)
      }
      artworkDownload = download
      download.start(url: url)
      return
    }

    artworkQueue.async { [weak self] in
      guard let self,
            let data = self.localArtworkData(from: value),
            let image = self.decodeArtwork(data) else { return }
      self.cacheAndApplyArtwork(image, key: value, generation: generation, sourceId: sourceId)
    }
  }

  private func cacheAndApplyArtwork(
    _ image: UIImage,
    key: String,
    generation: Int,
    sourceId: String
  ) {
    let cost = Int(image.size.width * image.scale) * Int(image.size.height * image.scale) * 4
    artworkCache.setObject(image, forKey: key as NSString, cost: cost)
    DispatchQueue.main.async { [weak self] in
      self?.applyArtwork(image, generation: generation, sourceId: sourceId)
    }
  }

  private func applyArtwork(_ image: UIImage, generation: Int, sourceId: String) {
    guard generation == artworkGeneration, sourceId == currentSourceId else { return }
    currentArtwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    updateNowPlayingInfo()
  }

  private func localArtworkData(from value: String) -> Data? {
    if value.hasPrefix("data:image/") {
      guard let comma = value.firstIndex(of: ","),
            value[..<comma].contains(";base64") else { return nil }
      let encoded = String(value[value.index(after: comma)...])
      guard encoded.count <= (10 * 1024 * 1024 * 4 / 3) + 16,
            let data = Data(base64Encoded: encoded, options: .ignoreUnknownCharacters),
            data.count <= 10 * 1024 * 1024 else { return nil }
      return data
    }

    let url: URL?
    if value.hasPrefix("file://") {
      guard let candidate = URL(string: value), candidate.isFileURL else { return nil }
      url = candidate
    } else if value.hasPrefix("/") {
      url = URL(fileURLWithPath: value)
    } else {
      let resource = value.hasPrefix("asset://") ? String(value.dropFirst("asset://".count)) : value
      let extensionName = (resource as NSString).pathExtension
      let name = (resource as NSString).deletingPathExtension
      url = Bundle.main.url(
        forResource: name,
        withExtension: extensionName.isEmpty ? nil : extensionName
      )
    }
    guard let url else { return nil }
    return readArtworkData(at: url)
  }

  private func readArtworkData(at url: URL) -> Data? {
    guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
          size <= 10 * 1024 * 1024,
          let data = try? Data(contentsOf: url, options: .mappedIfSafe),
          data.count <= 10 * 1024 * 1024 else { return nil }
    return data
  }

  private func decodeArtwork(_ data: Data) -> UIImage? {
    guard !data.isEmpty, data.count <= 10 * 1024 * 1024,
          let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceThumbnailMaxPixelSize: 1024,
      kCGImageSourceShouldCacheImmediately: true,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
      return nil
    }
    return UIImage(cgImage: image)
  }

  // MARK: - Helpers

  private func resolveURL(from uri: String) -> URL? {
    let value = uri.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !value.isEmpty else { return nil }
    if value.hasPrefix("http://") || value.hasPrefix("https://") {
      guard let url = URL(string: value),
            let scheme = url.scheme?.lowercased(),
            ["http", "https"].contains(scheme),
            url.host != nil else { return nil }
      return url
    }
    if value.hasPrefix("file://") {
      guard let url = URL(string: value), url.isFileURL, !url.path.isEmpty else { return nil }
      return url
    }
    if value.hasPrefix("data:") {
      guard value.contains(","), let url = URL(string: value) else { return nil }
      return url
    }
    if value.hasPrefix("/") {
      return URL(fileURLWithPath: value)
    }
    if value.contains("://") {
      return nil
    }
    return URL(fileURLWithPath: value)
  }

  private func cacheFileURL(for urlString: String) -> URL {
    let hash = urlString.data(using: .utf8)?.base64EncodedString()
      .replacingOccurrences(of: "/", with: "_") ?? UUID().uuidString
    let ext = URL(string: urlString)?.pathExtension.lowercased() ?? ""
    let safeExt = ext.isEmpty ? "audio" : ext
    return cacheDirectory.appendingPathComponent("\(hash).\(safeExt)")
  }

  private func configBool(_ key: String, default defaultValue: Bool) -> Bool {
    if let value = config[key] as? Bool { return value }
    if let value = config[key] as? NSNumber { return value.boolValue }
    return defaultValue
  }

  private func configInt(_ key: String, default defaultValue: Int) -> Int {
    if let value = config[key] as? Int { return value }
    if let value = config[key] as? NSNumber { return value.intValue }
    return defaultValue
  }

  private func configString(_ key: String, default defaultValue: String) -> String {
    config[key] as? String ?? defaultValue
  }

  private func emit(_ name: String, _ payload: [String: Any]) {
    eventEmitter?(name, payload)
  }

  private func makeError(_ message: String) -> NSError {
    NSError(domain: "RNAudioKit", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}
