# The KTrapIC language

KTrapIC programs are a constrained subset of Kotlin, compiled to IC10 for Stationeers'
programmable chips. This guide lists what you can write and what it means on the chip. Anything
not described here is either rejected with an error at its line and column, or noted under
[Not supported yet](#not-supported-yet). The one error without a position is a program that
needs too many registers (see [Size and registers](#size-and-registers)).

- [Programs](#programs)
- [Values and variables](#values-and-variables)
- [Operators](#operators)
- [Control flow](#control-flow)
- [Functions](#functions)
- [Devices](#devices)
- [Intrinsics and maths](#intrinsics-and-maths)
- [Numbers](#numbers)
- [Size and registers](#size-and-registers)
- [Not supported yet](#not-supported-yet)
- [An example](#an-example)

## Programs

A program is a Kotlin script in a file named `*.ic10.kts`. Its top-level statements run in
order, top to bottom, once, like a chip's lines; a program that should run forever ends in a
`while (true)` loop. Top-level `val`s, `var`s and functions may be declared anywhere among the
statements.

The `ktrapic` API is imported implicitly; writing `import ktrapic.*` is allowed. Import
`kotlin.math.*` to use the maths functions listed under
[Intrinsics and maths](#intrinsics-and-maths); calls into other libraries are rejected.

```kotlin
// thermostat.ic10.kts
while (true) {
  d1.On = d0.Temperature < 290.0
  yield()
}
```

## Values and variables

There are three types of value:

- `Double`: the chip's own number type (a 64-bit floating-point number). Use it for
  measurements, settings and anything a device reports.
- `Int`: whole numbers, for counting. See [Numbers](#numbers) for how it differs from the JVM.
- `Boolean`: `true` and `false`, stored as 1 and 0. A Boolean read from a device keeps the value
  the device reported, and is true when that value isn't 0; conditions, `!`, `&&`, `||`, `==`
  and `!=` treat it so, but writing it to another device writes the value as it was read.

Other types (`Long`, `Float`, `String`, nullable types, collections other than
[static sets](#static-sets), arrays, objects) are not supported. Kotlin converts nothing
implicitly: write `1.0`, not `1`, where a `Double` is needed,
and use `toDouble()` (which costs nothing) or `toInt()` to convert.

`val` and `var` work as in Kotlin, at the top level and in blocks. A top-level property needs an
initializer and the default getter and setter; a `val` whose initializer is a constant costs no
register, and so does a `var` the program never assigns again. A `val` may also hold a device, as
an alias: `val sensor = d0`. What has no value on the chip (a device, a view, a static set, a
`Substance`, a slot, a batch or a `Ratios` entry) can only be held in a `val`, never a `var`, and
the `val` must be given it where it is declared.

## Operators

- Arithmetic on `Int` and `Double`: `+`, `-`, `*`, `/`, `%`, unary `-`, `++`, `--`, and the
  compound assignments (`+=` and so on). Int `/` truncates and `%` takes the dividend's sign,
  as on the JVM.
- Comparisons: `<`, `<=`, `>`, `>=` on numbers; `==` and `!=` on numbers and Booleans.
- Booleans: `&&`, `||` (both short-circuit) and `!`. The non-short-circuit `and`, `or` and `xor`
  on Booleans are not supported.
- Bitwise operations on `Int`: `and`, `or`, `xor`, `inv()`, `shl`, `shr`, `ushr` (see
  [Numbers](#numbers)).
- Conversions: `toDouble()`, `toInt()`, and `roundToInt()` from `kotlin.math`.

An operand keeps the value it had when it was evaluated, as in Kotlin: in `x + x++`, the first
`x` is the old value, even when a later operand (or a function it calls) changes a variable.

Constant arithmetic is computed at compile time, to the result the chip would compute; calls to
the maths functions are not, even with constant arguments.

## Control flow

- `if`/`else`, as a statement or an expression.
- `when`, with or without a subject; used as a value, it needs an `else` branch.
- `while` and `do`/`while`.
- `for` over `Int` ranges: `a..b`, `a..<b`, `a until b`, `a downTo b`, each optionally with
  `step n`. The bounds and the step are evaluated once, before the loop. A constant step must be
  positive; a step computed at run time isn't checked, and one of 0 or less loops forever where
  the JVM throws. A step applied to a stepped range is not supported.
- `for` over a [static set](#static-sets), unrolled: one copy of the body for each element.
- Ranges work only in a `for`: `x in a..b` in an `if` or a `when` branch is rejected; `in` a
  static set works anywhere.
- `break` and `continue`, including labelled ones (`break@outer`).

## Functions

Top-level functions with `Int`, `Double` and `Boolean` parameters and results (or no result) are
supported, including early `return`, expression bodies, and reading and writing top-level
properties.

- A function called once, or marked `inline`, is compiled into the place it is called.
- Any other function gets one body, called with `jal`. Its arguments and result travel in
  registers set aside for it, and it saves `ra` on the stack only if it calls another function.
- Only an inlined function can take a `Device`, a view, a `Substance` or a static set parameter.
- A `String` parameter takes a constant string, or another `String` parameter, as its argument.
  In an inlined function it stands for that text wherever a constant string can go: `Text = msg`,
  `str(msg)`, `hash(msg)`, `msg.hash()`, `named(msg)`, or another call's `String` argument. A
  function called with `jal` gets the text packed by `STR` in a register, so there it can only be
  written with `Text = msg`, read as `str(msg)`, or passed on; `hash` and `named` need the function
  marked `inline`. Each call to such a function checks its text against `STR`'s limits.
- Not supported: recursion, default arguments, `vararg`, and extension, generic and local
  functions.

A call made before a property the function reads has been declared is an error when the call is
inlined, reported where the function reads the property. When it is called with `jal`, it
compiles and reads the property before its initializer has run, unless the property is a `val`
with a constant initializer, whose value is used directly.

## Devices

The chip's pins are `d0` to `d5`, and `db` is the housing the chip is in. Each device has one
property per logic type, named as in the game:

```kotlin
d0.Vertical = 45.0
val strength = d0.SignalStrength
d2.Setting = strength
```

Logic types that are on or off (`On`, `Open`, `Lock`, `Activate`, `Power`, `Error`, `Idle` and a
few others) are `Boolean`; all the others are `Double`. To read or write any logic type as a
`Double`, index the device by `LogicType`: `d0[LogicType.Setting] = 1.0`. The logic type must
be written out (`LogicType.X`), not computed.

The game's value enums are `Double` constants, so they can be written straight to a device:
`db.Color = Color.Red`, `d5.Mode = DisplayMode.String`.

A device can be held in a `val` (`val dish = d0`) and passed to an inlined function. Reading or
writing a logic type a device doesn't have is an error on the chip.

### Typed devices and batches

Each prefab with logic types has an entry point named after it in the plural, without the
game's `Structure` or `Item` prefix: `StructureGrowLight` is `GrowLights`. Where two prefabs
would get the same name that way, both keep their full names, as `ItemBeacons` and
`StructureBeacons` do. The [device reference](DEVICES.md) lists every prefab with its entry
point, type and logic types, and the game's value enums. Through an entry point:

- `GrowLights.at(d0)` is the device on `d0` as a `GrowLight`, which has only the logic types a
  grow light has, as `val` where they can only be read. Using one it lacks is a compile error.
- `GrowLights.all` is every grow light on the chip's network, and `GrowLights.named("Potatoes")`
  every one with that name: a batch, which is used like a Kotlin collection of `GrowLight`s.

A batch is read with collection operations, each of which compiles to one batch instruction
(`lb`, `lbn`): `map { it.X }` followed by `average()`, `sum()`, `min()` or `max()`; `sumOf`,
`minOf` and `maxOf`; `count { it.On }`, how many have a logic type on; `any { it.On }`,
`all { it.On }` and `none { it.On }`, which inside an `if` become the branch itself; and
`count()`, how many devices of the prefab there are. The game can't count the devices with a
name, so `count()` on a `named(...)` batch is a compile error. A batch is written with `forEach`,
each of whose assignments writes every device (`sb`, `sbn`):

```kotlin
GrowLights.all.forEach { it.On = true }
GrowLights.named("Potatoes").forEach { it.On = false }
val lit = GrowLights.all.count { it.On }
if (GrowLights.named("Potatoes").any { it.On }) db.On = true
val charge = Batteries.Small.all.map { it.Charge }.average()
val most = Batteries.Small.all.maxOf(Battery.Small::Charge)
val sensor = GasSensors.at(d0)
db.Setting = if (sensor.Temperature > 300.0) charge else most
```

The lambdas are narrower than Kotlin's: a reading one may only pick a logic type, as `{ it.X }` or
a property reference such as `Battery.Small::Charge`, and a writing one may only assign logic
types of `it` (or of its slots), with values that don't use `it`. A written value, or a slot
number in `it.slots[n]`, is computed once for all the devices, even for none, so it may have no
side effect: it can't assign anything (`n++`), write a device (`d0.On = true`,
`d0[LogicType.On] = 1.0`, a slot's logic type, or a batch's `forEach`), or call `sleep` or
`yield`, nor call a function that does any of these. A value that reads the batch being written
reads it once, before any device is written. A writing lambda can't `return`. Anything else is a
compile error. The result of `map` can only be ended with one of the four functions above, in the
same expression: it can't be iterated or held in a `val`.

With no devices in a batch, `average()` is NaN, as in Kotlin, but `max()` and `maxOf` give
negative infinity, and `min()` and `minOf` 0, where Kotlin throws. `any` is false and `none` true,
as in Kotlin, but `all` is false, where Kotlin gives true. A typed device or a batch can be held
in a `val`; a `map` can't.

`count { it.On }` sums the logic type over the batch, and `any`, `all` and `none` compare its
greatest or least value with 0, so all four take an on-or-off logic type to be 0 or 1. The game
reports them so, and clamps a value written to one to that range.

Prefabs that do one kind of job are grouped into families: `Batteries.Small.at(d0)` is a
`Battery.Small`, and `Batteries.Large.all` every large one. On its own, `Battery` is no prefab: it
is the interface its members share, with the logic types all of them can read, so an inlined
function can take any battery. It has only those logic types: `PrefabHash`, `NameHash` and
`ReferenceId` are read from a member (`Batteries.Small.at(d0)`) or its pin. Prefabs are grouped
this way when they do one kind of job and share their logic types. That covers one device in
several sizes, fluids or styles (`Valves.Gas` and `Valves.Liquid`, `Transformers.Small`,
`SolarPanels.HeavyAngled`, `DirectHeatExchangers.LargeGasToLiquid`). It also covers different
devices that a program uses the same way, even when they work in opposite directions:
`Switches.Lever` and `Switches.Standard` both give an on/off signal, a back pressure regulator is
`PressureRegulators.BackGas`, and the microwave power transmitter and receiver are
`PowerTransmissions.MicrowaveTransmitter` and `.MicrowaveReceiver`. A member keeps the logic types
only it has: `DigitalChutes.ValveLeft` adds `Lock` and `Open` to the `DigitalChute` interface.
Devices that merely look alike from a chip, with nothing a program would do to both, are not
grouped. A family that is a special case of another extends its
interface: an elevator level is a shaft with doors and power, so `ElevatorLevel` extends
`ElevatorShaft`, and a function taking an `ElevatorShaft` takes an `ElevatorLevels.Standard` too.
Likewise interior doors, hangar doors and airlocks extend `Door`, the family of the glass, blast
and composite doors, so `inline fun close(door: Door)` closes any of them.
The [device reference](DEVICES.md#families) lists every family and its members; every other prefab
has its own entry point.

### Views

Some logic types come in families, which a device groups as views rather than one property each:

- `Ports`: a device's `Input`, `Input2`, `Output` and `Output2` ports, each with `Pressure`,
  `Temperature`, `TotalMoles`, `Combustion`, `Setting` and `Ratios` (`PressureOutput`,
  `SettingInput`, `RatioOxygenOutput2`).
- `Ratios`: the ratio of each `Substance` in the device, by key (`RatioOxygen`).
- `Position`, `Velocity`, `VelocityRelative`, `Forward`, `Dimensions` and `Target`: vectors, by
  `X`, `Y` and `Z` (`PositionX`, `SizeZ`).
- `Import` and `Export`: `Count`, `Quantity`, `SlotHash` and `SlotOccupant` (`ImportCount`).

```kotlin
val filtration = Filtrations.Gas.at(d0)
val outlet = filtration.Ports.Output
val oxygen = outlet.Ratios[Substance.Oxygen]
if (outlet.Pressure > 5000.0) filtration.On = false
val sensors = GasSensors.all.map { it.Ratios[Substance.Nitrogen] }.average()
db.Setting = oxygen + sensors
```

A view is resolved when the program is compiled: each logic type it names is the same one
instruction the logic type's own name would be, and a view takes no register. It can be held in a
`val` or passed to an inlined function, as a device can, but it has no value of its own. A key,
such as `Substance.Oxygen`, must be written out, held in a `val` or passed to an inlined function.
Only a view's logic types can be used: `equals`, `hashCode` and `toString` are errors.

A typed device has only the views its prefab has, so naming another is a type error, and the
compiler checks the rest against the prefab: `SatelliteDishes.Medium.at(d0).Dimensions.Y` is an
error, "SatelliteDish.Medium has no SizeY", as the dish reports only `SizeX` and `SizeZ`. The
[device reference](DEVICES.md#views) says what each device's views reach. A pin used untyped
(`d0`) has every view, as it has every logic type, and the compiler checks only that each name
it makes is one the game has: `d0.Ports.Output2.Setting` is an error, as the game has no
`SettingOutput2`. The flat names are still reached through a pin's index:
`d0[LogicType.PressureOutput]`.

```kotlin
inline fun charge(battery: Battery): Double = battery.Ratio
db.Setting = charge(Batteries.Small.at(d0)) + Batteries.Large.all.map { it.Ratio }.average()
```

A prefab's name is also its value: `GrowLight` and `Battery.Small` are the game's hashes of
those prefabs, and a device's `PrefabHash` is a prefab, so `d0.PrefabHash == Battery.Small` asks
whether `d0` is a small battery. A prefab is not a number and can only be compared; read
`d0[LogicType.PrefabHash]` for the number. `"Name".hash()` is the hash of any other text, such as
a modded prefab.

LED displays have a `Text` property that writes one to six ASCII characters to their `Setting`,
which they show while their `Mode` is `DisplayMode.String`. The text must be written out; longer
text, or anything but printable ASCII, is a compile error. `Text` is on the `LedDisplay`
interface too, so an inlined function taking any LED display can write it.

```kotlin
val sign = LedDisplays.Small.at(d5)
sign.Mode = DisplayMode.String
sign.Text = "HOT"
LedDisplays.Large.all.forEach { it.Text = "OK" }
```

A function can take the text as a `String` parameter (see [Functions](#functions)):

```kotlin
val panel = LedDisplays.Small.at(d4)
fun debug(msg: String) {
    panel.Mode = DisplayMode.String
    panel.Text = msg
}
debug("START")
debug("DONE")
```

### Static sets

A static set is a collection the compiler knows whole: `setOf` or `listOf` of constants (numbers
or `Substance` entries), `Substance.entries`, a view's `Ratios.keys` (the substances its device
reports), sets made of these with `+`, `-` and `intersect` (which act as in Kotlin, so a list's `+`
keeps duplicates), and a `val` holding any of them. It never exists on the chip. A `for` over one
is unrolled: the body is compiled once for each element, with the loop variable a constant in that
copy, so it can key a view, and only the elements given cost lines. A loop over `keys` or
`Substance.entries` is one copy per substance, up to 28, so its body should be short:

```kotlin
val sensor = GasSensors.at(d0)
val breathable = setOf(Substance.Oxygen, Substance.Nitrogen, Substance.CarbonDioxide)
var other = 1.0
for (gas in breathable) other -= sensor.Ratios[gas]
var heaviest = 0.0
for (gas in sensor.Ratios.keys - breathable) {
  if (sensor.Ratios[gas] > heaviest) heaviest = sensor.Ratios[gas]
}
db.Setting = other + heaviest
```

`continue` goes on to the next element's copy, and `break` past the last. A set's `size` and an
`in` test of a constant are constants; `in` with a number known only when the program runs
compiles to one comparison per element. A static set can be held in a `val` and passed to an
inlined function, but not used as a value otherwise. Elements
compare as numbers, unlike Kotlin's: `2` and `2.0` are the same element. A `for` over an `Int`
range is not unrolled: it stays a loop.

A loop over a view's `Ratios` itself gives each substance its device reports with its ratio, by
destructuring (`(gas, ratio)`) or as an entry's `key` and `value`, unrolled the same way. Two
substances compare with `==` and `!=` when compiled, so a test of the loop's substance costs
nothing:

```kotlin
var other = 0.0
for ((gas, ratio) in GasSensors.at(d0).Ratios) {
  if (gas == Substance.Oxygen || gas == Substance.Nitrogen) continue
  other += ratio
}
db.Setting = other
```

### Slots

A device's slots are `slots`, numbered from 0: `d0.slots[0].Quantity` reads a slot's logic type
(`ls`). The number may be computed, such as a loop's variable; it is fixed when the slot is
taken, so a slot held in a `val` keeps its number when the variable changes. A constant number
must be from 0 to 255 (255 is a robotic arm dock's target slot). That range is all the compiler
checks, not the device's own slots: any number past a device's last slot, constant or computed, is
an error on the chip.

```kotlin
var plants = 0.0
for (i in 0..<4) plants += d0.slots[i].Quantity
d1.slots[0].Open = true
db.Setting = plants
```

The game lets a program write only a few slot logic types: `On` on helmet, tool and appliance
slots, `Lock` on helmet slots, `Open` on helmet, canister and bottle slots, and `Mode` on any
slot of a console. A pin's slots offer all four, and every slot logic type to read; a slot can
read only some of them, and reading or writing one it lacks is an error on the chip.

A typed device's slots have only what its kind of slot has, and only the writes the game allows
there, so anything else is a compile error. Where all of a device's slots are of one kind, such as
a battery charger's battery slots, `slots[n]` is of that kind; otherwise it has the logic types
every slot can read. Slots the game names are also properties, of their own kind:

```kotlin
val tanks = TankStorages.Gas.at(d2)
tanks.GasCanister.Open = true
db.Ratio = BatteryChargers.Standard.at(d3).slots[0].Charge
```

A batch's `slots[n]` is that slot of every device in it, read and written like a batch (`lbs`,
`lbns`, `sbs`); a `forEach` over the devices can also write their slots. The game can't write the
slots of a named batch.

```kotlin
val charge = BatteryChargers.Standard.all.slots[0].map { it.Charge }.average()
val north = BatteryChargers.Standard.named("North").slots[1].maxOf { it.Charge }
TankStorages.Gas.all.slots[0].forEach { it.Open = false }
TankStorages.Gas.all.forEach { it.GasCanister.Open = true }
```

A slot, or a device's or batch's `slots`, can be held in a `val`.

A thing that isn't a structure, such as a tablet, a suit, a tool, the AIMeE bot or a rover, has
other rules: none of its slots can be written, and they read fewer logic types (not
`SortingClass`, `FreeSlots` or `TotalSlots`). Its typed slots (`ItemBatterySlot`,
`ItemCommonSlot`, ...) follow those rules, so `Tablets.Advanced.at(db).Battery.Charge` compiles and
anything a thing's slot can't do is a compile error. A pin's untyped slots don't know which kind
of device they're on, so they offer a structure's.

## Intrinsics and maths

- `sleep(seconds)` pauses the chip (`seconds` an `Int` or a `Double`); `yield()` ends the
  chip's work for the current tick (half a second).
- `hash("text")` (or `"text".hash()`) is the game's hash of `text`, such as a prefab or device
  name; `str("text")` packs one to six ASCII characters into a number, for displays. Both need a
  constant string, which may not contain `"`, `#`, a line break or another control character.
- From `kotlin.math`: `abs`, `sqrt`, `floor`, `ceil`, `round` (halves to even), `truncate`,
  `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `exp`, `ln`, `max`, `min`, `pow`,
  `absoluteValue` and `roundToInt` (halves up), and the constants `PI` and `E`. From
  `kotlin.ranges`: `coerceAtLeast`, `coerceAtMost` and `coerceIn(min, max)` (not the overload
  taking a range).

Other library calls are rejected.

## Numbers

IC10 has one kind of number, a 64-bit floating-point number, and Kotlin's `Double` is exactly
that: arithmetic, comparisons, NaN and infinity behave on the chip as they do on the JVM.

`Int` compiles to the same kind of number and behaves as on the JVM while it stays in the `Int`
range: `/` truncates, `%` takes the dividend's sign, and `toInt()` truncates, limits the result
to the `Int` range and turns NaN into 0. `roundToInt()` limits its result the same way. Where
the chip can't match the JVM:

- Arithmetic past the `Int` range doesn't wrap: an `Int` holding 2147483647 becomes 2147483648
  when incremented. A constant expression that would overflow is a compile error instead.
- Bitwise operations and shifts work on 53-bit integers with a sign: a value wraps modulo 2^53,
  a result keeps bits 0–52 with bit 53 as its sign, and `ushr` shifts the value as an unsigned
  54-bit number (`-1 ushr 1` is 2^53 - 1). They match the JVM for non-negative values whose
  results stay in the `Int` range, with shift counts between 0 and 31. At run time a count
  outside that range isn't reduced modulo 32 (`5 shr 32` is 0, not 5). A constant shift count
  outside 0..31, or a constant bitwise expression whose result differs from the JVM's (such as
  `-1 ushr 28`), is a compile error.
- `roundToInt()` of NaN gives 0, where the JVM throws.
- An `Int` product or quotient computed at run time that is zero can be a negative zero
  (`n * -1` or `n / -5` with `n` 0); a constant one is 0. It equals 0, but the sign shows once
  it becomes a `Double`: `1.0 / m.toDouble()` is negative infinity.
- Dividing an `Int` by zero at run time gives NaN or infinity, where the JVM throws. An `Int`
  holding NaN compares as a `Double` NaN does: every ordered comparison is false, and a `for`
  loop with a NaN bound runs no iterations. Dividing by a constant zero is a compile error.
- `Double` `%` is computed as `a - truncate(a / b) * b`, which rounds at the division and the
  multiplication, so it can differ from the JVM's exact remainder: `10.0 % 0.1` is 0.0 rather
  than 0.09999999999999945, and `x % Double.POSITIVE_INFINITY` is NaN rather than `x`. A zero
  result is always 0.0, where the JVM gives it the dividend's sign: `-3.0 % 3.0` is 0.0, not
  -0.0.
- `max(a, b)` and `min(a, b)` return `b` when `a` and `b` are 0.0 and -0.0 in either order,
  where the JVM's `max` gives 0.0 and its `min` gives -0.0. `coerceAtLeast`, `coerceAtMost` and
  `coerceIn` return the bound when it equals the receiver, so `(-0.0).coerceIn(0.0, 1.0)` is 0.0,
  where the JVM keeps -0.0. A NaN bound makes their result NaN, where the JVM ignores it, and
  `coerceIn` with a minimum greater than its maximum returns the maximum, where the JVM throws.

Constant expressions fold to the same results the chip computes. A NaN constant appears in the
output as `0 / 0`, since the chip rejects `nan` written as an operand, and a negative-zero
constant as `0 * -1`; each use of either costs a line and a temporary register, except that a
negative zero is compared as 0.

## Size and registers

A chip holds at most 128 lines of at most 90 characters, and 4096 characters in all. The
compiler measures programs as the game's code editor does: every line counts, and each line
break counts as two characters, so a 128-line program has 3842 characters for its text.
Compact output (resolving labels to line numbers and dropping them) saves lines. The compiler
reports these counts but does not reject a program over the limits.

Values live in the registers `r0` to `r15`; the compiler reuses a register once its value is no
longer needed. A program that needs more than 16 values at once is rejected, with an error that
has no line or column.

## Not supported yet

Planned, roughly in this order:

- Your own enums and `when` over them; classes, interfaces and objects, compiled away at compile
  time; inline functions taking lambdas. Features are only added where their cost in lines is
  worth it on a 128-line chip.

Not planned as such: strings beyond `hash`/`str` and `Text`, collections beyond static sets,
nullable types, exceptions, casts and type checks, and coroutines.

## An example

[`test/cases/satellite_tracker.ic10.kts`](../test/cases/satellite_tracker.ic10.kts) is a port of
a chip from the game: it points a dish at a satellite, scanning the horizon until it hears a
signal and then nudging the dish while the signal improves. It uses functions (some inlined, some
called with `jal`), device properties, `str` for status text, and `sleep`, and compiles to 87
lines in compact form.
