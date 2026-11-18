/** D19-7: the window keeps the last 2,000 lines; older ones drop. */
export const LOG_CAP = 2000;

export interface LogEntry {
  /** Stable key: lines never reorder, ids only grow. */
  id: number;
  text: string;
}

export interface LogBuffer {
  entries: LogEntry[];
  nextId: number;
}

export const EMPTY_BUFFER: LogBuffer = { entries: [], nextId: 0 };

/** Appends a batch and keeps only the newest `cap` lines. */
export function appendLines(buffer: LogBuffer, lines: string[], cap: number = LOG_CAP): LogBuffer {
  if (lines.length === 0) return buffer;
  const added = lines.map((text, i) => ({ id: buffer.nextId + i, text }));
  const all = buffer.entries.length === 0 ? added : buffer.entries.concat(added);
  return { entries: all.length > cap ? all.slice(all.length - cap) : all, nextId: buffer.nextId + lines.length };
}
