import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { "react-hooks": reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // Views import only from src/ipc: nothing talks to Tauri directly (P4).
      "no-restricted-imports": [
        "error",
        { patterns: [{ group: ["@tauri-apps/*"], message: "Import from src/ipc instead." }] },
      ],
    },
  },
  {
    files: ["src/ipc/**", "src/test/**", "**/*.test.{ts,tsx}"],
    rules: { "no-restricted-imports": "off" },
  },
);
