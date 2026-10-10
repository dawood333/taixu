# TaiXu Portable Project Template Specification (v1)

A TaiXu template is a ZIP package that can be imported, exported, and shared. In the Android app, use **Workshop → More → Template Manager** to import a ZIP or export a built-in template as a starting example.

## 1. Directory structure

A ZIP may contain the manifest at its root or contain a single template directory. A typical package is:

```text
my-template.zip
├── template.json
├── preview.png                  # Optional, 270×270
├── template-hooks/              # Optional
│   ├── before-create.sh
│   └── after-create.sh
└── files/
    ├── README.md.template
    └── app/
```

The importer validates ZIP paths and limits, then stores the template in the app's private template directory. The package must not contain absolute paths, Windows drive-letter paths, or paths escaping the destination through `..`.

## 2. Manifest

A minimal example:

```json
{
  "schemaVersion": 1,
  "id": "sample-project",
  "name": "Sample Project",
  "description": "A portable project template",
  "projectType": "GENERAL",
  "category": "General",
  "sortOrder": 100,
  "previewImage": "preview.png",
  "variables": [
    {
      "name": "greeting",
      "type": "TEXT",
      "label": "Greeting",
      "defaultValue": "Hello",
      "prompt": true
    }
  ],
  "validation": {
    "requiredFiles": ["README.md"]
  }
}
```

Important fields:

| Field | Constraint |
|---|---|
| `schemaVersion` | Must be `1`. |
| `id` | Lowercase letters and digits, optionally separated by dots, underscores, or hyphens. The `builtin.` prefix is reserved. |
| `projectType` | `ANDROID`, `FLUTTER`, or `GENERAL`. |
| `category` | Group shown in the template picker. Lower `sortOrder` values appear first. |
| `previewImage` | Optional single 1:1 preview image, 270×270 pixels. |
| `variables` | Declares dynamic form fields and template variables. |
| `hooks` | Optional pre- and post-generation scripts. |
| `validation` | Optional rules for checking generated output. |

The manifest is parsed strictly; unknown fields are rejected. Use the JSON Schema in the repository for machine-readable validation.

## 3. Dynamic variables

Supported prompt types include `TEXT`, `MULTILINE`, `NUMBER`, `BOOLEAN`, `SELECT`, and `SECRET`.

- Variable names must match `^[A-Za-z][A-Za-z0-9_]*$` and must be unique.
- Hidden required variables need default values, except for system-derived variables.
- `SELECT` fields must have unique options. Optional fields may be left blank.
- Workshop supplies the project name and creation path; do not represent them as ordinary dynamic fields.
- System variables include `projectName`, `appName`, and `projectPath`. If `appName` is declared as a dynamic field, user input takes precedence.
- Java package-name validation is enabled only when the template declares `packageName` or `packagePath`.
- A template may declare `projectName` as a fixed variable with `prompt: false` and use `validationRegex` to constrain the shared project-name field. On failure, the UI shows the variable's `description` and blocks creation rather than silently rewriting the input.

## 4. File content and path substitution

Use `{{variableName}}` in both text files and relative paths. During generation, variables are substituted and the trailing `.template` suffix is removed. Common text formats such as Java, Kotlin, Dart, JSON, YAML, TOML, Shell, Python, JavaScript, and C/C++ are recognized.

The special variable `TAIXU_PACKAGE_PATH` expands to the directory hierarchy corresponding to a package name. Avoid placeholder directories starting with underscores because Android AAPT may ignore them. Generated paths are normalized and escaping paths are rejected. If an undeclared placeholder such as `{{unknownVariable}}` remains in a file, creation fails.

## 5. Preview image

A template declares one preview image; there are no separate variants for phone/tablet or portrait/landscape. The image must be square (1:1) and exactly 270×270 pixels. PNG, JPEG, WebP, and GIF are supported; the file must not exceed 4 MiB.

Keep the logo, title, and key content within the central safe area and leave about 8% padding around the edges so cards remain legible at different sizes.

## 6. Generation hooks

Hooks do not run during import, preview, or export. When a project is created, TaiXu displays the script contents and runs them only after the user explicitly grants permission.

- Hooks must be inside `template-hooks/`, with each file at most 1 MiB.
- They run in the new project's Linux sandbox; each stage has a 60-second limit.
- `beforeCreate` runs before template files are copied; `afterCreate` runs after materialization.
- The project path is provided as `TAIXU_PROJECT_DIR`.
- Variables are passed as `TAIXU_VAR_<UPPERCASE_VARIABLE_NAME>`.
- A nonzero exit aborts creation and returns error output to the UI.

## 7. Output validation

Templates may declare:

- `requiredFiles`: files that must exist.
- `forbiddenFiles`: files that must not exist.
- `contentRules`: content checks using `equals`, `contains`, and `excludes`.

Paths and content may use template variables. Validation runs after `afterCreate` completes. Rules belong to the template itself and remain consistent after renaming, export, and re-import.

## 8. Import and export

In the Android app, open **Workshop → More → Template Manager → Import ZIP**. After import, the template appears under the matching project type and category; no app-code or registry changes are required. The template manager can also export templates. User templates may be deleted; built-in templates are protected.

## 9. Example without an Android package name

A `GENERAL` template does not depend on an Android package name. In `README.md.template`, writing `# {{projectName}}\n{{greeting}}` is sufficient; the shared creation screen supplies the project name and path.

## 10. Pre-release checklist

- [ ] The manifest passes the v1 JSON Schema and contains no unknown fields.
- [ ] The ID does not use `builtin.`; variable names and category IDs are valid and unique.
- [ ] Project name and path are not declared as dynamic fields.
- [ ] All placeholders are declared and generated paths cannot escape the destination.
- [ ] The preview meets the 1:1, 270×270, format, and size requirements.
- [ ] Hooks are in the designated directory, contain no secrets, and finish within 60 seconds.
- [ ] Import, form entry, creation, export, and re-import work in a fresh directory.
- [ ] GENERAL templates are not incorrectly prompted for a Java package name.
