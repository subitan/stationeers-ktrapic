# KTrapIC for IntelliJ IDEA: changes

Each release's section becomes its change notes on JetBrains Marketplace. Add changes under
Unreleased as they land; a release moves them under its version (see docs/RELEASING.md).

## [Unreleased]

### Added

- KTrapIC's errors and warnings, marked in the editor as you type.
- The IC10 tool window: the compiled program, with its lines, bytes and
  registers against the chip's limits, and the compiler's warnings.
- Settings for the compile daemon (Settings > Tools > KTrapIC).
- No compile options: a program chooses compact output, calls in registers and tail calls itself,
  with `@file:Compact`, `@file:InRegisters` and `@file:NoTailCalls`, and the plugin sends none.
- The compile daemon, and the SDK and script definition the Kotlin plugin checks programs against,
  bundled: the plugin needs nothing else installed.
