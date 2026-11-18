import type { Locale, Translator } from "../i18n";
import { DASH } from "./dash";

const MON_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MON_ID = ["Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des"];

/** The one place time text is written (design.md 4.7): relative, clock and `dmy`, in the window's locale. */
export function parseTime(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** `HH:MM`, 24 h, local. */
export function clock(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** `Mon D` (EN) or `D Mon` (ID). */
export function dayMonth(date: Date, locale: Locale): string {
  const month = (locale === "id" ? MON_ID : MON_EN)[date.getMonth()];
  return locale === "id" ? `${date.getDate()} ${month}` : `${month} ${date.getDate()}`;
}

/** The `dmy` of the Paired column: day, month and year. */
export function dayMonthYear(date: Date, locale: Locale): string {
  const year = date.getFullYear();
  return locale === "id" ? `${dayMonth(date, locale)} ${year}` : `${dayMonth(date, locale)}, ${year}`;
}

export type RelativeTime =
  | { kind: "now" }
  | { kind: "minutes"; n: number }
  | { kind: "today"; time: string }
  | { kind: "date"; text: string };

/** design.md 4.7: now < 1 min; minutes < 60; same calendar day `today HH:MM`; else `<date> HH:MM`. */
export function relativeTime(date: Date, now: Date, locale: Locale): RelativeTime {
  const minutes = Math.floor((now.getTime() - date.getTime()) / 60000);
  if (minutes < 1) return { kind: "now" };
  if (minutes < 60) return { kind: "minutes", n: minutes };
  const sameDay =
    date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
  if (sameDay) return { kind: "today", time: clock(date) };
  return { kind: "date", text: `${dayMonth(date, locale)} ${clock(date)}` };
}

/** The relative time as shown text; `—` when `iso` is not a time. */
export function relativeText(tr: Translator, iso: string | null | undefined, now: Date = new Date()): string {
  const date = parseTime(iso);
  if (!date) return DASH;
  const rel = relativeTime(date, now, tr.locale);
  switch (rel.kind) {
    case "now":
      return tr.t("now");
    case "minutes":
      return tr.t("ago_min", { n: rel.n });
    case "today":
      return tr.t("today", { t: rel.time });
    case "date":
      return rel.text;
  }
}

/** `HH:MM` of an ISO time; `—` when it is not a time. */
export function clockText(iso: string | null | undefined): string {
  const date = parseTime(iso);
  return date ? clock(date) : DASH;
}

/** The Paired column text; `—` when it is not a time. */
export function dmyText(tr: Translator, iso: string | null | undefined): string {
  const date = parseTime(iso);
  return date ? dayMonthYear(date, tr.locale) : DASH;
}

/** Whole minutes since `iso`, never negative; null when it is not a time. */
export function minutesSince(iso: string, now: number): number | null {
  const date = parseTime(iso);
  return date ? Math.max(0, Math.floor((now - date.getTime()) / 60_000)) : null;
}
