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

## What it is

A Markdown editor for your phone — for notes, a diary, or editing a document.

- Write on your phone, carry on at your computer: it edits the `.md` files already on your phone and saves them back in place, so back up and sync exactly as you do now
- Comfortable to write in: syntax highlighting, one-tap preview, and tables, formulas and images all show properly
- Free and open source, no account, no network, no storage permission

## Download

Grab the latest APK (`MarkNote-vX.Y.Z.apk`) from [Releases](https://github.com/groundgrounder/MarkNote/releases).

- Requires Android 8.0 or newer
- The APK does not come from an app store, so on first install the system will ask you to allow installs from unknown sources
- **Updating**: from v1.1.1 onwards you can install over the previous version; earlier versions must be uninstalled first

## Features

**Files**

- Create: tap "+" at the bottom-right. In the folder list it makes the file right there (long-press the button for a new folder); on the recent list it asks where to put it
- Open: tap a `.md`, `.markdown` or plain-text file in any file manager and pick "Open with MarkNote"
- Or switch the sidebar to "Folder", grant one folder, and pick a document right inside it. The list holds only files MarkNote can edit, so images, archives and other binaries never show up in it
- Encoding untouched: UTF-8, UTF-16 and GBK all read correctly, and a file is saved back in the encoding it came in — a GBK note stays GBK
- Recent files: everything you open stays on the home screen and survives a reboot; remove an entry you no longer need without touching the file
- A file that was moved or can no longer be read is flagged in red — open it and follow the prompt to grant access again
- **Folder browsing**: switch the sidebar to "Folder" to work in the folder you granted — browse it, make files and folders in it, rename and delete things, subfolders included; long-press an entry for its menu. A document opened this way treats that folder as the place its images live
- Auto-save: saves 800 ms after you stop typing, and before you leave the editor or switch to preview
- Switch to manual in Settings if you prefer; a save button then appears in the top bar and highlights while changes are unsaved
- Read-only files are called out: the editor says "changes will not be saved" instead of quietly dropping your work

**Editing**

- Syntax highlighting: headings, bold, italic, strikethrough, quotes, code and links are coloured
- Toolbar: headings, bold, italic, quote, list, link, code and horizontal rule in one tap, sitting above the keyboard and wrapping your selection
- Undo / redo: two buttons in the top bar. A run of typing counts as one step, toolbar inserts and replace-all as their own; the buttons grey out when there is nothing left to undo
- Enter continues the list: `-`, `1.`, `>`, `- [ ]` carry over with numbering advanced and new to-do items unchecked; press Enter again on an empty item to end the list
- Indent: Tab / Shift+Tab indent or outdent the current line, or the whole selection
- Hardware keyboard shortcuts: Ctrl+S saves, Ctrl+F finds, Ctrl+B / Ctrl+I bold and italic, Ctrl+Z undoes, Ctrl+Shift+Z (or Ctrl+Y) redoes. An on-screen keyboard cannot send these, so use the top-bar buttons there
- Search & replace: shows which match you are on out of how many, cycles through them, and can replace one or all
- Outline: slides in from the right on landscape and tablets, up from the bottom on narrow screens; tap a heading to jump to it
- Word count: "N chars · M lines", always in the top bar

**Preview**

- One tap to switch between editing and preview; tables, strikethrough and tappable links all display properly
- To-do items show as checkboxes, and a bare `https://…` in the text becomes a link you can open
- Jump links: tapping `[text](#heading)` scrolls straight to that section, and says so when there is no such heading. Footnote markers (`[^1]` and their definition lines) show as written
- Formulas: `$…$` (or an inline `$$…$$`) shows in the line; `$$` on a line of its own is centred. Matrices, fractions, integrals and sums all work, drawn in the theme's colour at the preview font size
- A single or unpaired `$` is left alone, so "from $5 to $10" is not mistaken for a formula
- Images: relative paths are looked up **next to the document** (`..` steps up a level); grant that folder once when the prompt appears and they keep working
- An image outside the granted folder or missing shows a line of explanation where it would be; images embedded in the document show too, and very large ones are scaled down
- Search and outline work in the preview as well: search runs over **what you actually see**, matches highlight one by one and scroll into view, with a "match N of M" counter and up/down navigation; tap an outline entry to jump to that heading. The preview is read-only, so there is no replace

**Multiple windows**

- One document per window: keep several windows open (system split screen, desktop windows on tablets), each with its own file
- Want another one: long-press a file in the recent list and pick “Open in new window”
- The same file never gets two editors, so two copies cannot overwrite each other
- Back still returns to the file list; close a window from the system recents

**UI & language**

- Colours taken from your wallpaper (Material You, Android 12+); light or dark can follow the system or be locked
- Tablets and landscape switch to two panes: file list on the left, editor on the right; the sidebar collapses to a narrow strip
- Four UI languages — 简体中文, 繁體中文, English and Latina. It follows the system by default and can be picked in Settings without losing the document you are editing
- Languages that are not built in show English; on Android 13+ you can also change it under system Settings → Apps → MarkNote → Language
- Settings: theme, language, separate font sizes for editor and preview, the auto-save switch, hidden files in folder browsing, the permission notice for files from other apps, and version info
- The icon follows your system's themed-icon setting (Android 13+)

## A few notes

- Files opened from another app (a file manager, a chat, an email) get no lasting access, so MarkNote cannot reopen them once it is closed. The editor then shows a notice with “Grant access” — pick the same file again and it stays editable, in the recent list too. The dialog that explains this can be silenced with “Don’t show again”, and switched back on in Settings
- Images need a granted folder: pick the one holding **both** the document and its images; granting just the document's folder fails whenever the images sit outside it
- Search in the preview runs over the displayed text, so formula markers such as `$` and `$$` are not found there (they are in the editor); text inside a formula is searched as usual
- One unrecognised command in a formula (say `\zzzz`) turns the whole formula into a boxed notice — fix it and it shows. The formula fonts have no Chinese characters, so keep Chinese outside the formula
- The folder list holds editable text files only — Markdown, plain text, and other text types such as `.py` or `.html`. Images, archives, PDFs and other binaries never appear in it
- Files and folders whose name starts with a dot (`.git`, `.obsidian`) stay hidden as well; turn on "Show hidden files" in Settings to see them. Reinstalling the app drops the folder permission, so pick the folder again when prompted
- Keyboards carry their own undo: if Ctrl+Z takes back a single character, that was the keyboard, not MarkNote — the top-bar buttons use the app's own history

<details>
<summary>Version history</summary>

### v1.1.0

- Editor undo/redo, driven by two top-bar buttons (edit mode only — the preview has nothing to undo)
- A burst of typing collapses into a single step: a pause of 600 ms splits it, so one undo never rolls back a whole paragraph; toolbar inserts and replace-all each stay a step of their own. The caret returns to the change, and the buttons dim when there is nothing left to undo
- The history is bounded (both in entries and in total diff size), so one large paste cannot grow it without limit; every entry is checked against the live text before it is applied, and a stale one is dropped rather than corrupting the document
- Opening or reloading a document invalidates the history, so a re-read can never be undone through a stale entry
- Note: there is no Ctrl+Z shortcut yet — use the undo button in the top bar

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

### v1.4.0

- One document per window: MarkNote can now hold several windows at once (system split screen, desktop windows on tablets), each editing its own file with no interference; the title in each window is the file it is editing
- Long-press a file in the recent list and pick “Open in new window” for another one; a document that is already open is not opened a second time (MarkNote says so and declines, and it recognises the same file opened from a different source)
- Fixed: after opening a file from a file manager, the recent list on a wide screen did not refresh and kept showing the state from before
- Fixed: rotating the screen or entering/leaving split screen used to pop up the “no long-term access” notice all over again

### v1.5.0

- Folder browsing: switch the sidebar to "Folder", grant one folder once, and browse or open its Markdown and plain-text files right in the app, subfolders included
- Enter continues lists: `-`, `1.`, `>`, `- [ ]` carry over with numbering advanced and new to-do items unchecked; press Enter again on an empty item to end the list. Tab / Shift+Tab indents or outdents
- Hardware keyboard shortcuts: Ctrl+S saves, Ctrl+F finds, Ctrl+B / Ctrl+I bold and italic, Ctrl+Z undoes, Ctrl+Shift+Z (or Ctrl+Y) redoes
- The preview is closer to GFM: to-do items show as checkboxes, a bare link becomes tappable, `[text](#heading)` jumps inside the document, and footnote markers are no longer swallowed
- The sidebar title now sits on one line with the buttons, giving the list a row back

### v1.5.1

- "Open file" now opens the in-app list instead of the system picker: grant a folder once, then pick a document right inside it, subfolders included. The picker could not do this — it filters by type in the "Recent" view only, and goes back to showing everything the moment you step into a folder
- That list holds editable text files only: images, archives, PDFs and other binaries no longer appear in it
- New "Show hidden files" switch in Settings, for when you do want to see `.git` or `.obsidian`. Off by default
- Granting access to a file again (after opening it from another app) no longer filters by type, so files the system reports as `application/octet-stream` can be found again

### v1.5.2

- You can now do work inside a folder, not just browse it: create files and folders, rename, delete — long-press an entry for the menu, or long-press "＋" to create a folder right in the current directory
- "New" adapts to the tab you are in: in the folder tab it creates right there, in the recent tab it asks where to put the file
- Clearer notice for files opened from other apps: the banner at the top of the editor carries the "grant access again" action, and the explanatory dialog can be dismissed for good (turn it back on in Settings)
- With auto save off, leaving the editor now asks first (Save / Discard / Stay). It used to write back regardless of that setting, contradicting the "save manually" description
- Fixed: syntax highlighting ignored backslash escapes, so `\[text\](link)` — meant to show literally — was highlighted as a link (the preview had it right, the two disagreed)
- Fixed: both write paths now verify the file's actual length, so a provider that ignores the truncate flag no longer leaves the tail of the old content behind; a corrupt recent-files list is salvaged instead of wiped

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
