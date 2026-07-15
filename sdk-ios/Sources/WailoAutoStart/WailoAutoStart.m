#import "WailoAutoStart.h"

@implementation WailoAutoStartMarker

/// Runs during image load, before `main` (and before the host's `AppDelegate`/`App`), so capture is
/// armed with zero host code — the timing match for Android's `WailoStartupProvider.onCreate`.
///
/// The Swift entry point is resolved by ObjC runtime name rather than imported, so this target needs no
/// compile-time view of WailoSDK's generated headers. Shipping in the same dynamic product guarantees
/// `WailoBootstrap` is registered by the time this fires.
+ (void)load {
    Class bootstrap = NSClassFromString(@"WailoBootstrap");
    SEL autoStart = NSSelectorFromString(@"autoStart");
    if (bootstrap != Nil && [bootstrap respondsToSelector:autoStart]) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        [bootstrap performSelector:autoStart];
#pragma clang diagnostic pop
    }
}

@end
