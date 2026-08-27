// A renderer test decodes a fifty-kilobyte catalog before it can resolve a single child, and a
// Compose UI test composes a tree on top of that. On a JVM that is tens of milliseconds; in a
// browser on a CI runner it is not, and mocha's default of two seconds is a unit-test default
// rather than a budget for this.
//
// Added before it was needed rather than after. `a2ui-gallery` learned this the expensive way: the
// setting is per-module, so a new module starts at mocha's default no matter what its neighbours
// carry, and the failure is uniquely unhelpful -- karma truncates the message to `Error`, so a
// timeout arrives looking like an assertion failure with no assertion in it, and the same test
// passes locally.
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
