// Draws PNGs on a canvas in one headless Chrome run and returns them to Node.
// Chrome is used because its text engine shapes Tamil correctly and its canvas takes SVG path
// data, so the Roku bitmaps can be drawn from the same sources as the Fire TV app.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const chrome = [
  process.env.CHROME,
  path.join(process.env.ProgramFiles || '', 'Google/Chrome/Application/chrome.exe'),
  path.join(process.env['ProgramFiles(x86)'] || '', 'Google/Chrome/Application/chrome.exe'),
  path.join(process.env.LOCALAPPDATA || '', 'Google/Chrome/Application/chrome.exe'),
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].find(p => p && fs.existsSync(p));

const fileUrl = p => 'file:///' + p.replace(/\\/g, '/');

// jobs: plain data, one per image. draw: the source of a page-side function
// `async (job) => canvas` (it runs in Chrome, not Node). fonts: {family: ttf path}.
// Returns [{job, png: Buffer}] in job order.
function drawAll(jobs, draw, fonts = {}) {
  if (!chrome) throw new Error('Chrome not found (set CHROME to its path).');
  const faces = Object.entries(fonts)
    .map(([family, file]) => `@font-face{font-family:${family};src:url("${fileUrl(file)}")}`).join('');
  const page = `<!doctype html><meta charset="utf-8"><style>${faces}</style>
<body><pre id="out"></pre><script>
const jobs = ${JSON.stringify(jobs)};
const draw = ${draw};
(async () => {
  await document.fonts.ready;
  const out = [];
  for (let i = 0; i < jobs.length; i++) {
    const c = await draw(jobs[i]);
    out.push('IMG ' + i + ' ' + c.toDataURL('image/png').split(',')[1]);
  }
  document.getElementById('out').textContent = out.join(' ');
})();
</script>`;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'freeair-canvas-'));
  const html = path.join(tmp, 'draw.html');
  fs.writeFileSync(html, page);
  let dom;
  try {
    dom = execFileSync(chrome, ['--headless=new', '--disable-gpu', '--allow-file-access-from-files',
      '--virtual-time-budget=10000', '--dump-dom', fileUrl(html)],
      { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  // One "IMG <job index> <base64 png>" per image: indexes, so the text needs no escaping.
  const images = [...dom.matchAll(/IMG (\d+) ([A-Za-z0-9+/=]+)/g)]
    .map(([, i, b64]) => ({ job: jobs[Number(i)], png: Buffer.from(b64, 'base64') }));
  if (images.length !== jobs.length) throw new Error(`Chrome drew ${images.length} of ${jobs.length} images.`);
  return images;
}

module.exports = { drawAll };
