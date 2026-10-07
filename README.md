# MRP Accounts → Google Sheets

Captures the nightly accounts report from the WhatsApp group **ACCOUNTS - MRP VENTURES**
and adds it to a Google Sheet.

## How it behaves

| Time | What the app does |
|---|---|
| **9 PM** | Wakes up (alarm), makes sure it's listening, sends anything left over. |
| **10 PM – 3 AM** | Watches **only** ACCOUNTS - MRP VENTURES. Accepts a message only if it was posted in this window **and** looks like the report (at least 3 of Pp / Z / S / Cash / Acc G / Acc P). |
| After the first report | Saves it to the sheet, shows "Report saved" notification, and **stops** for the night (later report-like messages are ignored). |
| **3 AM** | Checks: if nothing was captured, shows "No report captured last night". |

Messages posted before 10 PM, ordinary chat, and every other WhatsApp group are ignored.
The hours can be changed in the app (Settings).

## The Google Sheet

**Tab "Daily Report"** — one row per night:

| Date & Time | Pp - PetPooja | Z - Zomato | S - Swiggy | Cash - Take Home Cash | Acc G - GooglePay Account | Acc P - PhonePe Account | Original Message |
|---|---|---|---|---|---|---|---|
| 08-Oct-2026 10:32:15 pm | 6039 | 975 | 795 | **300** | 1088 | 2233 | Pp- 6039 … |

- **Date & Time** = the phone's date & time when the message was captured.
- **Cash** keeps only the number after `=` (`4670 - 800 - 2000 - 500 - 1070 = 300` → 300).
  If someone forgets the `=`, the sum is worked out instead.
- **Original Message** is kept so you can double-check any row (delete the column if you don't want it).

**Tab "Summary"** — `Take Home Cash till date` = a live SUM of the Cash column, so it updates
by itself every time a row is added (or if you edit/delete a row by hand).

## Updating from the previous version

**Sheet:**
1. Open your Google Sheet → **Extensions → Apps Script**.
2. Select all the old code, delete it, paste the new `apps-script/Code.gs`.
3. Put your secret back on the `SECRET:` line (same one as before). Save 💾.
4. **Deploy → Manage deployments → ✏️ (pencil) → Version: New version → Deploy.**
   The URL stays the same, so nothing changes on the phone.
5. Back in the sheet, delete the old **Raw** and **Data** tabs (right-click the tab → Delete).
   The **Daily Report** and **Summary** tabs are created automatically with the first report,
   or straight away if you run the `setup` function once in the script editor (choose `setup` → ▶ Run).

**App (GitHub):**
1. In your GitHub repository, open each changed file and replace it — or simplest:
   **Add file → Upload files**, drag in the new `app` folder and the files from this zip, **Commit changes**.
   (The `.github/workflows/build-apk.yml` file you created earlier stays as it is.)
2. **Actions** tab → wait for the green tick → download **wa-sheets-apk** → unzip → `app-debug.apk`.
3. On the phone: **uninstall the old app first** (long-press its icon → Uninstall), then install the new APK.
   (Each GitHub build has a different signature, so Android won't install it over the old one.)
4. Open **MRP Accounts** and redo the setup: notification access, battery = unrestricted,
   paste URL + secret, allow notifications. The group is already filled in.
5. Tap **Test connection to sheet** — the log should say "✅ Connected to the sheet".

## First-time setup (if you haven't done it before)

See the step-by-step guide in the chat: create the Google Sheet + Apps Script (Deploy as Web app,
Execute as Me, Access Anyone), put the project on GitHub, build the APK in Actions, install it,
then set up the app as in step 4 above.

## Good to know

- The group must **not be muted** — muted groups don't create notifications, so the app can't see them.
  To keep it quiet, set the group's notification tone to *None* instead of muting.
- If you have the group chat **open on screen** at 10 PM, WhatsApp doesn't show a notification for it.
- Phone switched off / no internet: messages are queued and sent later. If a night is missed
  completely, paste the report into **"Missed a night? Add it manually"** in the app.
- To correct a wrong number, just edit the cell in the sheet — the Summary total updates automatically.
- On Xiaomi / Redmi / Oppo / Vivo / Realme, also enable **Autostart** for the app, otherwise
  the 9 PM alarm may be blocked.
