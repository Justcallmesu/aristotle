import { memo } from "react";

export type LineLevel = "error" | "warn" | null;

export interface ParsedLine {
  /** `Mon DD HH:MM:SS host unit[pid]:` when the line starts like a journal line. */
  prefix: string;
  rest: string;
  level: LineLevel;
}

const PREFIX = /^[A-Z][a-z]{2} [ \d]\d \d\d:\d\d:\d\d \S+ \S+?\[\d+\]:/;
const LEVEL = /\blevel=([A-Za-z]+)/;

/** design.md 3.7: no parsing beyond the journal prefix and the `level=` token. */
export function parseLine(text: string): ParsedLine {
  const prefix = PREFIX.exec(text)?.[0] ?? "";
  const rest = text.slice(prefix.length);
  const word = LEVEL.exec(rest)?.[1]?.toUpperCase();
  const level: LineLevel = word === "ERROR" ? "error" : word === "WARN" || word === "WARNING" ? "warn" : null;
  return { prefix, rest, level };
}

/** Splits `rest` so the `level=X` token can be bold; everything stays a text node (never HTML). */
function restParts(rest: string): { text: string; level: boolean }[] {
  const match = LEVEL.exec(rest);
  if (!match) return [{ text: rest, level: false }];
  const start = match.index;
  const end = start + match[0].length;
  return [
    { text: rest.slice(0, start), level: false },
    { text: match[0], level: true },
    { text: rest.slice(end), level: false },
  ];
}

export const LogLine = memo(function LogLine({ text }: { text: string }) {
  const { prefix, rest, level } = parseLine(text);
  return (
    <div className={level ? `log-line log-${level}` : "log-line"}>
      {prefix ? <span className="log-prefix">{prefix}</span> : null}
      {restParts(rest).map((part, i) =>
        part.level ? (
          <strong key={i} className="log-level">
            {part.text}
          </strong>
        ) : (
          part.text
        ),
      )}
    </div>
  );
});
