import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import en from "./en.json";
import id from "./id.json";
import { createTranslator, detectLocale, interpolate } from "./index";

function params(text: string): string[] {
  return (text.match(/\{\w+\}/g) ?? []).sort();
}

describe("message files", () => {
  it("have the same keys in en and id", () => {
    expect(Object.keys(id).sort()).toEqual(Object.keys(en).sort());
  });

  it("have no empty text", () => {
    for (const [lang, catalog] of Object.entries({ en, id })) {
      for (const [key, text] of Object.entries(catalog)) {
        expect(text, `${lang}.${key}`).not.toBe("");
      }
    }
  });

  it("use the same {params} in both languages", () => {
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(params(id[key]), key).toEqual(params(en[key]));
    }
  });

  it("carry no Rust-side keys (tray menu and notifications, P10)", () => {
    for (const key of Object.keys(en)) {
      expect(key).not.toMatch(/^(m_|tip_|n_)|^now_t$/);
    }
  });

  it("define every key the source asks for with t('...')", () => {
    const missing: string[] = [];
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) walk(path);
        else if (/\.tsx?$/.test(name) && !name.includes(".test.")) {
          const source = readFileSync(path, "utf8");
          for (const m of source.matchAll(/\bt\(\s*"([\w-]+)"/g)) {
            if (!(m[1] in en)) missing.push(`${path}: ${m[1]}`);
          }
        }
      }
    };
    walk(join(__dirname, ".."));
    expect(missing).toEqual([]);
  });
});

describe("translator", () => {
  it("picks Indonesian for id-* system languages, else English", () => {
    expect(detectLocale("id-ID")).toBe("id");
    expect(detectLocale("id")).toBe("id");
    expect(detectLocale("en-US")).toBe("en");
    expect(detectLocale(undefined)).toBe("en");
  });

  it("fills {params} and leaves unknown ones", () => {
    expect(interpolate("a {x} b {y}", { x: 1 })).toBe("a 1 b {y}");
  });

  it("returns the raw word when the catalog has no key for a systemctl word", () => {
    const tr = createTranslator("en");
    expect(tr.word("svc_", "active")).toBe("active");
    expect(tr.word("svc_", "activating")).toBe("activating");
    expect(createTranslator("id").word("svc_", "active")).toBe("aktif");
  });
});
