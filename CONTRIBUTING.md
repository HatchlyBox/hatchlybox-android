# Contributing

Thank you for helping. Bug reports, ideas and changes are all welcome.

## Reports and ideas

Open an issue with the **Bug report** or **Idea** template. Before pasting a log, remove anything personal: your
user name in paths (write `/home/USER`), addresses on your network, emails, tokens and keys.

## Changes

1. Make a branch from `main` (`fix/pairing-timeout`, `idea/tasks-page-filter`). Never commit to `main` directly.
2. Keep the change to one thing, with what someone using Hatchlybox would notice in the commit message.
3. Run the tests: `for t in tests/*.sh; do bash "$t" || break; done` for the engine (HatchlyBox/hatchlybox); a build
   (`./gradlew --console=plain -q assembleRelease`) for the app.
4. Open a pull request with the template filled in. It's merged once a maintainer approves it.

The house rules:

- Users don't code. Every message is plain words: what happened, and what to do next.
- Hatchlybox and its agents never use `sudo`. Where the system needs something only the user can do, say the exact
  command for them to run.
- Nothing personal in the code or its history: no names, emails, tokens, keys, network addresses or paths from a
  machine.

## Licence of contributions

Before your first contribution is merged, you agree to the [Contributor Licence Agreement](CLA.md): tick its box in
the pull request, or sign off your commits (`git commit -s`). In short: you keep your copyright; Hatchlybox may use
your contribution under the GNU AGPL 3.0 and under other terms too (for paid editions or an app store version); and
you confirm that you wrote it or have the right to send it. Every contribution we accept also stays available under
the AGPL 3.0.
