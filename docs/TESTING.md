# Testing a program against fake devices

A test runs a compiled program on KTrapIC's IC10 emulator against a network of fake devices,
changes their values between ticks if asked, and checks values afterwards. A test of
`thermostat.ic10.kts` lives beside it as `thermostat.test.json`. The compile daemon runs one
with its `run` action; in this repository, `//compiler/api:CaseTestsTest` runs every
`test/cases/X.test.json` against `X.ic10.kts`.

## An example

```json
{
  "devices": [
    {"id": "sensor", "pin": "d0", "prefab": "StructureGasSensor", "logic": {"Temperature": 295}},
    {"id": "heater", "pin": "d1", "prefab": "StructureWallHeater"},
    {"prefab": "StructureBatterySmall", "name": "Backup", "logic": {"Charge": 100}}
  ],
  "ticks": 12,
  "changes": [
    {"tick": 3, "device": "sensor", "logic": "Temperature", "value": 280},
    {"tick": 6, "device": "sensor", "logic": "Temperature", "value": 290,
     "rampTo": {"tick": 12, "value": 320}}
  ],
  "expect": [
    {"tick": 3, "device": "heater", "logic": "On", "equals": 1},
    {"device": "heater", "logic": "On", "equals": 0},
    {"batch": {"prefab": "StructureBatterySmall", "mode": "Sum"}, "logic": "Charge", "atLeast": 50}
  ]
}
```

## The network

`devices` lists the devices on the chip's data network. Each has:

- `prefab`: the game's prefab name, as in [DEVICES.md](DEVICES.md) (`StructureGasSensor`).
- `pin` (optional): `d0` to `d5`, or `db`, to put the device on that pin as well.
- `name` (optional): its name in game, which named batches (`lbn`, `sbn`) match by hash.
- `id` (optional): what changes and expectations call it. It defaults to the pin, then the
  name, then the prefab. Ids must be unique.
- `logic` (optional): starting logic values. Every logic type the prefab has starts at 0;
  values here are set on top, and may name logic types the prefab lacks.
- `slots` (optional): starting slot values, by slot number (`{"0": {"Quantity": 3}}`). Each
  slot the prefab has, and each slot given here, starts with every slot logic type at 0.

Values are numbers; `true` and `false` are 1 and 0. Without a device on `db`, the network
gets a circuit housing (`StructureCircuitHousing`) there, with the id `db`. Whatever is on
`db`, `get` and `put` on `db` reach the chip's own stack.

A device without a `name` has a name hash of 0, where the game would hash its default name.

## Running

The program runs for `ticks` ticks (default 20, at most 10000), or until it halts or hits a
runtime error. A tick runs up to 128 lines and ends early at `yield` or `sleep`, as on the
chip.

`changes` set logic values from outside the chip, before a tick runs:

- `tick`, `device`, `logic`, `value`: sets the value before tick `tick` (ticks count from 1).
- `rampTo` (optional), `{"tick", "value"}`: carries on in a straight line to that value at
  that tick, set before each tick in between.

## Expectations

Each entry of `expect` reads one value and compares it with exactly one of `equals`,
`notEquals`, `below`, `atMost`, `above` or `atLeast`. It reads:

- `device` and `logic`: a device's logic value.
- `device`, `slot` and `logic`: a slot's value.
- `batch` and `logic`: a batch read over the network, as `lb`/`lbn` reads it, with
  `batch` holding `prefab`, an optional `name`, and `mode`: `Average`, `Sum`, `Minimum`,
  `Maximum` or `Count`.
- `register`: a register, `r0` to `r15`, `sp` or `ra`.

With `tick`, it is checked after that tick runs, and fails if the run stopped before then;
without, after the run. A test passes when every expectation holds and the program hit no
runtime error.

## The daemon's run action

`{"action": "run", "code": ..., "options": {...}, "test": {...}, "trace_tick": N}` answers
with what a compile request answers, plus `run` if the program compiled:

- `passed`, and `error`: `{"tick", "line", "message"}` for a runtime error, or null.
- `checks`: each expectation's `description` (`heater.On == 1 after tick 3: was 0`),
  `tick`, `actual` value (null if it couldn't be read) and `passed`.
- `ticks`: a snapshot of the start (tick 0) and one after each tick that ran.
- `lines`: with `trace_tick`, a snapshot after each line that tick ran, `ran` being the line.

A snapshot holds `tick`, `line` (the line that runs next), `halted`, and only what changed
since the snapshot before: `registers`, `stack` (by index; an entry back to 0 shows as 0),
`logic` (by device id) and `slots` (by device id, then slot). NaN and the infinities are the
strings `"NaN"`, `"Infinity"` and `"-Infinity"`. A test that can't be run as written gets
`run: {"error": {"description"}}` saying what is wrong with it.
