#import "WailoDebugUIAutoStart.h"

@implementation WailoDebugUIAutoStartMarker

/// Runs during image load, before `main`, so linking the product is the entire setup — the same
/// zero-host-code contract `WailoAutoStart` gives capture. Nothing here touches `UIApplication`, which
/// does not exist yet; `install` only subscribes to scene notifications.
///
/// The Swift entry point is resolved by ObjC runtime name rather than imported, so this target needs no
/// compile-time view of WailoDebugUI's generated headers. Shipping in the same dynamic product
/// guarantees `WailoDebugUIBootstrap` is registered by the time this fires.
+ (void)load {
    Class bootstrap = NSClassFromString(@"WailoDebugUIBootstrap");
    SEL install = NSSelectorFromString(@"install");
    if (bootstrap != Nil && [bootstrap respondsToSelector:install]) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        [bootstrap performSelector:install];
#pragma clang diagnostic pop
    }
}

@end
