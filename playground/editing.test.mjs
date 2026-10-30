// The playground editor's reading of compile responses and LSP answers: where a compile's problems
// are marked, and how completions and hovers show. Run with `node --test playground/*.test.mjs`.
//
// The markers, completions and hovers themselves are Monaco's, so they are checked in the page.

import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const { problemsOf, completionKind, markdownText, hoverContents, plainOf } =
  createRequire(import.meta.url)("./editing.js");

const range = (p) => [p.startLine, p.startColumn, p.endLine, p.endColumn];

test("a failure's errors cover the identifier where each starts", () => {
  const text = "val x = 1\nd0.Setting = y\n";
  const res = {
    error: {
      description: "first", line: 2, column: 14,
      errors: [
        { description: "Unresolved reference 'y'", line: 2, column: 14 },
        { description: "Bad setting", line: 2, column: 4 },
      ],
    },
  };
  const problems = problemsOf(res, text);
  assert.deepEqual(problems.map((p) => p.severity), ["error", "error"]);
  assert.deepEqual(problems.map((p) => p.message), ["Unresolved reference 'y'", "Bad setting"]);
  assert.deepEqual(range(problems[0]), [2, 14, 2, 15]);
  assert.deepEqual(range(problems[1]), [2, 4, 2, 11]);
});

test("a failure without a list of errors is its own error", () => {
  const problems = problemsOf({ error: { description: "Oops", line: 1, column: 5 } }, "val x = 1");
  assert.deepEqual(problems.map((p) => [p.message, ...range(p)]), [["Oops", 1, 5, 1, 6]]);
});

test("an error with no position is put at the start of the program", () => {
  const res = { error: { description: "Internal compiler error", line: 0, column: 0, stack_trace: "..." } };
  assert.deepEqual(problemsOf(res, "sleep(1.0)\n").map(range), [[1, 1, 1, 6]]);
});

test("a success's warnings are warnings", () => {
  const res = { code: "yield\n", warnings: [{ description: "Variable 'a' is never used", line: 1, column: 5, kotlin: true }] };
  const [w] = problemsOf(res, "val a = 1\n");
  assert.equal(w.severity, "warning");
  assert.deepEqual(range(w), [1, 5, 1, 6]);
});

test("a clean compile has no problems", () => {
  assert.deepEqual(problemsOf({ code: "yield\n", warnings: [] }, "sleep(1.0)"), []);
  assert.deepEqual(problemsOf({ code: "yield\n" }, "sleep(1.0)"), []);
});

test("a position past the end of its line, or of the program, is clamped", () => {
  const res = { error: { description: "x", line: 1, column: 40, errors: [
    { description: "past the line", line: 1, column: 40 },
    { description: "past the program", line: 9, column: 1 },
  ] } };
  assert.deepEqual(problemsOf(res, "val a\r\nval b").map(range), [[1, 6, 1, 6], [2, 1, 2, 4]]);
});

test("columns skip a leading byte-order mark, as the compiler's do", () => {
  const res = { error: { description: "x", line: 1, column: 1 } };
  assert.deepEqual(problemsOf(res, String.fromCharCode(0xfeff) + "foo()").map(range), [[1, 2, 1, 5]]);
});

test("LSP completion kinds name Monaco's", () => {
  assert.equal(completionKind(3), "Function");
  assert.equal(completionKind(6), "Variable");
  assert.equal(completionKind(10), "Property");
  assert.equal(completionKind(20), "EnumMember");
  assert.equal(completionKind(99), "Text");
});

test("a hover shows its declaration as Kotlin and its KDoc as text", () => {
  const text = "LedDisplay.Small\nvar Setting: Double (write-only)\n\nThe number *shown*, e.g. 1.5.";
  assert.deepEqual(hoverContents(text), [
    { value: "```kotlin\nLedDisplay.Small\nvar Setting: Double (write-only)\n```" },
    { value: "The number \\*shown\\*, e\\.g\\. 1\\.5\\." },
  ]);
  assert.deepEqual(hoverContents("val a: Int"), [{ value: "```kotlin\nval a: Int\n```" }]);
  assert.deepEqual(hoverContents(""), []);
});

test("KDoc keeps its line breaks", () => {
  assert.equal(markdownText("a\nb"), "a  \nb");
});

test("LSP contents read as plain text", () => {
  assert.equal(plainOf({ kind: "plaintext", value: "val a: Int" }), "val a: Int");
  assert.equal(plainOf("text"), "text");
  assert.equal(plainOf(["a", { language: "kotlin", value: "b" }]), "a\n\nb");
  assert.equal(plainOf(null), "");
});
