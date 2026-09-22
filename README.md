# MarkNote

A lightweight, local-first Android Markdown editor. Edit `.md` files directly on your device with no storage permissions, no network access, and complete privacy.

**English** | [简体中文](README.zh-CN.md)

<p align="center">
  <img src="docs/screenshots/list.png" width="19%" alt="Recent files">
  <img src="docs/screenshots/editor.png" width="19%" alt="Editor">
  <img src="docs/screenshots/preview.png" width="19%" alt="Preview: images and tables">
  <img src="docs/screenshots/dark.png" width="19%" alt="Dark mode">
  <img src="docs/screenshots/landscape.png" width="19%" alt="Landscape two-pane">
</p>

---

## 🌟 What makes MarkNote different?

Many Markdown apps force you to import files into a database or use a proprietary sync service. MarkNote is different: **it edits files exactly where they are on your phone.** 

- **No Sync Lock-in:** Edits the `.md` files already on your phone and saves them back in-place. You can back up and sync using whatever tools you already use (e.g., Syncthing, Git, Obsidian Sync, etc.).
- **Privacy First & Permission Light:** No account, no analytics, no internet connection, and zero storage permission required (using Android's modern Storage Access Framework).
- **Distraction-Free & Robust:** Syntax highlighting, smart toolbar, LaTeX math formulas, table rendering, local relative images, and multiple windows.

---

## 🚀 Download & Installation

Grab the latest APK (`MarkNote-vX.Y.Z.apk`) from [Releases](https://github.com/groundgrounder/MarkNote/releases).

* **System Requirements:** Android 8.0 or newer.
* **Side-loading Notice:** Because this app is distributed directly (not through Google Play), your system will ask you to allow installs from "unknown sources" on first install.
* **Upgrading:** Simply install the new version over the previous one (applicable for v1.1.1 and later).

---

## ✨ Features

### 📂 File & Workspace Management
* **Open & Edit Directly:** Tap any `.md`, `.markdown`, or plain-text file in your preferred system file manager and choose "Open with MarkNote".
* **Workspace Folder Mapping:** Access a designated local directory as a workspace. Browse subdirectories and pick files directly from the app's sidebar.
* **Manage Files Locally:** Create new files and folders, rename them, or delete them directly inside your workspace folder (long-press items for the menu).
* **Recent Files History:** Access recently opened documents on your home screen. They stay pinned across reboots; tap the "×" next to any entry to clear it from the history without deleting the actual file.

### 🖋️ Writing & Editing Experience
* **Live Syntax Highlighting:** Headings, bold, italic, strikethroughs, quotes, blockquotes, inline/fenced code, and hyperlinks are visually styled in real-time.
* **Contextual Symbol Toolbar:** Easily insert headings, lists, links, images, or format text with a smart toolbar sitting right above the soft keyboard. It intelligently wraps your active selection.
* **Smart List Continuation:** Pressing Enter in lists automatically continues `-`, `1.`, `>`, or `- [ ]` prefixes (with advancing counters for ordered lists). Pressing Enter on an empty list item exits list mode.
* **Block Indentation:** Use Tab and Shift+Tab to indent or outdent lines or block selections effortlessly.
* **Granular Undo & Redo:** Intelligent history tracking groups typing bursts, toolbar inserts, and search-replaces logically. Control these via top-bar buttons.
* **Hardware Keyboard Shortcuts:** Fully supports external keyboards with common shortcuts: `Ctrl + S` (save), `Ctrl + F` (find/replace), `Ctrl + B`/`Ctrl + I` (bold/italic), and `Ctrl + Z`/`Ctrl + Y` (undo/redo).
* **Live Statistics:** The top-bar continuously displays the character and line counts (`N chars · M lines`).

### 📖 Rich GFM Preview & LaTeX Formulas
* **GitHub Flavored Markdown:** Renders Markdown tables (with alignments and zebra-striping), interactive checklist checkboxes, and auto-linked URLs.
* **LaTeX Math Equations:** Supports inline math using `$…$` (following Pandoc rules) and block math on its own line using `$$…$$`. Formulas are drawn using the system's text color for perfect readability in dark mode.
* **Obsidian-Style Relative Images:** Image paths like `../img/pic.png` resolve relative to the document folder itself. Grant access to the parent folder once, and your images display seamlessly.
* **Internal Anchor Navigation:** Link jumps (e.g., `[text](#heading)`) scroll straight to the target section in preview. Standard Markdown footnote markers (`[^1]`) are also supported.

### ⚙️ Saving, Encoding & Customization
* **Adaptive Save Modes:** 
  * *Auto-Save:* Automatically writes back 800ms after you stop typing, or when leaving the editor.
  * *Manual Save:* Turn auto-save off in Settings to reveal an on-screen save button that highlights when you have unsaved changes. Leaving with unsaved changes prompts you to save or discard.
* **Encoding Protection:** Automatically detects and preserves file encodings (including UTF-8 with/without BOM, UTF-16, and GBK/GB18030). A GBK file will never be silently forced into UTF-8.
* **Multi-Window Support:** Open multiple document editors side-by-side in Android's split-screen or tablet desktop mode. The app prevents dual editors on the same file to avoid overwriting edits.
* **Material You Dynamic UI:** Automatically adapts to your Android 12+ wallpaper's color palette. Supports system-following dark mode or manual light/dark locks.
* **Wide Screen Layout:** Dynamically switches to a two-pane layout on tablets and landscape screens, showing the file list and editor side-by-side.
* **Multi-lingual UI:** Supports English, Simplified Chinese (简体中文), Traditional Chinese (繁體中文), and Latin (Latina).

---

## 💡 Good to Know

* **Temporary File Access:** Files opened from external apps (like chats or email clients) are granted temporary, non-persistent system permissions. MarkNote cannot automatically reopen them after being closed. Follow the "Grant access again" banner at the top of the editor to pick the file again and keep it in your recent files list.
* **Folder Permissions for Images:** To display relative-path images, make sure you grant access to a folder that contains **both** the document and the images. Granting access only to the document's immediate directory will fail if the images reside in a sibling directory.
* **LaTeX Formula Warnings:** Math formulas are typeset dynamically. An unrecognized command (e.g., `\zzzz`) will render a boxed error showing the faulty source. Note that LaTeX math fonts do not include Chinese characters; keep Chinese text outside formula blocks.
* **Virtual Keyboard Undo:** If pressing `Ctrl+Z` on your on-screen virtual keyboard behaves unexpectedly (e.g., deletes one character at a time), that is the keyboard's internal history engine. Use the top-bar undo/redo buttons to use MarkNote's application-level history.
* **Permission Resets:** Reinstalling the app or revoking permissions in Android system settings will require you to select and authorize your workspace and image folders again.

---

<details>
<summary><b>📜 Version History</b></summary>

### v1.5.2
* **Folder Operations:** Create, rename, and delete files/folders directly within the workspace folder (via long-press menus).
* **Smart "New" Action:** Adapts to current view. Creates inside the active directory in folder view, or asks where to place it in the recent list view.
* **Save Confirmations:** With auto-save disabled, leaving the editor now asks whether to save or discard changes.
* **Fixed:** Resolved syntax highlighting ignoring backslash escapes (e.g., `\[text\](link)`).
* **Robust Writes:** Verifies exact file length to avoid leaving leftover tails of old file content.

### v1.5.1
* **In-App File Browser:** Replaced the system document picker with an optimized in-app file browser, hiding non-editable binary files like PDFs and ZIPs.
* **Hidden Files Switch:** Added settings toggle for showing hidden files (e.g., `.git`, `.obsidian`).

### v1.5.0
* **Folder Browsing:** Introduced the sidebar "Folder" tab for navigating designated local directory trees.
* **Smart Lists:** Added automatic continuation for list indicators (`-`, `1.`, `>`, `- [ ]`) and block indentation via Tab / Shift+Tab.
* **GFM Tweaks:** Support for checkboxes, bare links, heading anchors, and footnote parsing.
* **Physical Keyboards:** Added keyboard shortcuts (Ctrl+S, Ctrl+F, Ctrl+B, Ctrl+I, Ctrl+Z, Ctrl+Y).

### v1.4.0
* **Multi-Window Support:** Edit multiple documents side-by-side (Android split-screen or desktop mode).
* **Dual-Open Protection:** Prevents opening the same document in multiple windows simultaneously to prevent race conditions.

### v1.3.1
* **System Bar Integration:** Dark mode now correctly styles system status and navigation bars.
* **Smooth Scrolling:** Refactored syntax highlighter rules to prevent UI stuttering on large documents.

### v1.3.0
* **UI Languages:** Consolidated interface languages into Simplified Chinese, Traditional Chinese, English, and Latin.

### v1.2.1
* **Inline Math:** Added support for single-`$` inline math blocks using Pandoc-style delimiters.
* **Math Diagnostics:** Displays descriptive error boxes on math typesetting failures instead of silently rendering plain text.

### v1.2.0
* **Math Formulas:** Introduced LaTeX block math formula rendering (`$$…$$`) inside the preview.

### v1.1.4
* **Outline Improvement:** The outline panel now respects `~~~` code block fences and handles headings inside them correctly.
* **Recent List Optimization:** Caps the recent files list at 100 entries to maintain high performance.

### v1.1.3
* **Relative Images Fix:** Correctly resolves relative paths starting with `..` against the document folder rather than the workspace root.
* **Failure Feedback:** Displays a placeholder explaining why an image failed to load.

### v1.1.2
* **Reworked Icon:** Modernized and bolded the Markdown "M↓" mark icon.
* **Color Tuning:** Switched the accent to a pleasant light periwinkle (`#9AA8FF`) and added a subtle background gradient.

### v1.1.1
* **Signature Fix:** Unified signing keys so new updates install smoothly over existing releases.

### v1.1.0
* **Editor Undo/Redo:** Native app-level undo history controlled via top-bar buttons. Smartly groups typed sequences by 600ms pauses to prevent whole-paragraph rollbacks.

### v1.0.0
* **First Stable Release:** Core feature set and file handling established.
* **Charset Detection:** Full read/write support for UTF-8 (with/without BOM), UTF-16, and GBK. Keeps non-UTF-8 files intact.
* **External Edit Sync:** Automatically re-reads changes on disk when files are updated outside MarkNote, avoiding overwriting external edits.

### v0.9.0
* **Search & Outline in Preview:** Enabled searching and outline jumping within the preview view.
* **Keyboard Adaptation:** The preview screen shifts intelligently to avoid hiding search matches behind the soft keyboard.

### v0.8.1
* **Robust Exception Handling:** Shows explicit permission restoration and read-error states rather than pretending a failed read is an empty document.

### v0.8.0
* **GFM Tables:** Support for markdown table styling and alignment.
* **Initial Relative Images:** Added support for local image rendering after a one-time folder authorization.

</details>

---

## 📄 License

Copyright (C) 2026 groundgrounder

MarkNote is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.
