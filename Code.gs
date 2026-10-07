/**
 * MRP Ventures – nightly accounts report → Google Sheets.
 *
 * Paste this into Extensions → Apps Script of your Google Sheet, set SECRET,
 * then Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone).
 * Already deployed? Deploy → Manage deployments → ✏️ Edit → Version: New version → Deploy
 * (keeps the same URL, so nothing changes on the phone).
 *
 * Tab "Daily Report": one row per nightly message.
 * Tab "Summary":      Take Home Cash till date (updates automatically).
 *
 * Example message it understands:
 *   Pp- 6039
 *   Z- 975
 *   S- 795
 *   Cash- 4670 - 800 - 2000 - 500 - 1070 = 300
 *   Acc G- 1088
 *   Acc P- 2233
 */

const CONFIG = {
  // Must match the secret typed into the phone app.
  SECRET: 'change-me-to-something-long',

  REPORT_SHEET: 'Daily Report',
  SUMMARY_SHEET: 'Summary',
  TIME_ZONE: 'Asia/Kolkata',

  // A message must contain at least this many of the six fields to be saved.
  MIN_FIELDS: 3,
};

// Column order on the Daily Report tab. `keys` are the labels as written in WhatsApp,
// lower-cased with spaces/dots removed ("Acc G" → "accg").
const COLUMNS = [
  { header: 'Pp - PetPooja',             keys: ['pp', 'petpooja'] },
  { header: 'Z - Zomato',                keys: ['z', 'zomato'] },
  { header: 'S - Swiggy',                keys: ['s', 'swiggy'] },
  { header: 'Cash - Take Home Cash',     keys: ['cash'] },
  { header: 'Acc G - GooglePay Account', keys: ['accg', 'gpay', 'googlepay', 'accgpay'] },
  { header: 'Acc P - PhonePe Account',   keys: ['accp', 'phonepe', 'accphonepe'] },
];
const CASH_COL = 5; // column E on Daily Report = Take Home Cash
const HEADERS = ['Date & Time'].concat(COLUMNS.map(function (c) { return c.header; }), ['Original Message']);

// ---------------------------------------------------------------- web endpoint

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (!CONFIG.SECRET || body.secret !== CONFIG.SECRET) {
      return json_({ ok: false, error: 'Wrong secret' });
    }
    return json_(Object.assign({ ok: true }, ingest_(body.messages || [])));
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return json_({ ok: true, info: 'MRP report endpoint is running. The phone app uses POST.' });
}

// ---------------------------------------------------------------- core

function ingest_(messages) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = setup_(ss);
  const seen = seenIds_();
  const rows = [];
  let skipped = 0, lastCash = null;

  messages.forEach(function (m) {
    if (!m || m.test) return;                       // connection test: nothing written
    if (!m.id || seen.has(m.id)) { skipped++; return; }
    seen.add(m.id);

    const fields = parseReport(m.text || '');
    if (Object.keys(fields).length < CONFIG.MIN_FIELDS) { skipped++; return; }

    const when = new Date(Number(m.captured) || Number(m.time) || Date.now());
    rows.push([when].concat(COLUMNS.map(function (c) {
      return c.header in fields ? fields[c.header] : '';
    }), [m.text]));
    if (COLUMNS[3].header in fields) lastCash = fields[COLUMNS[3].header];
  });

  if (rows.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADERS.length).setValues(rows);
    formatReport_(sheet);
  }
  saveSeenIds_(seen);
  SpreadsheetApp.flush();

  return { added: rows.length, skipped: skipped, cash: lastCash, totalCash: totalCash_(ss) };
}

/**
 * Turns the WhatsApp text into { 'PetPooja (Pp)': 6039, ... }.
 * For every field, if there is an '=' the value after it is used
 * ("4670 - 800 - 2000 - 500 - 1070 = 300" → 300).
 */
function parseReport(text) {
  const out = {};
  String(text).split(/\r?\n/).forEach(function (line) {
    const m = line.match(/^\s*[*_~]*([A-Za-z][A-Za-z .]*?)[*_~]*\s*[-–—:=]\s*(.*?)\s*$/);
    if (!m) return;
    const key = m[1].toLowerCase().replace(/[^a-z]/g, '');
    const col = COLUMNS.filter(function (c) { return c.keys.indexOf(key) >= 0; })[0];
    if (!col || col.header in out) return;
    const value = valueOf_(m[2]);
    if (value !== '') out[col.header] = value;
  });
  return out;
}

function valueOf_(raw) {
  let s = String(raw);
  if (s.indexOf('=') >= 0) s = s.substring(s.lastIndexOf('=') + 1);   // only what's after '='
  s = s.replace(/₹|rs\.?|inr|\/-/gi, '').replace(/[,\s]/g, '').replace(/[–—]/g, '-');
  if (s === '') return '';
  if (/^[+-]?\d+(\.\d+)?$/.test(s)) return Number(s);
  // No '=' but a sum like "4670-800-2000": work it out.
  if (/^[+-]?\d+(\.\d+)?([+-]\d+(\.\d+)?)+$/.test(s)) {
    return s.match(/[+-]?\d+(\.\d+)?/g).reduce(function (a, b) { return a + Number(b); }, 0);
  }
  return String(raw).trim(); // keep the text if it isn't a number, so nothing is lost
}

// ---------------------------------------------------------------- sheets

/** Creates both tabs if missing. You can also run this once from the editor. */
function setup() { setup_(SpreadsheetApp.getActiveSpreadsheet()); }

function setup_(ss) {
  if (ss.getSpreadsheetTimeZone() !== CONFIG.TIME_ZONE) ss.setSpreadsheetTimeZone(CONFIG.TIME_ZONE);

  let report = ss.getSheetByName(CONFIG.REPORT_SHEET);
  if (!report) {
    report = ss.insertSheet(CONFIG.REPORT_SHEET, 0);
    report.setFrozenRows(1);
    report.setColumnWidth(1, 150);
    report.setColumnWidth(HEADERS.length, 320);
  }
  // Header row is (re)written every time, so renamed columns show up on an existing tab too.
  report.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS])
    .setFontWeight('bold').setBackground('#e8f0fe').setWrap(true);

  let summary = ss.getSheetByName(CONFIG.SUMMARY_SHEET);
  if (!summary) {
    summary = ss.insertSheet(CONFIG.SUMMARY_SHEET, 1);
    summary.setColumnWidth(1, 220);
  }
  // Re-written every time so it always points at the right column.
  summary.getRange('A1').setValue('Take Home Cash till date').setFontWeight('bold');
  summary.getRange('B1')
    .setFormula("=SUM('" + CONFIG.REPORT_SHEET + "'!" + colLetter_(CASH_COL) + "2:" + colLetter_(CASH_COL) + ")")
    .setNumberFormat('₹#,##0').setFontWeight('bold');

  // Remove the empty default tab of a brand-new spreadsheet.
  const blank = ss.getSheetByName('Sheet1');
  if (blank && blank.getLastRow() === 0 && ss.getSheets().length > 2) ss.deleteSheet(blank);
  return report;
}

function formatReport_(sheet) {
  const n = sheet.getLastRow() - 1;
  if (n < 1) return;
  sheet.getRange(2, 1, n, 1).setNumberFormat('dd-mmm-yyyy hh:mm:ss am/pm');
  sheet.getRange(2, 2, n, COLUMNS.length).setNumberFormat('#,##0.##');
}

function totalCash_(ss) {
  const summary = ss.getSheetByName(CONFIG.SUMMARY_SHEET);
  const v = summary ? summary.getRange('B1').getValue() : 0;
  return typeof v === 'number' ? v : 0;
}

/** Try the parser: edit the text, press Run, then look at the Execution log. */
function testParse() {
  const sample = 'Pp- 6039\nZ- 975\nS- 795\nCash- 4670 - 800 - 2000 - 500 - 1070 = 300\nAcc G- 1088\nAcc P- 2233';
  Logger.log(JSON.stringify(parseReport(sample), null, 2));
}

// ---------------------------------------------------------------- helpers

function seenIds_() {
  const raw = PropertiesService.getScriptProperties().getProperty('seen_ids');
  return new Set(raw ? JSON.parse(raw) : []);
}

function saveSeenIds_(set) {
  const ids = Array.from(set).slice(-300);
  PropertiesService.getScriptProperties().setProperty('seen_ids', JSON.stringify(ids));
}

function colLetter_(n) { return String.fromCharCode(64 + n); }

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
