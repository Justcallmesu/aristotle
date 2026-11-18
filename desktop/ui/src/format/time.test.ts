import { createTranslator } from "../i18n";
import { clockText, dmyText, minutesSince, parseTime, relativeText, relativeTime, dayMonthYear } from "./time";

const now = new Date(2026, 10, 18, 14, 30, 0);

describe("relativeTime", () => {
  it("says now under a minute, also for a clock a bit ahead", () => {
    expect(relativeTime(new Date(2026, 10, 18, 14, 29, 30), now, "en")).toEqual({ kind: "now" });
    expect(relativeTime(new Date(2026, 10, 18, 14, 31, 0), now, "en")).toEqual({ kind: "now" });
  });
  it("counts minutes under an hour", () => {
    expect(relativeTime(new Date(2026, 10, 18, 14, 0, 0), now, "en")).toEqual({ kind: "minutes", n: 30 });
    expect(relativeTime(new Date(2026, 10, 18, 13, 31, 0), now, "en")).toEqual({ kind: "minutes", n: 59 });
  });
  it("uses Today HH:MM on the same calendar day", () => {
    expect(relativeTime(new Date(2026, 10, 18, 9, 5, 0), now, "en")).toEqual({ kind: "today", time: "09:05" });
  });
  it("writes the date for an earlier day, Mon D in EN and D Mon in ID", () => {
    const earlier = new Date(2026, 4, 3, 7, 8, 0);
    expect(relativeTime(earlier, now, "en")).toEqual({ kind: "date", text: "May 3 07:08" });
    expect(relativeTime(earlier, now, "id")).toEqual({ kind: "date", text: "3 Mei 07:08" });
    expect(relativeTime(new Date(2026, 10, 17, 23, 50, 0), now, "id")).toEqual({ kind: "date", text: "17 Nov 23:50" });
  });
  it("formats the paired date with the year", () => {
    expect(dayMonthYear(new Date(2026, 7, 9), "en")).toBe("Aug 9, 2026");
    expect(dayMonthYear(new Date(2026, 7, 9), "id")).toBe("9 Agu 2026");
  });
});

describe("shown time text (design.md 4.7, locale-aware)", () => {
  const en = createTranslator("en");
  const id = createTranslator("id");
  const at = (d: Date) => d.toISOString();

  it("relativeText: now, minutes, today, date; — for a non-time", () => {
    expect(relativeText(en, at(new Date(2026, 10, 18, 14, 29, 30)), now)).toBe("just now");
    expect(relativeText(en, at(new Date(2026, 10, 18, 14, 0, 0)), now)).toBe("30 min ago");
    expect(relativeText(en, at(new Date(2026, 10, 18, 8, 5, 0)), now)).toBe("Today 08:05");
    expect(relativeText(en, at(new Date(2026, 10, 17, 22, 0, 0)), now)).toBe("Nov 17 22:00");
    expect(relativeText(id, at(new Date(2026, 4, 3, 7, 9, 0)), now)).toBe("3 Mei 07:09");
    expect(relativeText(id, at(new Date(2026, 10, 18, 14, 0, 0)), now)).toBe("30 menit lalu");
    expect(relativeText(en, "garbage", now)).toBe("—");
    expect(relativeText(en, null, now)).toBe("—");
  });

  it("clockText and dmyText", () => {
    expect(clockText(at(new Date(2026, 10, 18, 9, 5, 0)))).toBe("09:05");
    expect(clockText("garbage")).toBe("—");
    expect(dmyText(en, at(new Date(2026, 9, 3, 8, 0)))).toBe("Oct 3, 2026");
    expect(dmyText(id, at(new Date(2026, 9, 3, 8, 0)))).toBe("3 Okt 2026");
    expect(dmyText(en, "")).toBe("—");
  });

  it("parseTime and minutesSince", () => {
    expect(parseTime("nope")).toBeNull();
    expect(minutesSince(at(new Date(2026, 10, 18, 14, 0, 0)), now.getTime())).toBe(30);
    expect(minutesSince(at(new Date(2026, 10, 18, 15, 0, 0)), now.getTime())).toBe(0);
    expect(minutesSince("nope", now.getTime())).toBeNull();
  });
});
