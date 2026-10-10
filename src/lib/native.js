// Native shell detection: true only inside the Android app (Capacitor wrapper or the Kotlin shell).

// The Kotlin shell injects window.MavisNative before page scripts and owns the bottom navigation.
export function isNativeShell() {
  return !!window.MavisNative;
}

export function isNativeApp() {
  return isNativeShell() || !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
}
