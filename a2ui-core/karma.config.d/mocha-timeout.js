// The conformance suite is one test over 153 assertions, by design: the cases are data the
// specification owns, and splitting them into 153 hand-written tests would be a second copy of
// that list. Reaching them means parsing fourteen case files and decoding a fifty-kilobyte
// catalog, which on a JVM is a few tens of milliseconds and in a browser on a CI runner is not.
//
// Mocha's default of two seconds is a unit-test default. Raising it here is not hiding a slow
// implementation -- `ConformanceCostTest` measures what the suite actually costs, and a JVM run of
// all 153 assertions takes about 70ms.
// Merged rather than assigned: `config.set({client: ...})` replaces the whole `client` block, and
// the generated config already puts the test runner's own arguments there.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, {timeout: 60000}),
    }),
});
