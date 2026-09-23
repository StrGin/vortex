// f6 衍生：浅底纯黑大涡纹。按包围盒居中，并缩放到统一外廓尺寸。
// 运行：node gen-f6.js
const fs = require('fs');
const path = require('path');
const OUT = __dirname;

const CX = 512, CY = 512;
const r1 = (n) => Math.round(n * 10) / 10;
const BG = '#F2F5F9';
const BLACK = '#000000';
const STROKE = 88;
const FILL = 840; // 目标外廓（含线宽）边长，画布 1024

function build({ rOut, rIn, turns, phase, steps = 1400 }) {
  const pts = [];
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const th = phase + t * turns * 2 * Math.PI;
    const r = rOut + (rIn - rOut) * t;
    const x = CX + r * Math.cos(th), y = CY + r * Math.sin(th);
    pts.push([x, y]);
    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
  }
  const dx = CX - (minX + maxX) / 2;
  const dy = CY - (minY + maxY) / 2;
  const span = Math.max(maxX - minX, maxY - minY) + STROKE;
  const s = FILL / span;
  const d = 'M' + pts.map(([x, y]) =>
    `${r1(CX + (x + dx - CX) * s)} ${r1(CY + (y + dy - CY) * s)}`).join('L');
  return { d, s, span: r1(span), scale: r1(s), bbox: `${r1((maxX - minX + STROKE) * s)}x${r1((maxY - minY + STROKE) * s)}` };
}

const svg = (d, sRaw) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
<rect width="1024" height="1024" fill="${BG}"/>
<path d="${d}" fill="none" stroke="${BLACK}" stroke-width="${r1(STROKE * sRaw)}" stroke-linecap="round"/>
</svg>
`;

const P = (deg) => (deg * Math.PI) / 180;
const cands = [
  ['g1-t18-d315', { rOut: 374, rIn: 62, turns: 1.8, phase: P(315) }, '1.8 圈 · 起笔朝右上'],
  ['g2-t20-d315', { rOut: 374, rIn: 62, turns: 2.0, phase: P(315) }, '2.0 圈 · 起笔朝右上'],
  ['g3-t22-d315', { rOut: 374, rIn: 62, turns: 2.2, phase: P(315) }, '2.2 圈 · 起笔朝右上'],
  ['g4-t22-d135', { rOut: 374, rIn: 62, turns: 2.2, phase: P(135) }, '2.2 圈 · 起笔朝左下'],
];

const built = cands.map(([n, o, cap]) => {
  const b = build(o);
  fs.writeFileSync(path.join(OUT, `${n}.svg`), svg(b.d, b.s));
  console.log(n, '外廓', b.bbox, '缩放', b.scale);
  return [n, cap];
});

const cells = built
  .map(
    ([n, cap]) => `<div class="cell"><div class="wrap"><img class="big" src="${n}.svg">
    <i class="vx"></i><i class="hy"></i></div>
    <div class="small"><img src="${n}.svg" width="88" height="88" style="border-radius:20px">
    <img src="${n}.svg" width="60" height="60" style="border-radius:14px">
    <img src="${n}.svg" width="42" height="42" style="border-radius:10px"></div>
    <div class="cap">${n}<br>${cap}</div></div>`
  )
  .join('');

const html = `<!doctype html><meta charset="utf-8"><style>
 html,body{margin:0;background:#0b0d12;color:#cfd6e4;font:14px/1.5 system-ui,sans-serif}
 body{width:1520px;padding:30px;box-sizing:border-box}
 .row{display:grid;grid-template-columns:repeat(4,1fr);gap:26px;align-items:end}
 .cell{text-align:center}
 .wrap{position:relative;width:300px;height:300px;margin:0 auto}
 .big{width:300px;height:300px;border-radius:66px;display:block;box-shadow:0 8px 24px #0007}
 .wrap i{position:absolute;background:#ff2d55;opacity:.5}
 .vx{left:50%;top:0;width:1px;height:100%}
 .hy{top:50%;left:0;height:1px;width:100%}
 .small{display:flex;gap:12px;justify-content:center;align-items:flex-end;margin-top:14px}
 .cap{margin-top:10px;font-size:13px;color:#8f9bb3}
</style><div class="row">${cells}</div>`;
fs.writeFileSync(path.join(OUT, 'sheet-f6.html'), html);
console.log('wrote sheet-f6.html');
