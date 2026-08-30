// A Compose UI test composes a tree, and a `Text` in this module parses Markdown before it draws.
// On a JVM that is milliseconds; in a browser on a CI runner it is not, and mocha's default of two
// seconds is a unit-test default rather than a budget for this.
//
// Missing until now, which is the failure the neighbouring modules' copies of this file warn
// about: the setting is per-module, so a new module starts at mocha's default no matter what its
// neighbours carry. The tests happened to fit on the macOS runner they were written on. Moving the
// web backends to a Linux runner changes the machine underneath them, and a timeout there would
// arrive as karma's truncated `Error` -- an assertion failure with no assertion in it, passing
// locally, pointing the investigation at everything except the clock.
//
// Merged rather than assigned: `config.set({client: ...})` replaces the whole `client` block, and
// the generated config already puts the test runner's own arguments there.
//
// Karma's own budget is raised alongside mocha's, because mocha's alone is not reachable.
// `browserNoActivityTimeout` defaults to 30s and is counted independently: a test that runs for
// 45s sends karma nothing while it does, so karma disconnects the browser before mocha's 60s ever
// fires.
config.set({
    browserNoActivityTimeout: 120000,
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, {timeout: 60000}),
    }),
});
