#import <Foundation/Foundation.h>

/// This target exists solely for its `+load` auto-start hook (see `WailoAutoStart.m`) — the iOS analog
/// of Android's `WailoStartupProvider`. It has no public API; host apps import `WailoSDK`, never this.
/// The class is here only so the target has a public header for SwiftPM's module map.
@interface WailoAutoStartMarker : NSObject
@end
