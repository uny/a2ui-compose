// `MarkdownBlocksTest` parses an eight-thousand-level quote on every target, and Mocha's default
// 2s per test is close enough to that parse on a CI runner to fail on a slow one (#89: `js` red
// on one attempt, green on the next). The parse is the point of the test, so the budget moves,
// not the depth. A Karma override rather than `useMocha { timeout }`: that DSL adds
// `source-map-support` to the JS store and so moves the yarn lock; this does not.
config.set({
    client: {
        mocha: {
            timeout: 30000,
        },
    },
});
