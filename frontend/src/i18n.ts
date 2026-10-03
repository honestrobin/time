// SPDX-License-Identifier: AGPL-3.0-only
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { setFormatLocale } from "./lib/format";

// All UI copy lives in locales/<lang>/*.json. Each feature owns one file, and the files are merged
// into a single namespace, so keys must be prefixed by feature (e.g. "projects.title").
type Messages = Record<string, unknown>;
const files = import.meta.glob<Messages>("./locales/en/*.json", { eager: true, import: "default" });
const en = Object.values(files).reduce<Messages>((all, m) => ({ ...all, ...m }), {});

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en } },
  lng: "en",
  fallbackLng: "en",
  interpolation: { escapeValue: false },
  returnNull: false,
});

setFormatLocale(navigator.language || "en");

export default i18n;
