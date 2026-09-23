// 在 (圈数, 起始角) 上搜一遍：先按包围盒居中，再看墨迹重心还差多远。
const CX = 512, CY = 512;
const r1 = (n) => Math.round(n * 10) / 10;

function measure({ rOut, rIn, turns, phase, steps = 1200 }) {
  const pts = [];
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  let sw = 0, swx = 0, swy = 0;
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const th = phase + t * turns * 2 * Math.PI;
    const r = rOut + (rIn - rOut) * t;
    const x = CX + r * Math.cos(th), y = CY + r * Math.sin(th);
    pts.push([x, y]);
    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    sw += r; swx += r * x; swy += r * y;
  }
  const dx = CX - (minX + maxX) / 2, dy = CY - (minY + maxY) / 2;
  let bw = 0, bh = 0, bTotal = 0;
  for (const [x, y] of pts) {
    const r = Math.hypot(x - CX, y - CY);
    sw = r;
    bw += r * (x + dx); bh += r * (y + dy); bTotal += r;
  }
  const resX = bw / bTotal - CX, resY = bh / bTotal - CY;
  return { res: Math.hypot(resX, resY), resX, resY, w: maxX - minX, h: maxY - minY };
}

const out = [];
for (let turns = 1.5; turns <= 2.8001; turns += 0.05) {
  for (let deg = 0; deg < 360; deg += 15) {
    const m = measure({ rOut: 374, rIn: 62, turns, phase: (deg * Math.PI) / 180 });
    out.push({ turns: r1(turns), deg, ...m });
  }
}
out.sort((a, b) => a.res - b.res);
console.log('residual = 按包围盒居中后，墨迹重心离画布中心的距离（px / 1024）');
for (const r of out.slice(0, 12)) {
  console.log(
    `圈数 ${String(r.turns).padEnd(5)} 起始角 ${String(r.deg).padStart(3)}°  ` +
    `residual ${r1(r.res).toString().padStart(6)}  (${r1(r.resX)},${r1(r.resY)})  外廓 ${r1(r.w)}x${r1(r.h)}`
  );
}
