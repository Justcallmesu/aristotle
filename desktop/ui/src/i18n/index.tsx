import { createContext, useContext, useEffect, useMemo, type ReactNode } from "react";
import en from "./en.json";
import id from "./id.json";

export type Locale = "en" | "id";
export type MessageKey = keyof typeof en;
export type Params = Record<string, string | number>;

const catalogs: Record<Locale, Record<string, string>> = { en, id };

/** Indonesian when the system language starts with `id` (P10/D16), else English. */
export function detectLocale(language: string | undefined): Locale {
  return language?.toLowerCase().startsWith("id") ? "id" : "en";
}

export function interpolate(template: string, params?: Params): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in params ? String(params[name]) : whole,
  );
}

export interface Translator {
  locale: Locale;
  t: (key: MessageKey, params?: Params) => string;
  /** True when the catalog has this key (state words that may come back raw, design.md 4.1). */
  has: (key: string) => boolean;
  /** `<prefix><value>` from the catalog, else the raw value. */
  word: (prefix: string, value: string, params?: Params) => string;
}

export function createTranslator(locale: Locale): Translator {
  const catalog = catalogs[locale];
  const has = (key: string) => key in catalog;
  return {
    locale,
    has,
    t: (key, params) => interpolate(catalog[key] ?? key, params),
    word: (prefix, value, params) => {
      const key = `${prefix}${value}`;
      return has(key) ? interpolate(catalog[key], params) : value;
    },
  };
}

const I18nContext = createContext<Translator>(createTranslator("en"));

export function I18nProvider({ locale, children }: { locale?: Locale; children: ReactNode }) {
  const resolved = locale ?? detectLocale(navigator.language);
  const translator = useMemo(() => createTranslator(resolved), [resolved]);
  useEffect(() => {
    document.documentElement.lang = resolved;
  }, [resolved]);
  return <I18nContext.Provider value={translator}>{children}</I18nContext.Provider>;
}

export function useI18n(): Translator {
  return useContext(I18nContext);
}
