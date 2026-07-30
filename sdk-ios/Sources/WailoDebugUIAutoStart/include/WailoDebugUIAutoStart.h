#import <Foundation/Foundation.h>

/// This target exists solely for its `+load` hook (see `WailoDebugUIAutoStart.m`), which arms the debug
/// panel's gesture with zero host code. It has no public API; host apps link the `WailoDebugUI` product,
/// never this target directly. The class is here only so the target has a public header for SwiftPM's
/// module map.
@interface WailoDebugUIAutoStartMarker : NSObject
@end
