#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^AudioKitEventEmitter)(NSString *eventName, NSDictionary *payload);

@interface AudioKitImpl : NSObject

@property (nonatomic, copy, nullable) AudioKitEventEmitter eventEmitter;

- (void)configure:(NSDictionary *)config completion:(void (^)(NSError * _Nullable error))completion;
- (void)play:(NSDictionary *)source completion:(void (^)(NSError * _Nullable error))completion;
- (void)pauseWithCompletion:(void (^)(NSError * _Nullable error))completion;
- (void)resumeWithCompletion:(void (^)(NSError * _Nullable error))completion;
- (void)stopWithCompletion:(void (^)(NSError * _Nullable error))completion;
- (void)seekTo:(double)positionMs completion:(void (^)(NSError * _Nullable error))completion;
- (void)setVolume:(double)volume completion:(void (^)(NSError * _Nullable error))completion;
- (void)setPlaybackSpeed:(double)speed completion:(void (^)(NSError * _Nullable error))completion;
- (void)preload:(NSString *)url completion:(void (^)(NSDictionary *result, NSError * _Nullable error))completion;
- (void)getDuration:(NSString *)url completion:(void (^)(double durationMs, NSError * _Nullable error))completion;
- (void)cache:(NSString *)url completion:(void (^)(NSDictionary *result, NSError * _Nullable error))completion;
- (void)clearCacheWithCompletion:(void (^)(NSError * _Nullable error))completion;
- (NSInteger)cacheSize;
- (NSDictionary *)playbackState;
- (void)getWaveform:(NSString *)url sampleCount:(NSInteger)sampleCount completion:(void (^)(NSArray<NSNumber *> *samples, NSError * _Nullable error))completion;
- (double)resumePositionForSource:(NSString *)sourceId;
- (void)setResumePosition:(double)positionMs forSource:(NSString *)sourceId;
- (void)setABRepeatStart:(double)startMs end:(double)endMs;
- (void)clearABRepeat;
- (void)detectSilenceGaps:(NSString *)url thresholdDb:(double)thresholdDb minSilenceMs:(double)minSilenceMs completion:(void (^)(NSArray<NSDictionary *> *gaps, NSError * _Nullable error))completion;
- (void)destroyWithCompletion:(void (^)(NSError * _Nullable error))completion;

@end

NS_ASSUME_NONNULL_END
