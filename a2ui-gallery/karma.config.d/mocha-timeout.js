// The corpus test validates every message in all 43 examples against the basic catalog: 126
// messages, each one a schema evaluation, after decoding a fifty-kilobyte catalog. On a JVM that
// is tens of milliseconds; in a browser on a CI runner with three processors it is not, and
// mocha's default of two seconds is a unit-test default rather than a budget for this.
//
// The failure this prevents is worth naming, because it is the one that already cost a debugging
// round in `a2ui-core`: karma truncates the message to `Error`, so a timeout arrives looking like
// an assertion failure with no assertion in it. Locally the same test passes, which points the
// investigation at everything except the clock.
//
// Merged rather than assigned: `config.set({client: ...})` replaces the whole `client` block, and
// the generated config already puts the test runner's own arguments there.
//
// Karma's own budget is raised alongside mocha's, because mocha's alone is not reachable.
// `browserNoActivityTimeout` defaults to 30s and is counted independently: a test that runs for
// 45s sends karma nothing while it does, so karma disconnects the browser before mocha's 60s ever
// fires. That arrives as `Disconnected, because no message in 30000 ms` -- an infrastructure
// fault, which is the same misattribution this file exists to prevent, wearing the other hat.
config.set({
    browserNoActivityTimeout: 120000,
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, {timeout: 60000}),
    }),
});
