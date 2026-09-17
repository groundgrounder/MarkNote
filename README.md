# MarkNote

A lightweight Android Markdown editor · edits the `.md` files already on your device · no storage permission, no network

**English** | [简体中文](README.zh-CN.md)

<p>
  <img src="docs/screenshots/list.png" width="19%" alt="Recent files">
  <img src="docs/screenshots/editor.png" width="19%" alt="Editor">
  <img src="docs/screenshots/preview.png" width="19%" alt="Preview: images and tables">
  <img src="docs/screenshots/dark.png" width="19%" alt="Dark mode">
  <img src="docs/screenshots/landscape.png" width="19%" alt="Landscape two-pane">
</p>

MarkNote is a **file-first** Markdown editor: it keeps no private note database — it reads and writes `.md` files anywhere on your device. Tap a `.md` file in a file manager and pick MarkNote; your edits are saved back to the original location. No account, no network, no storage permission — your notes stay simply your own files.

## Download

Grab the latest APK (`MarkNote-vX.Y.Z.apk`) from [Releases](https://github.com/groundgrounder/MarkNote/releases).

- Requires Android 8.0 or newer
- The APK does not come from an app store, so on first install the system will ask you to allow installs from unknown sources
- **Updating**: from v1.1.1 onwards you can install over the previous version; earlier versions must be uninstalled first

## Features

**Files**

- Open straight from a file manager: tap a `.md` / `.markdown` or plain-text file anywhere and choose MarkNote in the system "Open with" sheet; reopening reuses the same window
- Or use "Open file" inside the app to read and write **any folder** through the system document picker (external storage and cloud-sync folders included), no storage permission needed, edits written back to the original file
- Encoding preserved: UTF-8 (with or without BOM), UTF-16 and GB18030/GBK are read correctly, and edits are written back in the encoding the file came in with — a GBK note stays GBK instead of being silently converted to UTF-8
- Recent files: everything you have opened stays on the home screen and is still editable after a reboot; entries can be removed (from the list only — the file is untouched); a file that was moved or whose access expired is flagged in red, and "Re-authorize" brings it back
- Read-only files are called out: without write access the editor shows "changes will not be saved" instead of silently dropping your edits
- Auto-save: writes to disk 800 ms after you stop typing, and saves before you leave the editor or switch to preview; can be switched to manual in Settings (the save button in the top bar highlights while there are unsaved changes)

**Editing**

- Syntax highlighting in the editor: headings, bold, italic, strikethrough, quotes, code, links and list markers are coloured live
- Symbol toolbar: H1/H2/bold/italic/strikethrough plus quote / list / link / code / horizontal rule, docked above the keyboard, wrapping the current selection
- Search & replace: match counter (n/m), wrapping navigation with highlighted matches, replace one or replace all
- Undo/redo: two buttons in the top bar. A burst of typing collapses into a single step (a pause splits it), while toolbar inserts and replace-all each stay a step of their own; the caret returns to the change and the buttons dim when there is nothing left to undo
- Outline navigation: slides in from the right on landscape/tablet, bottom sheet on narrow screens; tapping jumps straight to that heading (available in preview too)
- Word count: live "N chars · M lines" in the top bar

**Preview**

- One-tap toggle between editing and preview: supports GFM tables (header, zebra striping, column alignment), strikethrough and tappable links
- LaTeX formulas: `$$…$$` and a single `$…$` both render inline, while `$$` on a line of its own renders a centred display formula — matrices (`\begin{pmatrix} a & b \\ c & d \end{pmatrix}`), fractions, integrals, sums, Greek letters and the rest of the common vocabulary. They are drawn in the theme's text colour, so they stay readable in dark mode, and they scale with the preview font size. Delimiters follow Pandoc's rules, so `from $5 to $10`, `$ x$`, `$x $` and `$x$1` all stay literal. A formula that cannot be typeset shows its source in the error colour inside a thin outline instead of quietly staying as plain text
- Search and outline work in the preview as well: search runs over the **rendered output** (what you see is what you search), every match is highlighted, the current one is solid and scrolled into view, with an n/m counter and up/down navigation; tapping an outline entry scrolls the body to that heading. Preview is read-only, so there is no replace
- Images: relative-path images resolve **relative to the document's own folder** (`..` walks up, as Markdown expects) inside a folder you grant once — tap "Grant access" in the banner and pick a folder containing **both** the document and its images (the grant is persistent); an image that resolves outside the grant shows an inline "not in the granted folder" placeholder instead of silently vanishing; `content://`, `file://` and base64 data URIs are supported; oversized images are downsampled to avoid running out of memory

**UI & adaptation**

- Material You: dynamic colour on Android 12+, light/dark can follow the system or be locked manually, edge-to-edge layout
- Tablet / landscape two-pane: wide screens switch automatically to a file list on the left and the editor on the right, live on rotation; the sidebar collapses to a narrow strip
- Settings: theme mode, app language, independent font sizes for editor and preview, auto-save switch, version info
- The launcher icon supports Android 13+ themed icons, following the system's monochrome setting

## Tips & troubleshooting

- **Searching for `$` or `$$` finds nothing in the preview**: the formula delimiters are removed when rendering, so they match in the editor but not in the preview. Text inside a formula is searched normally
- **An image does not show up**: relative-path images resolve against the document's own folder. If you see "not in the granted folder", grant the folder that contains **both** the document and its images — granting only the document's folder fails whenever the images live outside it
- **A file opened from a chat app or via "Open with" stops working after a while**: files handed over by other apps usually cannot be granted lasting access. MarkNote tells you straight away when it detects this — follow the prompt and re-pick the same file in the system picker to obtain a durable grant, after which the recent list works too
- **Why there is no Ctrl+Z shortcut**: while a text field has focus the letter key is handed to the IME first, and keyboards such as Gboard consume the combination for their own per-character undo, so the app never sees it. Use the undo button in the top bar instead
- **A formula appears in the error colour inside a box**: one unrecognised command in it (say `\zzzz`) fails the whole formula; fix that command and it renders. Note that the formula fonts contain no CJK glyphs, so `\text{中文}` cannot be typeset — keep Chinese outside the formula

## App language

**4 built-in UI languages**: English, Simplified Chinese, Traditional Chinese and Latin. It follows the system by default and can be pinned in Settings, applying immediately while keeping the document you are editing; unmatched languages (Japanese, French, Korean, …) fall back to English. Timestamps in the recent-files list follow the language too.

On Android 13 and newer there is a second entry point: change it directly under system Settings → Apps → MarkNote → Language, kept in sync with the in-app picker.

## Roadmap

- v1.4: "Save as", image insertion
- v2.0: WebDAV sync, custom themes, multi-tab editing

<details>
<summary>Version history</summary>

### v1.1.0

- Editor undo/redo, driven by two top-bar buttons (edit mode only — the preview has nothing to undo)
- A burst of typing collapses into a single step: a pause of 600 ms splits it, so one undo never rolls back a whole paragraph; toolbar inserts and replace-all each stay a step of their own. The caret returns to the change, and the buttons dim when there is nothing left to undo
- The history is bounded (both in entries and in total diff size), so one large paste cannot grow it without limit; every entry is checked against the live text before it is applied, and a stale one is dropped rather than corrupting the document
- Opening or reloading a document invalidates the history, so a re-read can never be undone through a stale entry
- Note: there is no Ctrl+Z shortcut yet — see "Tips & troubleshooting" for why

### v1.1.1

- Fixed: every release used to be signed with a different key, so installing a new version over an old one was rejected with "App not installed". All releases now use one fixed signing key and update over the previous version normally
- No functional changes

### v1.1.2

- New app icon: the Markdown "M↓" mark redrawn from scratch. The M is bolder with flat terminals and mitered corners — square at the top, pointed at the middle V — instead of a round-capped blob; the arrow now matches the M's height, weight, cap line and baseline, where it used to sit short and oversized beside it
- Colours reworked: the accent is now light periwinkle (#9AA8FF), a lighter tint of the background hue, replacing the amber (#FFD54F) that clashed with it, and the flat #4A5ACF background became a #4C58DA → #2C35A0 gradient
- Occupies exactly the same space as before: nothing gets clipped under round or squircle masks, and the mark still reads down to 16px; Android 13+ themed icons keep working

### v1.1.3

- Fixed: relative-path images resolved to the wrong place, and could even load a different image of the same name without any sign of it. Paths used to be resolved against the **root of the granted folder**, with `..` dropped instead of walking up — so `![](../img/a.png)` and any note not sitting at the root of the grant were wrong. They now resolve against the **document's own folder** (`..` walks up, as Markdown expects), and layouts that already worked are unaffected
- Failed loads are no longer invisible: the reason is drawn where the image would have been, instead of leaving an empty box
- The grant banner comes back when access has gone stale (reinstall, revoked permission) instead of failing silently
- The banner now says which folder to grant — one holding **both** the document and its images; the old wording pointed at the document's folder, which cannot work when the images live outside it

### v1.1.4

- Undo no longer swallows what you type after a toolbar insert: pressing H1 and then typing no longer collapses into one undo that removes both
- Fixed: re-entering a document could overwrite freshly typed text with the contents read from disk
- The outline now understands `~~~` fences as well as backtick ones, and only a fence of the same character and at least the same length closes it — a `~~~` block used to leak its `#` headings into the outline, while a backtick fence inside it closed the block early
- Search match count and "replace all" now agree: the counter used to include overlapping matches (`aaaa` searching `aa` reported 3, while replace actually changed 2)
- List snippets no longer start with an invisible blank character for BOM-prefixed files
- The recent list is capped at 100 entries, oldest by open time evicted, so it no longer gets slower the more you use the app
- Two more edge cases fixed: a short write can no longer leave the tail of the old content behind in the file, and a corrupt recent-list record is backed up instead of emptying the list

### v1.2.0

- LaTeX formulas in the preview: `$$…$$` renders inline and `$$` on a line of its own renders a centred display formula. Matrices, fractions, integrals, sums and Greek letters all work, the formulas are drawn in the theme's text colour so they stay readable in dark mode, and they scale with the preview font size
- Only `$$` counts as math, deliberately: a lone `$` is left exactly as written, so prose like "from $5 to $10" is not turned into a formula, and inline code keeps its `$` for the same reason
- A document full of formulas does not block the preview: each formula briefly shows its source and is then replaced by the typeset result
- The `$$` delimiters are not part of the rendered text, so searching for `$$` matches in the editor but not in the preview. Everything inside the formula is searched normally
- Fixed: the outline jumped to the wrong place for a heading that contains a formula; it now lands exactly on it

### v1.2.1

- Inline math with a single `$`: `$\alpha$`, `$x^2 + y^2$` and `$\frac{a}{b}$` now render, alongside the existing `$$…$$`. Delimiters follow Pandoc's rules, so `$5 to $10`, `$ x$`, `$x $` and `$x$1` all stay literal
- A formula that cannot be typeset now shows its source in the error colour inside a thin outline, instead of quietly staying as plain text. One undefined command (`\zzzz`) still fails the whole formula, but you can now see which one it is
- Fixed: a lone `$` used to swallow the text up to a following `$$…$$`, so `costs $5, $$E=mc^2$$` silently lost the `$5, `
- The outline now knows that single-`$` delimiters also disappear during rendering, so jumping to a heading that contains one lands exactly on it

### v1.3.0

- Four UI languages now: English, Simplified Chinese, Traditional Chinese and Latin. Japanese, French, German, Spanish and Italian are gone, and users of those languages see English
- The Latin UI gained the two image-loading error messages that used to appear in English
- Fixed: if you had previously picked one of the removed languages, Settings showed "System default" while the UI stayed English; that leftover choice is now cleared and the system language is followed from the second launch on

### v1.3.1

- Dark mode now reaches the system bars and the launch window too: forcing Dark while the system is light used to leave dark status-bar icons on a dark background, effectively invisible
- Fixed: with some sources a save that had actually succeeded was reported as "save failed" — the file-length check read "unknown length" as a mismatch
- Fixed: re-granting access to a document, or granting the image folder, no longer freezes the UI while it waits for the system
- Typing in large documents is smoother: the syntax highlighter no longer recompiles its rules on every keystroke
- The English wording of the "grant the image folder" prompt now matches the other languages

### v1.0.0

- First stable release: the feature set, UI language handling and file-format behaviour are settled from here on
- Encoding is detected on read and reused on write: UTF-8 (with or without BOM), UTF-16LE/BE and GB18030/GBK all read correctly, and a GBK note is saved back as GBK instead of being silently converted to UTF-8 (previously a single edit mangled any non-UTF-8 file)
- A UTF-8 BOM is stripped on read and restored on write, so the first heading of a BOM-prefixed file is now parsed into the outline and highlighted like any other
- Reopening a document re-reads it from disk when there are no unsaved changes, so edits made in another app are no longer overwritten by a stale in-memory copy
- Saving no longer writes stale content, and the editor can no longer get stuck showing unsaved changes forever
- Replace-all counts matches the same way the result is produced (non-overlapping); overlapping matches used to be counted twice
- The UI no longer stutters on large documents: file-name lookups and similar slow work moved off the main thread, and the word/line count is no longer recomputed on every redraw

### v0.11.0

- The fallback language is now English instead of Chinese: users whose language is not built in used to see Chinese and now see English
- Added a system language entry point, so on Android 13+ the UI language can be changed from system Settings → Apps → MarkNote → Language
- In-app selection and the system per-app language are synced both ways, and either change applies immediately
- Chinese tags moved from region to script, covering several regions that share the same script in one go

### v0.10.0

- Localization: new "Language" setting with 9 built-in UI languages — Simplified Chinese, Traditional Chinese, English, Japanese, French, German, Spanish, Italian and Latin; follows the system by default and shows each language by its own endonym
- Switching applies immediately and keeps the current screen and the document being edited
- Timestamp formatting in the recent list follows the language as well

### v0.9.0

- Search and outline now work in the read-only preview: previously the preview top bar kept only edit/preview and hid both entrances. Both are available in preview now — search runs over the **rendered output** (what you see is what you search), every match is highlighted with the current one solid and scrolled into view, plus an n/m counter and up/down navigation; tapping an outline entry scrolls the body straight to that heading. Preview is read-only, so no replace
- Preview body avoids the keyboard: the IME no longer covers matches while searching
- Shortened the "This file cannot be accessed long-term" dialog, dropping the explanation the buttons already carry

### v0.8.1

- Fixed "file not found after reopening the app": files handed over by external apps mostly cannot be granted lasting access, and the failure used to be swallowed. It is now reported immediately, with a prompt to re-pick the same file through the system picker
- Write access is checked when opening: a read-only grant (or a failed save) shows a banner plus "Re-authorize" in the editor instead of silently losing changes
- Read failures are no longer disguised as empty documents: there is an explicit error screen (Re-authorize / Retry / Back), and writing is disabled while the read failed so empty content cannot overwrite the original file
- The recent list no longer loses entry times and ordering, and entries no longer only grow; re-authorizing replaces the old entry automatically, and stale entries no longer show up as a number

### v0.8.0

- Images in preview: relative-path images display after a one-time grant (a banner appears at the top of the preview — tap "Grant access" and pick the document's folder; the grant is persistent); supports `content://`, `file://` and base64 inline images; images larger than 4096 px are downsampled
- GFM table rendering in preview, with header, zebra striping and column alignment

### v0.7.2

- Unified the home-screen floating buttons: "Open file" lost its label and became a small icon-only button matching "New"; the two are stacked vertically in the same colour scheme

### v0.7.1

- Landscape/tablet fixes: bottom padding in the sidebar list so file cards are no longer covered by the floating buttons; long outline titles are ellipsized on a single line instead of squeezing the panel
- Wide-screen interaction fix: tapping a file on the left (or opening from a file manager) while Settings is open now correctly switches the right pane back to the editor
- The Settings screen now leaves room for the gesture navigation bar

### v0.7.0

- New Settings screen: theme mode (system/light/dark), independent font size for editor and preview (small/normal/large), auto-save switch, and version info

### v0.6.2

- Dark mode fix: some ROMs (MIUI/HyperOS and friends) force-invert the dark UI and turn the editor white; cold starts in dark mode no longer flash white either

### v0.6.1

- UI consistency: all icons switched to Outlined, the outline uses a dedicated icon (no longer clashing with the list icon), the secondary theme colour matches the icon family, the toolbar avoids the gesture navigation bar and collapsing the sidebar avoids the status bar
- Bug fixes: saving can no longer be overwritten by stale content, selection and counter are correct after search & replace, the list summary refreshes after returning from the editor, and preview links are tappable

### v0.5.0

- Collapsible left sidebar, right-hand outline panel on wide screens, symbol toolbar

### v0.4.0

- Two-pane layout, centred max-width, grid list on wide screens

### v0.3.0

- Word count, search & replace

### v0.2.0

- Editor syntax highlighting, outline navigation

### v0.1.0 (MVP)

- Plain source editing with a preview toggle, rendering, file management, auto-save, Material You theming

</details>

## License

Copyright (C) 2026 groundgrounder

MarkNote is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License** as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

MarkNote is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.

You should have received a copy of the GNU General Public License along with this program.
If not, see <https://www.gnu.org/licenses/>.
