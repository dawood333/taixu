### Android Reverse Engineering and Code Audit Rules
- Current project type: Android APK reverse engineering; root: `{{WORKSPACE_PATH}}`; directory marker: `{{MARKER_TEXT}}`.
- Core rule: the original APK and `unpacked/` are analysis inputs. First read `apk-info.properties` and `REVERSE.md`; never overwrite the original APK. Follow the standardized workflow: **plan first, understand structure, prioritize source code, search rapidly, and trace reflection/call graphs**.

#### 1. Plan first (mandatory)
- Reverse-engineering audits are complex, multi-stage tasks. Follow the `workflow` rule block and create an execution board in the first tool round. Do not explore blindly without a plan. Suggested stages:
  1. Understand the structure (read the Manifest and identify the main package and key entry components).
  2. Obtain source code (decompile Java with JADX or extract DEX).
  3. Search for key indicators and keywords precisely with `rg`.
  4. Trace key classes and call chains in depth (cross-check Java/Smali and analyze reflection).
  5. Summarize audit findings and evidence for the relevant business logic.
- After each stage, advance the board with `plan(action="advance")`.

#### 2. APK structure and architecture
- Read `apk-info.properties` and `AndroidManifest.xml` first.
- Extract key details: package name, entry Activity, important Services/Receivers, custom Application classes (often containing hotfix, packing, or initialization logic), and permission declarations.
- When encountering R-class resource references (such as `@string/login_sms_title` or `0x7f...`), use `rg` to search `res/values/strings.xml` or mappings in `R.smali`.

#### 3. Decompilation and toolchain selection
- **Prefer one-step decompilation into a Java source project:**
  `jadx -d out/java <APK/DEX-file-path>`
  Java source has complete control flow and type structure and is often over 10 times faster to read than Smali. After decompilation, search the code under `out/java/sources/`.
- **Resource and Smali edits:** unpack with `apktool d <APK-path> -o unpacked/`; after editing, rebuild with `apktool b`.
- **DEX to JAR:** if another Java bytecode analysis tool is needed, run `d2j-dex2jar <APK/DEX-path> -o out.jar`.

#### 4. Fast search and code knowledge graphs (Ripgrep & CodeGraph)
- Follow the `code-navigation` rule block for code search and call-chain tracing: prefer CodeGraph MCP (explore/callers/callees/impact), use `rg` for text search, and never scan with `find | xargs grep`.
- Common reverse-engineering searches:
  - Search class names, method names, or constants: `rg "quickPhoneLogin" out/java/`
  - Search Java files only: `rg -t java "isNewUser" out/java/`
  - Search Smali files only: `rg -g "*.smali" "invoke-static.*login" unpacked/`
  - Case-insensitive regex search: `rg -i "sms.*code" out/java/`

#### 5. Plugin architecture and reflection call-graph analysis
- For APKs with plugin/component architectures or extensive reflection, such as Battle of Balls:
  - **Identify reflection patterns:** search for `Class.forName`, `getMethod`, `invoke`, `ReflectUtils`, and wrapper classes named `Reflect`.
  - **Trace class-name literals:** reflection often uses fully qualified class-name strings. Use `rg` to search for the target interface/class path, such as `"com.ztgame.mobileappsdk"`.
  - **Dynamic plugin loading:** inspect `DexClassLoader`, `PathClassLoader`, asset/ZIP extraction paths, and dynamic `.so` loading; trace secondary DEX files or plugin APKs.

#### 6. Large files and precise Smali slices
- Read large files in focused slices according to `code-navigation`; never read an entire large file without a target. For Smali, first run `rg -n '^\.method' Path/To/Class.smali` to list method signatures, then use `read(offset, limit)` to inspect the relevant lines.

#### 7. Scratchpad and persistent clues
- Save important class names, call chains, intermediate hypotheses, and decompilation directories as soon as they are discovered using `scratchpad(action="save", key="findings", value=...)` to avoid losing them during long sessions.
