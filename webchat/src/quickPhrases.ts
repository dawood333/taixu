import type { QuickPhrase } from "./types";

/** Filter disabled phrases and sort stably by sortOrder, tolerating missing or malformed server fields. */
export function normalizeQuickPhrases(raw: unknown): QuickPhrase[] {
  if (!Array.isArray(raw)) return [];
  const phrases: Array<QuickPhrase & { sortOrder: number }> = [];
  raw.forEach((item, index) => {
    if (typeof item !== "object" || item === null) return;
    const record = item as Record<string, unknown>;
    if (record.isEnabled === false) return;
    const content = String(record.content ?? "").trim();
    if (!content) return;
    const order = Number(record.sortOrder);
    phrases.push({
      id: String(record.id ?? `phrase-${index}`),
      title: String(record.title ?? "").trim() || content.slice(0, 12),
      content,
      description: String(record.description ?? "").trim() || undefined,
      sortOrder: Number.isFinite(order) ? order : index,
    });
  });
  return phrases
    .sort((left, right) => left.sortOrder - right.sortOrder)
    .map(({ sortOrder: _sortOrder, ...phrase }) => phrase);
}
