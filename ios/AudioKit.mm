#import "AudioKit.h"
#import "AudioKitImpl.h"

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNAudioKitSpec/RNAudioKitSpec.h>

@interface AudioKit () <NativeAudioKitSpec>
@end
#endif

static inline void AudioKitRunAsync(
    RCTPromiseResolveBlock resolve,
    RCTPromiseRejectBlock reject,
    NSString *errorCode,
    void (^work)(void (^finish)(NSError * _Nullable error, id _Nullable result)))
{
  RCTPromiseResolveBlock resolveCopy = [resolve copy];
  RCTPromiseRejectBlock rejectCopy = [reject copy];

  work(^(NSError * _Nullable error, id _Nullable result) {
    if (error != nil) {
      if (rejectCopy != nil) {
        rejectCopy(errorCode, error.localizedDescription, error);
      }
      return;
    }
    if (resolveCopy != nil) {
      resolveCopy(result);
    }
  });
}

@implementation AudioKit {
  AudioKitImpl *_impl;
  BOOL _hasListeners;
}

RCT_EXPORT_MODULE(RNAudioKitSpec)

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

- (instancetype)init
{
  if (self = [super init]) {
    _impl = [AudioKitImpl new];
    __weak AudioKit *weakSelf = self;
    _impl.eventEmitter = ^(NSString *eventName, NSDictionary *payload) {
      __strong AudioKit *strongSelf = weakSelf;
      if (strongSelf != nil && strongSelf->_hasListeners) {
        [strongSelf sendEventWithName:eventName body:payload ?: @{}];
      }
    };
  }
  return self;
}

- (NSArray<NSString *> *)supportedEvents
{
  // `remotePrevious` and `remoteNext` are included so the JS NativeEventBridge
  // can call addListener for them on iOS without RN throwing
  // "<event> is not a supported event type for AudioKit". iOS does not emit
  // these events (the remote prev/next commands stay disabled), so listing them
  // only prevents the addListener crash; no iOS behavior changes.
  return @[
    @"play", @"pause", @"resume", @"stop", @"complete",
    @"buffer", @"progress", @"error", @"interruption", @"routeChange",
    @"remotePrevious", @"remoteNext"
  ];
}

- (void)startObserving
{
  _hasListeners = YES;
}

- (void)stopObserving
{
  _hasListeners = NO;
}

RCT_EXPORT_METHOD(configure:(NSDictionary *)config
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_CONFIGURE", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl configure:config completion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(play:(NSDictionary *)source
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_PLAY", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl play:source completion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(pause:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_PAUSE", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl pauseWithCompletion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(resume:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_RESUME", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl resumeWithCompletion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(stop:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_STOP", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl stopWithCompletion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(seekTo:(double)positionMs resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_SEEK", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl seekTo:positionMs completion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(setVolume:(double)volume resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_VOLUME", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl setVolume:volume completion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(setPlaybackSpeed:(double)speed resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_SPEED", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl setPlaybackSpeed:speed completion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(preload:(NSString *)url resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_PRELOAD", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl preload:url completion:^(NSDictionary *result, NSError * _Nullable error) {
      finish(error, result);
    }];
  });
}

RCT_EXPORT_METHOD(getDuration:(NSString *)url resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_DURATION", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl getDuration:url completion:^(double durationMs, NSError * _Nullable error) {
      finish(error, @(durationMs));
    }];
  });
}

RCT_EXPORT_METHOD(cache:(NSString *)url resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_CACHE", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl cache:url completion:^(NSDictionary *result, NSError * _Nullable error) {
      finish(error, result);
    }];
  });
}

RCT_EXPORT_METHOD(clearCache:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_CLEAR_CACHE", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl clearCacheWithCompletion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

RCT_EXPORT_METHOD(getCacheSize:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  resolve(@([_impl cacheSize]));
}

RCT_EXPORT_METHOD(getPlaybackState:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  resolve([_impl playbackState]);
}

RCT_EXPORT_METHOD(getWaveform:(NSString *)url
                  sampleCount:(double)sampleCount
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_WAVEFORM", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl getWaveform:url sampleCount:(NSInteger)sampleCount completion:^(NSArray<NSNumber *> *samples, NSError * _Nullable error) {
      finish(error, @{ @"samples": samples ?: @[] });
    }];
  });
}

RCT_EXPORT_METHOD(getResumePosition:(NSString *)sourceId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  resolve(@([_impl resumePositionForSource:sourceId]));
}

RCT_EXPORT_METHOD(setResumePosition:(NSString *)sourceId
                  positionMs:(double)positionMs
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  [_impl setResumePosition:positionMs forSource:sourceId];
  resolve(nil);
}

RCT_EXPORT_METHOD(setABRepeat:(double)startMs endMs:(double)endMs resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  [_impl setABRepeatStart:startMs end:endMs];
  resolve(nil);
}

RCT_EXPORT_METHOD(clearABRepeat:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  [_impl clearABRepeat];
  resolve(nil);
}

RCT_EXPORT_METHOD(detectSilenceGaps:(NSString *)url
                  thresholdDb:(double)thresholdDb
                  minSilenceMs:(double)minSilenceMs
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_SILENCE", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl detectSilenceGaps:url thresholdDb:thresholdDb minSilenceMs:minSilenceMs completion:^(NSArray<NSDictionary *> *gaps, NSError * _Nullable error) {
      finish(error, @{ @"gaps": gaps ?: @[] });
    }];
  });
}

RCT_EXPORT_METHOD(destroy:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
  AudioKitRunAsync(resolve, reject, @"E_DESTROY", ^(void (^finish)(NSError * _Nullable, id _Nullable)) {
    [_impl destroyWithCompletion:^(NSError * _Nullable error) {
      finish(error, nil);
    }];
  });
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeAudioKitSpecJSI>(params);
}
#endif

@end
