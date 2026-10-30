// File annotations: the program says how it compiles (docs/LANGUAGE.md).
//
// These functions only read the program's text and say what to change, as an edit
// { start, end, text } that replaces [start, end) with text. index.html applies the edits to the
// editor; annotations.test.mjs runs them under `node --test`.

/** The end of the block comment that starts at p. Kotlin's block comments nest, so this counts them. */
function skipBlockComment(text, p) {
  let depth = 0;
  while (p < text.length) {
    if (text.startsWith("/*", p)) { depth++; p += 2; }
    else if (text.startsWith("*/", p)) { p += 2; if (--depth === 0) return p; }
    else p++;
  }
  return text.length;
}

/** The first offset from p that is not whitespace or a comment. */
function skipTrivia(text, p) {
  for (;;) {
    while (p < text.length && /\s/.test(text[p])) p++;
    if (text.startsWith("//", p)) {
      const nl = text.indexOf("\n", p);
      p = nl < 0 ? text.length : nl;
    } else if (text.startsWith("/*", p)) p = skipBlockComment(text, p);
    else return p;
  }
}

/**
 * The end of the `${...}` template expression that starts at p: its matching `}`, past strings,
 * characters and comments inside it, which may hold braces of their own.
 */
function skipTemplate(text, p) {
  let depth = 0;
  while (p < text.length) {
    const c = text[p];
    if (c === '"' || c === "'") p = skipLiteral(text, p);
    else if (text.startsWith("//", p) || text.startsWith("/*", p)) p = skipTrivia(text, p);
    else {
      p++;
      if (c === "{") depth++;
      else if (c === "}" && --depth === 0) return p;
    }
  }
  return text.length;
}

/**
 * The end of the string or character literal that starts at p. A string's `${...}` templates are
 * code, so a quote inside one does not end the string.
 */
function skipLiteral(text, p) {
  if (text.startsWith('"""', p)) {
    p += 3;
    while (p < text.length) {
      if (text.startsWith('"""', p)) {
        p += 3;
        while (text[p] === '"') p++; // A raw string may end in quotes of its own: `"""a""""`.
        return p;
      }
      p = text.startsWith("${", p) ? skipTemplate(text, p + 1) : p + 1;
    }
    return text.length;
  }
  const quote = text[p++];
  while (p < text.length && text[p] !== quote && text[p] !== "\n") {
    if (text[p] === "\\") p += 2;
    else if (quote === '"' && text.startsWith("${", p)) p = skipTemplate(text, p + 1);
    else p++;
  }
  return Math.min(p + 1, text.length);
}

/** The end of the parenthesised arguments that start at p, past strings and comments in them. */
function skipArguments(text, p) {
  let depth = 0;
  while (p < text.length) {
    const c = text[p];
    if (c === '"' || c === "'") p = skipLiteral(text, p);
    else if (text.startsWith("//", p) || text.startsWith("/*", p)) p = skipTrivia(text, p);
    else {
      p++;
      if (c === "(") depth++;
      else if (c === ")" && --depth === 0) return p;
    }
  }
  return text.length;
}

/** The annotation at p, a dotted name and any arguments, as { name, start, end }; or null. */
function readAnnotation(text, p) {
  const m = /(?:[A-Za-z_]\w*|`[^`\n]+`)(?:\.(?:[A-Za-z_]\w*|`[^`\n]+`))*/y;
  m.lastIndex = p;
  const name = m.exec(text);
  if (!name) return null;
  const end = p + name[0].length;
  return { name: name[0], start: p, end: text[end] === "(" ? skipArguments(text, end) : end };
}

/**
 * Reads the program's header: a shebang, then the `@file:` annotations before its first other
 * token, past whitespace and comments. Returns `found`, each annotation as { name, start, end,
 * group }, where [start, end) is its text from `@file:` (or, in `@file:[A B]`, from its name)
 * through any arguments and group is that `@file:[...]` as { start, end, items }; `top`, where
 * the program starts after any shebang; `last`, the end of the last annotation, or null if there
 * is none; and `stop`, where the first token that is not header starts.
 */
function scanHeader(text) {
  const found = [];
  let pos = 0;
  if (text.startsWith("#!")) {
    const nl = text.indexOf("\n");
    pos = nl < 0 ? text.length : nl + 1;
  }
  const top = pos;
  let last = null;
  const target = /@file\s*:\s*/y;
  for (;;) {
    pos = skipTrivia(text, pos);
    target.lastIndex = pos;
    const at = target.exec(text);
    if (!at) break;
    let p = pos + at[0].length;
    if (text[p] === "[") {
      const group = { start: pos, end: -1, items: [] };
      p++;
      for (;;) {
        p = skipTrivia(text, p);
        if (p >= text.length) return { found, top, last, stop: pos };
        if (text[p] === "]") break;
        const a = readAnnotation(text, p);
        if (!a) return { found, top, last, stop: pos };
        a.group = group;
        group.items.push(a);
        p = a.end;
      }
      group.end = p = p + 1;
      found.push(...group.items);
    } else {
      const a = readAnnotation(text, p);
      if (!a) break;
      a.start = pos;
      found.push(a);
      p = a.end;
    }
    pos = last = p;
  }
  return { found, top, last, stop: pos };
}

/**
 * `@file:name` (or `@file:ktrapic.name`) in the program's header, as scanHeader gives it, or null.
 * Backticks don't change a name: `` @file:`Compact` `` is Compact.
 */
function findAnnotation(header, name) {
  return (
    header.found.find((a) => {
      const plain = a.name.replace(/`/g, "");
      return plain === name || plain === `ktrapic.${name}`;
    }) ?? null
  );
}

/**
 * The edit that removes [start, end) of the program: with its line(s) if nothing else is left on
 * them, or else with the spaces after it (or, when none follow, the spaces before it).
 */
function cutEdit(text, start, end) {
  const lineStart = text.lastIndexOf("\n", start - 1) + 1;
  const nl = text.indexOf("\n", end);
  const lineEnd = nl < 0 ? text.length : nl;
  if ((text.slice(lineStart, start) + text.slice(end, lineEnd)).trim() === "") {
    return { start: lineStart, end: Math.min(lineEnd + 1, text.length), text: "" };
  }
  let e = end;
  while (e < lineEnd && /[ \t]/.test(text[e])) e++;
  let s = start;
  if (e === end) while (s > lineStart && /[ \t]/.test(text[s - 1])) s--;
  return { start: s, end: e, text: "" };
}

/**
 * The edit that adds `@file:name` on its own line: on the line after the program's last file
 * annotation, so a comment after that one stays with it, or else as the program's first line after
 * any shebang. Null if the program already has it.
 */
function additionEdit(text, name) {
  const header = scanHeader(text);
  if (findAnnotation(header, name)) return null;
  const line = `@file:${name}`;
  const insert = (at, s) => ({ start: at, end: at, text: s });
  if (header.last === null) {
    if (header.top > 0 && text[header.top - 1] !== "\n") return insert(header.top, `\n${line}`);
    return insert(header.top, `${line}\n`);
  }
  // The first line break after the last annotation that is not inside a comment.
  let p = header.last;
  while (p < header.stop && text[p] !== "\n") {
    if (text.startsWith("//", p)) {
      const nl = text.indexOf("\n", p);
      p = nl < 0 ? text.length : nl;
    } else if (text.startsWith("/*", p)) p = skipBlockComment(text, p);
    else p++;
  }
  if (p < header.stop) return insert(p + 1, `${line}\n`);
  if (header.stop === text.length) return insert(text.length, `\n${line}`);
  return insert(header.last, `\n${line}`);
}

/**
 * The edit that removes `@file:name` from the program's header, or null if it has none. In
 * `@file:[...]` only its name goes, unless it is the last name there, when the brackets go too.
 */
function removalEdit(text, name) {
  const found = findAnnotation(scanHeader(text), name);
  if (!found) return null;
  const group = found.group;
  if (group && group.items.length === 1) return cutEdit(text, group.start, group.end);
  return cutEdit(text, found.start, found.end);
}

if (typeof module === "object" && module.exports) {
  module.exports = { scanHeader, findAnnotation, additionEdit, removalEdit };
}
