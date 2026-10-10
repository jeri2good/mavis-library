// App version from package.json, set at build time (vite.config.js `define`).

/* global __APP_VERSION__ */
export const APP_VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '';
