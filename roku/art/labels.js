// Draws the Tamil text the channel shows as PNGs in roku/images/labels, from channels.json:
//   <md5 of a channel's locale text>-<size>.png   the channel's Tamil name
//   lang-<code>-<size>.png                        each language's own name, e.g. தமிழ்
// plus languages.json (code -> English name) and index.txt (every file, one per line).
// Usage: node roku/art/labels.js   (the package scripts run it)
//
// Roku's text engine does not apply Tamil shaping (vowel signs come out on the wrong side,
// conjuncts break), so the channel shows these Chrome-drawn images instead of text. Naming by
// MD5 means a renamed channel gets a new image and the channel finds it from the text alone.
// The text is white on transparent; the channel tints it with Poster.blendColor.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { drawAll } = require('./canvas');

const roku = path.join(__dirname, '..');
const repo = path.join(roku, '..');
const outDir = path.join(roku, 'images', 'labels');
// Font sizes used by the channel: 22 px in lists, 26 px on the card and Settings headings.
const SIZES = [22, 26];

const channels = JSON.parse(fs.readFileSync(path.join(repo, 'channels.json'), 'utf8'));
const texts = [...new Set(channels.map(c => (c.locale || '').trim()).filter(Boolean))];
const langs = [...new Set(channels.map(c => c.lang || 'ta-IN'))];
const md5 = t => crypto.createHash('md5').update(t, 'utf8').digest('hex');

const jobs = [];
for (const t of texts) for (const s of SIZES) jobs.push({ file: `${md5(t)}-${s}.png`, text: t, size: s });
for (const l of langs) for (const s of SIZES) jobs.push({ file: `lang-${l}-${s}.png`, lang: l, size: s });

const draw = `async (job) => {
  const c = document.createElement('canvas'), g = c.getContext('2d');
  const text = job.text || new Intl.DisplayNames([job.lang], { type: 'language' }).of(job.lang.split('-')[0]);
  const font = job.size + 'px T, "Segoe UI", Roboto, sans-serif';
  await document.fonts.load(font, text);
  g.font = font;
  const m = g.measureText(text);
  // Fixed height per size (room for Tamil's tall vowel signs), text centred in it.
  const h = Math.round(job.size * 1.65), asc = m.fontBoundingBoxAscent, desc = m.fontBoundingBoxDescent;
  c.width = Math.ceil(m.width) + 4; c.height = h;
  g.font = font; g.fillStyle = '#fff';
  g.fillText(text, 2, Math.round((h - (asc + desc)) / 2 + asc));
  return c;
}`;

const images = drawAll(jobs, draw, { T: path.join(roku, 'fonts', 'NotoSansTamil-Regular.ttf') });

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
for (const { job, png } of images) if (job.file) fs.writeFileSync(path.join(outDir, job.file), png);

// English names, for the Settings screen (Node's Intl has the same names as Chrome's).
const english = {};
for (const l of langs) english[l] = new Intl.DisplayNames(['en'], { type: 'language' }).of(l.split('-')[0]);
fs.writeFileSync(path.join(outDir, 'languages.json'), JSON.stringify(english, null, 1) + '\n');

// The channel can't check for files on its UI thread, so it reads this list instead.
fs.writeFileSync(path.join(outDir, 'index.txt'),
  fs.readdirSync(outDir).filter(f => f.endsWith('.png')).sort().map(f => f + '\n').join(''));
console.log(`${images.length} Tamil labels (${texts.length} names, ${langs.length} languages, ${SIZES.length} sizes) in ${outDir}`);
