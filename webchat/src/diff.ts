export interface DiffRow {
  kind: "add" | "del" | "same";
  text: string;
}

const MAX_DIFF_LINES = 400;

/**
 * Line-based LCS diff. File-edit blocks usually contain only dozens of lines, so O(n·m) is sufficient;
 * if either side exceeds 400 lines, fall back to showing all lines as removed/added with an omission marker to avoid freezing on large files.
 */
export function diffLines(before: string, after: string): DiffRow[] {
  const left = before.split("\n");
  const right = after.split("\n");
  if (left.length > MAX_DIFF_LINES || right.length > MAX_DIFF_LINES) {
    const rows: DiffRow[] = [];
    for (const text of left.slice(0, MAX_DIFF_LINES)) rows.push({ kind: "del", text });
    for (const text of right.slice(0, MAX_DIFF_LINES)) rows.push({ kind: "add", text });
    rows.push({ kind: "same", text: `… Content too long; showing only the first ${MAX_DIFF_LINES} lines …` });
    return rows;
  }

  const width = right.length;
  // lcs[i][j] = length of the longest common subsequence of left[i..] and right[j..] (flattened to one rolling dimension).
  const lcs = new Uint32Array((left.length + 1) * (width + 1));
  for (let i = left.length - 1; i >= 0; i -= 1) {
    for (let j = width - 1; j >= 0; j -= 1) {
      lcs[i * (width + 1) + j] = left[i] === right[j]
        ? lcs[(i + 1) * (width + 1) + j + 1] + 1
        : Math.max(lcs[(i + 1) * (width + 1) + j], lcs[i * (width + 1) + j + 1]);
    }
  }

  const result: DiffRow[] = [];
  let i = 0;
  let j = 0;
  while (i < left.length && j < width) {
    if (left[i] === right[j]) {
      result.push({ kind: "same", text: left[i] });
      i += 1;
      j += 1;
    } else if (lcs[(i + 1) * (width + 1) + j] >= lcs[i * (width + 1) + j + 1]) {
      result.push({ kind: "del", text: left[i] });
      i += 1;
    } else {
      result.push({ kind: "add", text: right[j] });
      j += 1;
    }
  }
  while (i < left.length) {
    result.push({ kind: "del", text: left[i] });
    i += 1;
  }
  while (j < width) {
    result.push({ kind: "add", text: right[j] });
    j += 1;
  }
  return result;
}
