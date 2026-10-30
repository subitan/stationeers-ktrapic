// The editor's view of the compiler and the language server: where a compile's problems go, and
// how the daemon's LSP answers (textDocument/completion, hover, signatureHelp) read in Monaco.
//
// These functions only turn JSON into plain objects; index.html hands those to Monaco, and
// editing.test.mjs runs them under `node --test`. Positions here are Monaco's: 1-based lines and
// columns, columns counted in UTF-16 code units, as the compiler counts them too.

/**
 * The characters Java's Character.isJavaIdentifierPart accepts, which Diagnostics.kt uses: letters,
 * digits and letter numbers, combining marks, connectors such as `_`, currency signs such as `$`,
 * and the characters an identifier ignores (format characters and most control characters).
 */
const IDENTIFIER_PART = /[\p{L}\p{Nd}\p{Nl}\p{Mn}\p{Mc}\p{Pc}\p{Sc}\p{Cf}\u0000-\u0008\u000e-\u001b\u007f-\u009f]/u;

/**
 * The problems in a compile response (the daemon's JSON; see daemon/.../Daemon.kt), for `text`,
 * the source compiled: a failure's errors (`error.errors`, or `error` itself if it has none, as an
 * internal compiler error has none), or a success's warnings. Each is { severity: "error" or
 * "warning", message, startLine, startColumn, endLine, endColumn }.
 *
 * The compiler gives only where a problem starts, so the range covers the identifier there, or the
 * one character there if it is not part of one, as the daemon's LSP diagnostics do (Diagnostics.kt),
 * reading by code point, so a character outside the BMP is one character, not two halves.
 * A problem with no position (line 0) is put at the start of the program.
 *
 * The ranges are for Monaco's model, which never starts with a byte-order mark; the compiler
 * doesn't count one either, so a leading mark in `text` is dropped before the columns are read.
 */
function problemsOf(res, text) {
  const error = res && res.error && typeof res.error === "object" ? res.error : null;
  const list = error ? (Array.isArray(error.errors) && error.errors.length ? error.errors : [error])
    : (res && Array.isArray(res.warnings) ? res.warnings : []);
  const lines = text.replace(/^﻿/, "").split("\n");
  return list.map((p) => {
    const line = p.line > 0 ? Math.min(p.line, lines.length) : 1;
    const lineText = lines[line - 1].replace(/\r$/, "");
    const start = p.line > 0 ? Math.max(0, Math.min((p.column || 1) - 1, lineText.length)) : 0;
    // The character at index i, a whole code point (a lone surrogate stays one unit).
    const charAt = (i) => String.fromCodePoint(lineText.codePointAt(i));
    let end = start;
    if (start < lineText.length) {
      if (IDENTIFIER_PART.test(charAt(start))) {
        while (end < lineText.length && IDENTIFIER_PART.test(charAt(end))) end += charAt(end).length;
      } else end = start + charAt(start).length;
    }
    return {
      severity: error ? "error" : "warning",
      message: String(p.description ?? "error"),
      startLine: line, startColumn: start + 1, endLine: line, endColumn: end + 1,
    };
  });
}

/**
 * The name of Monaco's CompletionItemKind for an LSP CompletionItemKind, as the daemon sends them
 * (LspServer.kindOf); "Text" for any other.
 */
function completionKind(lspKind) {
  return {
    3: "Function", 6: "Variable", 7: "Class", 8: "Interface", 9: "Module", 10: "Property",
    13: "Enum", 14: "Keyword", 20: "EnumMember",
  }[lspKind] ?? "Text";
}

/** Escapes text for Markdown, so a KDoc's `*`, `_` and `<` show as written. */
function markdownText(text) {
  return text.replace(/[\\`*_{}\[\]()#+\-.!|<>~]/g, "\\$&").replace(/\n/g, "  \n");
}

/**
 * A hover's plain text (Hover.text in the compiler: the class, the declaration and its access, then
 * a blank line and the KDoc) as Monaco hover contents: the declaration as Kotlin code, then the
 * KDoc. Empty for no text.
 */
function hoverContents(text) {
  if (!text) return [];
  const blank = text.indexOf("\n\n");
  const head = blank < 0 ? text : text.slice(0, blank);
  const doc = blank < 0 ? "" : text.slice(blank + 2).trim();
  const contents = [{ value: "```kotlin\n" + head + "\n```" }];
  if (doc) contents.push({ value: markdownText(doc) });
  return contents;
}

/** The plain text of an LSP hover's or signature's contents: a string, MarkupContent or MarkedString[]. */
function plainOf(contents) {
  if (contents == null) return "";
  if (typeof contents === "string") return contents;
  if (Array.isArray(contents)) return contents.map(plainOf).filter(Boolean).join("\n\n");
  return String(contents.value ?? "");
}

if (typeof module === "object" && module.exports) {
  module.exports = { problemsOf, completionKind, markdownText, hoverContents, plainOf };
}
