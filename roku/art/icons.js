// Draws the Settings icons into roku/images from the Fire TV app's vector paths
// (app/src/main/res/drawable): the channel tick boxes in each state, plain and on the white
// focused row, and the gear. Usage: node roku/art/icons.js (render.ps1 runs it).
'use strict';
const fs = require('fs');
const path = require('path');
const { drawAll } = require('./canvas');

const out = path.join(__dirname, '..', 'images');
const BOX_FILL = 'M7,1h12a6,6 0 0,1 6,6v12a6,6 0 0,1 -6,6h-12a6,6 0 0,1 -6,-6v-12a6,6 0 0,1 6,-6z';
const BOX_LINE = 'M7,2h12a5,5 0 0,1 5,5v12a5,5 0 0,1 -5,5h-12a5,5 0 0,1 -5,-5v-12a5,5 0 0,1 5,-5z';
const TICK = 'M7.5,13.5l3.8,3.8l7.2,-8.1';
const DASH = 'M8,13h10';
const GEAR = 'M19.43,12.98c0.04,-0.32 0.07,-0.64 0.07,-0.98s-0.03,-0.66 -0.07,-0.98l2.11,-1.65c0.19,-0.15 0.24,-0.42 0.12,-0.64l-2,-3.46c-0.12,-0.22 -0.39,-0.3 -0.61,-0.22l-2.49,1c-0.52,-0.4 -1.08,-0.73 -1.69,-0.98l-0.38,-2.65C14.46,2.18 14.25,2 14,2h-4C9.75,2 9.54,2.18 9.51,2.42L9.13,5.07C8.52,5.32 7.96,5.66 7.44,6.05l-2.49,-1c-0.23,-0.09 -0.49,0 -0.61,0.22l-2,3.46C2.21,8.95 2.27,9.22 2.46,9.37l2.11,1.65C4.53,11.34 4.5,11.67 4.5,12s0.03,0.66 0.07,0.98l-2.11,1.65c-0.19,0.15 -0.24,0.42 -0.12,0.64l2,3.46c0.12,0.22 0.39,0.3 0.61,0.22l2.49,-1c0.52,0.4 1.08,0.73 1.69,0.98l0.38,2.65C9.54,21.82 9.75,22 10,22h4c0.25,0 0.46,-0.18 0.49,-0.42l0.38,-2.65c0.61,-0.25 1.17,-0.59 1.69,-0.98l2.49,1c0.23,0.09 0.49,0 0.61,-0.22l2,-3.46c0.12,-0.22 0.07,-0.49 -0.12,-0.64L19.43,12.98zM12,15.5c-1.93,0 -3.5,-1.57 -3.5,-3.5s1.57,-3.5 3.5,-3.5s3.5,1.57 3.5,3.5S13.93,15.5 12,15.5z';

// Each layer: [path, fill or null, stroke or null]. Sizes are FHD pixels.
const box = (file, layers) => ({ file, size: 40, view: 26, layers });
const jobs = [
  box('check_on.png', [[BOX_FILL, '#F2A93B'], [TICK, null, '#111111']]),
  box('check_off.png', [[BOX_LINE, null, '#6E6E6E', 2]]),
  box('check_some.png', [[BOX_FILL, '#F2A93B'], [DASH, null, '#111111']]),
  // On the white focused row: darker, so they keep 3:1 against it.
  box('check_on_sel.png', [[BOX_FILL, '#8A5200'], [TICK, null, '#FFFFFF']]),
  box('check_off_sel.png', [[BOX_LINE, null, '#6B6B6B', 2]]),
  box('check_some_sel.png', [[BOX_FILL, '#8A5200'], [DASH, null, '#FFFFFF']]),
  // White, tinted by the channel (Poster.blendColor).
  { file: 'gear.png', size: 48, view: 24, layers: [[GEAR, '#FFFFFF']] },
  // "Connecting" loader (Fire TV loader_signal.xml at 72 px): each wave on its own so the
  // channel can pulse them in turn; the full-strength colour, faded by Poster opacity.
  { file: 'loader_wave1.png', size: 72, view: 96, layers: [['M38.1,54.1A14,14 0 0,1 57.9,54.1', null, '#00B896', 5]] },
  { file: 'loader_wave2.png', size: 72, view: 96, layers: [['M29.62,45.62A26,26 0 0,1 66.38,45.62', null, '#0284C7', 5]] },
  { file: 'loader_wave3.png', size: 72, view: 96, layers: [['M21.13,37.13A38,38 0 0,1 74.87,37.13', null, '#1D4ED8', 5]] },
  { file: 'loader_dot.png', size: 72, view: 96, layers: [['M43,64a5,5 0 1,0 10,0a5,5 0 1,0 -10,0', '#FFFFFF']] },
];

const draw = `async (job) => {
  const c = document.createElement('canvas');
  c.width = c.height = job.size;
  const g = c.getContext('2d');
  g.scale(job.size / job.view, job.size / job.view);
  for (const [d, fill, stroke, width] of job.layers) {
    const p = new Path2D(d);
    if (fill) { g.fillStyle = fill; g.fill(p); }
    if (stroke) {
      g.strokeStyle = stroke; g.lineWidth = width || 2.6; g.lineCap = 'round'; g.lineJoin = 'round';
      g.stroke(p);
    }
  }
  return c;
}`;

for (const { job, png } of drawAll(jobs, draw)) fs.writeFileSync(path.join(out, job.file), png);
console.log(`${jobs.length} icons written to ${out}`);
