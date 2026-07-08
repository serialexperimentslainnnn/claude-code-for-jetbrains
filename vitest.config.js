// Frontend (JCEF web app) unit tests. The app under test is the inlined ES2019 code in
// src/main/resources/jcef/*.js — it runs in an embedded Chromium at runtime, so we exercise it here in jsdom.
// No new runtime dependency ships in the plugin: vitest + jsdom are devDependencies only.
module.exports = {
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/test/frontend/**/*.test.js'],
    // The app JS is loaded manually into each test's fresh jsdom document (see helpers/load.js), so no setup file.
    restoreMocks: true,
    clearMocks: true,
    // Console reporter locally; a JUnit file for the GitLab pipeline to ingest (mirrors the Kotlin test job).
    reporters: process.env.CI ? ['default', 'junit'] : ['default'],
    outputFile: { junit: 'build/reports/frontend/junit.xml' },
  },
};
