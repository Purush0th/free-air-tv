// Draws the Roku channel's UI bitmaps (rounded pill and card, the now-watching dot) into
// roku/images. Plain Node, no packages: shapes are supersampled and written as RGBA PNGs.
// Usage: node roku/art/ui-assets.js
//
// SceneGraph has no rounded rectangles, so the pill and card are 9-patch PNGs (*.9.png): a
// 1 px frame whose black pixels on the top and left edges mark the rows and columns that
// stretch. Sizes are FHD pixels (the Fire TV app's dp values times two).
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const out = path.join(__dirname, '..', 'images');
const SS = 4; // supersampling per axis

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function png(w, h, rgba) {
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

const hex = (s, a = 1) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16), a];

// Renders w x h pixels; paint(x, y) returns [r, g, b, a(0..1)] for a sample point in pixel units.
function render(w, h, paint) {
  const buf = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let j = 0; j < SS; j++) for (let i = 0; i < SS; i++) {
      const c = paint(x + (i + 0.5) / SS, y + (j + 0.5) / SS);
      if (!c) continue;
      r += c[0] * c[3]; g += c[1] * c[3]; b += c[2] * c[3]; a += c[3];
    }
    const o = (y * w + x) * 4;
    if (a > 0) { buf[o] = Math.round(r / a); buf[o + 1] = Math.round(g / a); buf[o + 2] = Math.round(b / a); }
    buf[o + 3] = Math.round((a / (SS * SS)) * 255);
  }
  return buf;
}

// Composites layer colour c over base colour d (both [r,g,b,a]).
function over(c, d) {
  if (!d) return c;
  if (!c) return d;
  const a = c[3] + d[3] * (1 - c[3]);
  if (a === 0) return null;
  return [0, 1, 2].map(k => (c[k] * c[3] + d[k] * d[3] * (1 - c[3])) / a).concat(a);
}

function inRoundRect(x, y, w, h, r) {
  const cx = Math.min(Math.max(x, r), w - r), cy = Math.min(Math.max(y, r), h - r);
  return (x - cx) ** 2 + (y - cy) ** 2 <= r * r && x >= 0 && y >= 0 && x <= w && y <= h;
}

// A 9-patch: rounded rect of radius r, content (2r+2) square, plus the 1 px guide frame.
function ninePatch(file, color, r) {
  const s = 2 * r + 2, W = s + 2;
  const body = render(s, s, (x, y) => inRoundRect(x, y, s, s, r) ? color : null);
  const buf = Buffer.alloc(W * W * 4);
  for (let y = 0; y < s; y++) body.copy(buf, ((y + 1) * W + 1) * 4, y * s * 4, (y + 1) * s * 4);
  const mark = (x, y) => { const o = (y * W + x) * 4; buf[o] = buf[o + 1] = buf[o + 2] = 0; buf[o + 3] = 255; };
  // Stretch only the straight middle (the two pixels between the corners).
  for (let i = r + 1; i <= r + 2; i++) { mark(i, 0); mark(0, i); }
  // Content area (right and bottom guides) = the whole image. Without them Roku fits the item
  // to the stretch area and draws the corners outside it, so a list's focus pill came out
  // 2r px taller than its row and covered the rows next to it.
  for (let i = 1; i <= s; i++) { mark(i, W - 1); mark(W - 1, i); }
  fs.writeFileSync(path.join(out, file), png(W, W, buf));
}

// The now-watching dot (Fire TV watching_dot.xml): a soft halo with a solid core 8 px in.
function dot(file, size, halo, core, highlight) {
  const R = size / 2;
  fs.writeFileSync(path.join(out, file), png(size, size, render(size, size, (x, y) => {
    const d = Math.hypot(x - R, y - R);
    let c = d <= R ? halo : null;
    if (d <= R - 8) {
      const g = core(x, y);
      c = over(g, c);
    }
    if (highlight) {
      // small oval highlight, up and left of centre
      const hx = (x - (R - 2)) / 5, hy = (y - (R - 4)) / 4;
      if (hx * hx + hy * hy <= 1) c = over(highlight, c);
    }
    return c;
  })));
}

function mix(a, b, t) { return [0, 1, 2, 3].map(k => a[k] + (b[k] - a[k]) * t); }

fs.mkdirSync(out, { recursive: true });
ninePatch('pill.9.png', hex('#FFFFFF'), 16);          // focused row: white, 8dp corners
ninePatch('pill_grey.9.png', hex('#262626'), 16);      // Settings: the left item the right pane shows
ninePatch('loader.9.png', hex('#0A0A0A', 0xAD / 255), 24);  // loader backdrop: 12dp corners
ninePatch('card.9.png', hex('#0A0A0A', 0xC7 / 255), 28); // now-watching card: 14dp corners

const light = hex('#FFD58A'), accent = hex('#F2A93B');
dot('dot.png', 36, hex('#F2A93B', 0x59 / 255), (x, y) => {
  // radial gradient centred at 35%, 35%, radius 16 px
  const t = Math.min(1, Math.hypot(x - 36 * 0.35, y - 36 * 0.35) / 16);
  return mix(light, accent, t);
});
dot('dot_on_pill.png', 36, hex('#8A5200', 0x40 / 255), () => hex('#8A5200'), hex('#FFFFFF', 0x66 / 255));

const R = 10;
fs.writeFileSync(path.join(out, 'live_dot.png'), png(20, 20, render(20, 20, (x, y) =>
  Math.hypot(x - R, y - R) <= R ? accent : null)));

console.log('UI assets written to', out);
