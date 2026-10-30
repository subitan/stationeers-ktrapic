// The playground's file-annotation code: what it finds in a program's header, and what ticking or
// unticking a checkbox does to the program. Run with `node --test playground/*.test.mjs`.
//
// Undo (Ctrl+Z after a tick) goes through the editor's own edits (Monaco's, or the browser's for
// the plain textarea), so it is checked in the page itself, not here.

import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const { scanHeader, findAnnotation, additionEdit, removalEdit } =
  createRequire(import.meta.url)("./annotations.js");

const has = (text, name) => findAnnotation(scanHeader(text), name) !== null;

/** The program after ticking (`+`) and unticking (`-`) each named checkbox in turn. */
function run(text, ops) {
  for (const [op, name] of ops) {
    const edit = (op === "+" ? additionEdit : removalEdit)(text, name);
    if (edit) text = text.slice(0, edit.start) + edit.text + text.slice(edit.end);
  }
  return text;
}

const cases = [
  // A file annotation over several lines.
  ["find after multi-line", has('@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n', "Compact"), true],
  ["add after multi-line", run('@file:Suppress(\n  "A",\n)\nimport ktrapic.*\n', [["+", "Compact"]]), '@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n'],
  ["add another after multi-line", run('@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n', [["+", "InRegisters"]]), '@file:Suppress(\n  "A",\n)\n@file:Compact\n@file:InRegisters\nimport ktrapic.*\n'],
  ["add present after multi-line", run('@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n', [["+", "Compact"]]), '@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n'],
  ["remove after multi-line", run('@file:Suppress(\n  "A",\n)\n@file:Compact\nimport ktrapic.*\n', [["-", "Compact"]]), '@file:Suppress(\n  "A",\n)\nimport ktrapic.*\n'],

  // Comments before and among the annotations.
  ["find under block comment", has("/* Tracker */\n@file:Compact\nimport x\n", "Compact"), true],
  ["add under block comment", run("/* Tracker */\n@file:Compact\nimport x\n", [["+", "Compact"], ["+", "InRegisters"]]), "/* Tracker */\n@file:Compact\n@file:InRegisters\nimport x\n"],
  ["remove under block comment", run("/* Tracker */\n@file:Compact\nimport x\n", [["-", "Compact"]]), "/* Tracker */\nimport x\n"],
  ["add under KDoc", run("/**\n * Tracker.\n */\n@file:InRegisters\nimport x\n", [["+", "Compact"]]), "/**\n * Tracker.\n */\n@file:InRegisters\n@file:Compact\nimport x\n"],
  ["find in trailing comment", has('@file:Suppress("X") // @file:Compact\nimport x\n', "Compact"), false],
  ["add with trailing comment", run('@file:Suppress("X") // @file:Compact\nimport x\n', [["+", "Compact"]]), '@file:Suppress("X") // @file:Compact\n@file:Compact\nimport x\n'],
  ["remove absent is a no-op", run('@file:Suppress("X") // @file:Compact\nimport x\n', [["-", "Compact"]]), '@file:Suppress("X") // @file:Compact\nimport x\n'],
  ["nested comment", has("/* a /* b */ @file:Compact */\nimport x\n", "Compact"), false],
  ["trailing line comment kept", run("@file:Compact // keep\nimport x\n", [["+", "InRegisters"]]), "@file:Compact // keep\n@file:InRegisters\nimport x\n"],

  // Empty arguments, and Kotlin's `@file:[A B]` form.
  ["find Compact()", has("@file:Compact()\nimport x\n", "Compact"), true],
  ["remove Compact()", run("@file:Compact()\nimport x\n", [["-", "Compact"]]), "import x\n"],
  ["remove Compact() beside another", run("@file:Compact() @file:InRegisters\nimport x\n", [["-", "Compact"]]), "@file:InRegisters\nimport x\n"],
  ["find in brackets", has("@file:[Compact InRegisters]\nimport x\n", "InRegisters"), true],
  ["add beside brackets", run("@file:[Compact InRegisters]\nimport x\n", [["+", "Compact"], ["+", "NoTailCalls"]]), "@file:[Compact InRegisters]\n@file:NoTailCalls\nimport x\n"],
  ["remove first in brackets", run("@file:[Compact InRegisters]\nimport x\n", [["-", "Compact"]]), "@file:[InRegisters]\nimport x\n"],
  ["remove last in brackets", run("@file:[Compact InRegisters]\nimport x\n", [["-", "InRegisters"]]), "@file:[Compact]\nimport x\n"],
  ["remove both in brackets", run("@file:[Compact InRegisters]\nimport x\n", [["-", "InRegisters"], ["-", "Compact"]]), "import x\n"],
  ["brackets with arguments", run('@file:[Suppress("a]") Compact]\nimport x\n', [["-", "Compact"]]), '@file:[Suppress("a]")]\nimport x\n'],

  // Where a new annotation goes.
  ["add after shebang", run("#!/usr/bin/env kotlin\nimport x\n", [["+", "Compact"]]), "#!/usr/bin/env kotlin\n@file:Compact\nimport x\n"],
  ["add after shebang only", run("#!/usr/bin/env kotlin", [["+", "Compact"]]), "#!/usr/bin/env kotlin\n@file:Compact"],
  ["add to empty", run("", [["+", "Compact"]]), "@file:Compact\n"],
  ["add with no header", run("import x\n", [["+", "Compact"]]), "@file:Compact\nimport x\n"],
  ["add after last line without newline", run("@file:Compact", [["+", "InRegisters"]]), "@file:Compact\n@file:InRegisters"],

  // Strings in arguments.
  ["string holding a paren", run('@file:Suppress("a)b") @file:Compact\nimport x\n', [["-", "Compact"]]), '@file:Suppress("a)b")\nimport x\n'],
  ["raw string holding a paren", has('@file:Suppress("""x)""")\n@file:Compact\n', "Compact"), true],

  // Names.
  ["qualified name", has("@file:ktrapic.Compact\nimport x\n", "Compact"), true],

  // Where the header ends.
  ["after an import is not header", has("import x\n@file:Compact\n", "Compact"), false],
  ["after a package is not header", has("package p\n@file:Compact\n", "Compact"), false],

  // String templates: `${...}` is code, so its quotes and parens don't end the string.
  ["find after a template holding a quote and paren", has('@file:Suppress("${")"}")\n@file:Compact\nfun main(){}', "Compact"), true],
  ["add after a template holding a quote and paren", run('@file:Suppress("${")"}")\nval x = 1\n', [["+", "Compact"]]), '@file:Suppress("${")"}")\n@file:Compact\nval x = 1\n'],
  ["template holding a brace in a string", has('@file:Suppress("${"}\\")"}")\n@file:Compact\n', "Compact"), true],
  ["template nested in a template", has(`@file:Suppress("\${ "\${ '"' })" }")\n@file:Compact\n`, "Compact"), true],
  ["template in a raw string", has('@file:Suppress("""${"""a)"""}""")\n@file:Compact\n', "Compact"), true],
  ["template holding a character", has("@file:Suppress(\"${'\"'})\")\n@file:Compact\n", "Compact"), true],
  ["escaped dollar is not a template", has('@file:Suppress("\\${")\n@file:Compact\n', "Compact"), true],

  // Backticked names.
  ["find backticked", has("@file:`Compact`\nimport x\n", "Compact"), true],
  ["add when backticked is present", run("@file:`Compact`\nimport x\n", [["+", "Compact"]]), "@file:`Compact`\nimport x\n"],
  ["remove backticked", run("@file:`Compact`\nimport x\n", [["-", "Compact"]]), "import x\n"],
  ["find backticked qualified", has("@file:ktrapic.`Compact`\nimport x\n", "Compact"), true],
  ["find backticked in brackets", has("@file:[`InRegisters` Compact]\nimport x\n", "InRegisters"), true],
];

for (const [name, got, want] of cases) {
  test(name, () => assert.equal(got, want));
}
