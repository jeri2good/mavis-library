// App version from package.json and, in the Android build only, a build
// number (MAVIS_BUILD_NUMBER, the CI run counter). Set at build time by
// vite.config.js `define`.

/* global __APP_VERSION__, __APP_BUILD__ */
export const APP_VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '';
export const APP_BUILD = typeof __APP_BUILD__ === 'string' ? __APP_BUILD__ : '';

/** "Version 0.4.0" on the web; "Version 0.4.0 (build 12)" in the Android app. */
export const versionLabel = () => `Version ${APP_VERSION || 'unknown'}${APP_BUILD ? ` (build ${APP_BUILD})` : ''}`;
