import { dayMonthYear, relativeTime } from "./time";

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
