# Final hand checklist (CS F213)

Due: report and code link on LMS by **Mon 30 Nov 2026**. Demo: **4–7 Dec**. Tick each box by hand.

## Things only you can do

- [ ] **Cover page:** fill in team names and roll numbers. They are in `docs/report/build/report.js` (the `table(['No.', 'Name of team member', 'Roll number'], …)` line), or edit `docs/report/Ember_Report.docx` directly in Word.
- [ ] **Rebuild the PDF** after editing: in `docs/report/build/`, `node report.js`, then `"C:\Program Files\LibreOffice\program\python.exe" export_pdf.py`. (Skip `node report.js` if you edited the .docx by hand; then export the PDF from Word.)
- [ ] **Read the report once on paper** (black and white): the figures and tables are numbered 1–17 and 1–8.
- [ ] **Merge `calls-v2` into `main`** when you are happy (the branch is pushed; `main` still has the older calls). Tag `demo-safe-pre-calls-v2` marks the version before this work.
- [ ] **Hosted site (optional):** to have calls v2 online, set `EMBER_CALLS_V2=1` in the Render service's environment and redeploy. Without it the site keeps the older call code (safe).
- [ ] **Upload to LMS:** `docs/report/Ember_Report.pdf` (and `.docx` if asked) plus the GitHub link `https://github.com/POKEDB10/Ember`.
- [ ] Render CPU/RAM graphs during a call were **not measured** (needs your Render dashboard). Either capture them before the demo, or leave the report as it is ("not measured").

## Before the demo

- [ ] `scripts\run-tests.bat`: `46 passed, 0 failed` and Node `fail 0`.
- [ ] Rehearse `docs/DEMO.md` twice end to end, on the demo laptop, at projector resolution.
- [ ] Chrome: allow camera and microphone for `http://localhost:8090`.
- [ ] Headphones for the desktop app.
- [ ] Every team member reads `docs/VIVA_NOTES.md` (concept map and 20 questions) and the parts of `docs/CODEBASE_GUIDE.md` they own.
- [ ] Know the "not measured" list in `docs/results.md`; never quote a number that is not in it.

## Already done (checked 24 Nov 2026)

- [x] Report with calls and end-to-end chapters, call sequence/state/CRC figures, measured results table, 36 pages, Times New Roman 12/14, 1.15 spacing, contents page filled.
- [x] Honesty pass: test and file counts recounted (46 Java + 9 Node tests, 122 Java files); the old "40 MB idle" claim replaced by a measurement (about 64 MB, laptop, not Render); an unverifiable photo-size claim removed.
- [x] `docs/results.md`, `docs/decisions.md` (26 decisions), `docs/ladder.md`, `docs/DEMO.md`, `docs/VIVA_NOTES.md`, README quick start.
- [x] Secrets stay out of git: `config/secrets.properties`, keystores, `Start cmd.txt` and local data folders are ignored.
