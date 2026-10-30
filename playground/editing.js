// The editor's view of the compiler and the language server: where a compile's problems go, and
// how the daemon's LSP answers (textDocument/completion, hover, signatureHelp) read in Monaco.
//
// These functions only turn JSON into plain objects; index.html hands those to Monaco, and
// editing.test.mjs runs them under `node --test`. Positions here are Monaco's: 1-based lines and
// columns, columns counted in UTF-16 code units, as the compiler counts them too.

/** A byte-order mark, which the compiler skips before counting a program's first columns. */
const BOM = String.fromCharCode(0xfeff);

/**
 * The problems in a compile response (the daemon's JSON; see daemon/.../Daemon.kt), for `text`,
 * the source compiled: a failure's errors (`error.errors`, or `error` itself if it has none, as an
 * internal compiler error has none), or a success's warnings. Each is { severity: "error" or
 * "warning", message, startLine, startColumn, endLine, endColumn }.
 *
 * The compiler gives only where a problem starts, so the range covers the identifier there, or the
 * one character there if it is not part of one, as the daemon's LSP diagnostics do (Diagnostics.kt).
 * A problem with no position (line 0) is put at the start of the program.
 */
function problemsOf(res, text) {
  const error = res && res.error && typeof res.error === "object" ? res.error : null;
  const list = error ? (Array.isArray(error.errors) && error.errors.length ? error.errors : [error])
    : (res && Array.isArray(res.warnings) ? res.warnings : []);
  const lines = text.split("\n");
  return list.map((p) => {
    const line = p.line > 0 ? Math.min(p.line, lines.length) : 1;
    const lineText = lines[line - 1].replace(/\r$/, "");
    // The compiler drops a leading byte-order mark before counting columns.
    const bom = line === 1 && lineText.startsWith(BOM) ? 1 : 0;
    const start = p.line > 0 ? Math.max(0, Math.min((p.column || 1) - 1 + bom, lineText.length)) : 0;
    let end = start;
    const ident = /[\p{L}\p{N}_$]/u;
    if (start < lineText.length) {
      if (ident.test(lineText[start])) {
        while (end < lineText.length && ident.test(lineText[end])) end++;
      } else end = start + 1;
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
