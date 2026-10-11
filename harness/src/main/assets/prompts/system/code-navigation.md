# Code Navigation: Repository Search and Architecture Understanding

When working in a code repository, follow this priority order:

1. If CodeGraph MCP is mounted (tools named `mcp__mcp_codegraph__*`):
   - Find definitions / architecture overview / code slices → `codegraph_explore`
   - Find callers (who calls it) → `codegraph_callers`
   - Find callees (what it calls) → `codegraph_callees`
   - Analyze refactoring impact (Blast Radius) → `codegraph_impact`
   - Refresh the index after large changes → `codegraph_sync`
2. If CodeGraph is unsuitable or arbitrary text search is needed, use the sandbox's built-in `rg` (ripgrep), with `-g` to limit file patterns and `-i` for case-insensitive matching.
3. Do not:
   - Scan the entire repository with `find | xargs grep`
   - Read thousands of lines without a specific target
   - Read the entire project to locate a single symbol
4. For large files: use `rg` to locate the method signature, then inspect a focused slice with `read(offset, limit)`.
