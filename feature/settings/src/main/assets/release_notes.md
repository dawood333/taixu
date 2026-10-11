# TaiXu v0.17.0 Release Notes

> **Current version:** v0.17.0  
> **Runtime environment:** Android 10+ · arm64-v8a (PRoot Linux sandbox + AI pair programming)

---

### ✨ New Features

- **On-device offline translation:** Deep integration with Google ML Kit on-device translation, including automatic language detection and offline model download progress. Accurate two-way translation works without an API key.
- **Material You wallpaper colors:** Improved dynamic color extraction and live refresh from Android system wallpapers, while preserving the ability to switch between the “Liquid Glass” frosted-glass theme and the native “Material You” look.
- **Agent Harness architecture improvements:**
  - Adopted best practices from RikkaHub / Pi, adding fault-tolerant editing, prompt caching, direct visual input, and clean retry behavior.
  - Completed `.agents/skills` discovery, stop-retained content, and Prompt Cache controls and TTL customization.
  - Implemented Split-Turn role bridging and mechanical-summary file traces, completing optional engineering capabilities.
  - Fixed 512 MB out-of-memory (OOM) failures caused by escaped content in compressed snapshots and loading all branches.
  - Standardized the `content` field in output and intelligently filtered UI-only skill suggestions.
  - Simplified the `history_read` tool parameter schema and aligned it precisely with history search.

### 🐛 Bug Fixes

- **Terminal and communication:** Switched mDNS to on-demand discovery, eliminating frequent `NSD_PICKER` error dialogs on Android 17.
- **TaiXu chat experience:** Message bubbles now support long-press text selection and one-tap copying; improved the corner alignment and padding of model-usage progress bars.
- **UI layout stability:** Fixed multiple cases of unexpectedly tall list items and squeezed text wrapping, improving layouts on compact and foldable screens.
- **Model configuration:** Removed forced thinking-language constraints so the model can adapt to the language context of the instructions.

### ⚡ Build and Engineering

- Debug builds automatically reuse Release signing parameters when signing credentials are configured, preventing mismatches between debug and production signatures.
- **Layered architecture:** Moved the Markdown rendering engine into the shared components layer. Update dialogs and About/release notes now fully support rich-text previews.
