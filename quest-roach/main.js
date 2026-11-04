// ゴキブリシューター MR — Meta Quest 向け WebXR ゲーム
import * as THREE from './lib/three.module.min.js';

// ---------------------------------------------------------------------------
// 基本セットアップ
// ---------------------------------------------------------------------------
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.xr.enabled = true;
renderer.xr.setReferenceSpaceType('local-floor');
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(70, window.innerWidth / window.innerHeight, 0.01, 60);
camera.position.set(0, 1.6, 0);
camera.rotation.order = 'YXZ';
scene.add(camera);

scene.add(new THREE.HemisphereLight(0xfff4e6, 0x3a3028, 1.6));
const sun = new THREE.DirectionalLight(0xffffff, 1.6);
sun.position.set(1, 3, 1.5);
scene.add(sun);

// PCお試し用の床（MRでは非表示。現実の床が見える）
const pcFloor = new THREE.Group();
{
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(14, 14),
    new THREE.MeshStandardMaterial({ color: 0xb59a7a, roughness: 0.9 })
  );
  floor.rotation.x = -Math.PI / 2;
  pcFloor.add(floor);
  const grid = new THREE.GridHelper(14, 28, 0x7a6248, 0x8e7458);
  grid.position.y = 0.001;
  pcFloor.add(grid);
}
pcFloor.visible = false;
scene.add(pcFloor);

const rand = (a, b) => a + Math.random() * (b - a);
const TAU = Math.PI * 2;
function lerpAngle(a, b, k) {
  const d = ((b - a + Math.PI) % TAU + TAU) % TAU - Math.PI;
  return a + d * Math.min(1, k);
}
function dist2D(a, b) { return Math.hypot(a.x - b.x, a.z - b.z); }

// ---------------------------------------------------------------------------
// サウンド（WebAudio で全部その場で合成・立体音響）
// ---------------------------------------------------------------------------
const Snd = {
  ctx: null, master: null, noise: null, rustle: null,
  init() {
    if (this.ctx) { this.ctx.resume(); return; }
    const C = window.AudioContext || window.webkitAudioContext;
    if (!C) return;
    this.ctx = new C();
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.7;
    this.master.connect(this.ctx.destination);
    const len = this.ctx.sampleRate;
    const buf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
    const d = buf.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    this.noise = buf;
    // カサカサ音（一番近いゴキブリの位置から鳴る）
    const src = this.ctx.createBufferSource();
    src.buffer = buf; src.loop = true;
    const bp = this.ctx.createBiquadFilter();
    bp.type = 'bandpass'; bp.frequency.value = 4200; bp.Q.value = 1.2;
    const g = this.ctx.createGain(); g.gain.value = 0;
    const am = this.ctx.createGain(); am.gain.value = 0.5;
    const lfo = this.ctx.createOscillator(); lfo.frequency.value = 23;
    const lfoG = this.ctx.createGain(); lfoG.gain.value = 0.5;
    lfo.connect(lfoG); lfoG.connect(am.gain);
    const p = this.panner(new THREE.Vector3(0, 0, -2));
    src.connect(bp); bp.connect(am); am.connect(g); g.connect(p); p.connect(this.master);
    src.start(); lfo.start();
    this.rustle = { gain: g, panner: p };
  },
  panner(pos) {
    const p = this.ctx.createPanner();
    p.panningModel = 'HRTF';
    p.distanceModel = 'inverse';
    p.refDistance = 0.4;
    p.rolloffFactor = 1.3;
    this.setPos(p, pos);
    return p;
  },
  setPos(p, v) {
    if (p.positionX) { p.positionX.value = v.x; p.positionY.value = v.y; p.positionZ.value = v.z; }
    else p.setPosition(v.x, v.y, v.z);
  },
  updateListener(pos, fwd, up) {
    if (!this.ctx) return;
    const l = this.ctx.listener;
    if (l.positionX) {
      l.positionX.value = pos.x; l.positionY.value = pos.y; l.positionZ.value = pos.z;
      l.forwardX.value = fwd.x; l.forwardY.value = fwd.y; l.forwardZ.value = fwd.z;
      l.upX.value = up.x; l.upY.value = up.y; l.upZ.value = up.z;
    } else {
      l.setPosition(pos.x, pos.y, pos.z);
      l.setOrientation(fwd.x, fwd.y, fwd.z, up.x, up.y, up.z);
    }
  },
  env(t, peak, attack, dur) {
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(peak, t + attack);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    return g;
  },
  tone(type, f0, f1, dur, peak, dest, delay = 0) {
    const c = this.ctx, t = c.currentTime + delay;
    const o = c.createOscillator();
    o.type = type;
    o.frequency.setValueAtTime(f0, t);
    o.frequency.exponentialRampToValueAtTime(Math.max(20, f1), t + dur);
    const g = this.env(t, peak, 0.005, dur);
    o.connect(g); g.connect(dest || this.master);
    o.start(t); o.stop(t + dur + 0.05);
  },
  zap() {
    if (!this.ctx) return;
    this.tone('square', 1500, 200, 0.12, 0.18);
    this.tone('sine', 3000, 600, 0.08, 0.1);
  },
  splat(pos, big = false) {
    if (!this.ctx) return;
    const c = this.ctx, t = c.currentTime;
    const p = this.panner(pos); p.connect(this.master);
    const s = c.createBufferSource(); s.buffer = this.noise;
    const lp = c.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = big ? 500 : 1100;
    const g = this.env(t, big ? 0.9 : 0.5, 0.004, big ? 0.5 : 0.2);
    s.connect(lp); lp.connect(g); g.connect(p);
    s.start(t, Math.random() * 0.5); s.stop(t + 0.6);
    this.tone('sine', big ? 120 : 220, 40, big ? 0.35 : 0.15, 0.6, p);
  },
  buzz(pos) {
    if (!this.ctx) return null;
    const c = this.ctx;
    const o = c.createOscillator(); o.type = 'sawtooth'; o.frequency.value = rand(150, 190);
    const lfo = c.createOscillator(); lfo.frequency.value = rand(25, 35);
    const lg = c.createGain(); lg.gain.value = 25;
    lfo.connect(lg); lg.connect(o.frequency);
    const lp = c.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 1400;
    const g = c.createGain(); g.gain.value = 0.0001;
    g.gain.exponentialRampToValueAtTime(0.35, c.currentTime + 0.3);
    const p = this.panner(pos);
    o.connect(lp); lp.connect(g); g.connect(p); p.connect(this.master);
    o.start(); lfo.start();
    return {
      panner: p,
      stop: () => {
        const t = c.currentTime;
        g.gain.cancelScheduledValues(t);
        g.gain.setValueAtTime(g.gain.value, t);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.15);
        o.stop(t + 0.2); lfo.stop(t + 0.2);
      },
    };
  },
  setRustle(pos, amount) {
    if (!this.rustle) return;
    this.setPos(this.rustle.panner, pos);
    this.rustle.gain.gain.setTargetAtTime(amount, this.ctx.currentTime, 0.05);
  },
  hurt() {
    if (!this.ctx) return;
    this.tone('sawtooth', 220, 60, 0.5, 0.35);
    this.tone('square', 233, 70, 0.5, 0.2);
  },
  jingle(notes, step = 0.1, type = 'triangle') {
    if (!this.ctx) return;
    notes.forEach((f, i) => this.tone(type, f, f * 0.99, step * 1.6, 0.22, null, i * step));
  },
  roar(pos) {
    if (!this.ctx) return;
    const p = this.panner(pos); p.connect(this.master);
    this.tone('sawtooth', 90, 45, 1.2, 0.6, p);
    this.tone('square', 61, 40, 1.2, 0.4, p);
  },
};

// ---------------------------------------------------------------------------
// テクスチャ・テキストパネル
// ---------------------------------------------------------------------------
const FONT = '"Noto Sans JP","Noto Sans CJK JP","Hiragino Sans","Yu Gothic",sans-serif';

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function makePanel(w, h, pw, ph) {
  const canvas = document.createElement('canvas');
  canvas.width = pw; canvas.height = ph;
  const ctx = canvas.getContext('2d');
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthTest: false, depthWrite: false });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
  mesh.renderOrder = 1000;
  return { mesh, ctx, canvas, update() { tex.needsUpdate = true; } };
}

function makeCanvasTex(size, draw) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  draw(c.getContext('2d'), size);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

const shadowTex = makeCanvasTex(64, (ctx, s) => {
  const g = ctx.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
  g.addColorStop(0, 'rgba(0,0,0,0.55)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
});

const splatTex = [0, 1, 2].map(() => makeCanvasTex(128, (ctx, s) => {
  const c = s / 2;
  ctx.fillStyle = 'rgba(92,74,24,0.92)';
  for (let i = 0; i < 14; i++) {
    const a = Math.random() * TAU, r = rand(0, s * 0.28);
    ctx.beginPath();
    ctx.arc(c + Math.cos(a) * r, c + Math.sin(a) * r, rand(s * 0.04, s * 0.13), 0, TAU);
    ctx.fill();
  }
  ctx.fillStyle = 'rgba(150,130,40,0.85)';
  for (let i = 0; i < 6; i++) {
    const a = Math.random() * TAU, r = rand(s * 0.3, s * 0.45);
    ctx.beginPath();
    ctx.arc(c + Math.cos(a) * r, c + Math.sin(a) * r, rand(2, 6), 0, TAU);
    ctx.fill();
  }
  ctx.fillStyle = 'rgba(40,22,10,0.95)';
  ctx.beginPath(); ctx.ellipse(c, c, s * 0.1, s * 0.16, rand(0, 3), 0, TAU); ctx.fill();
}));

const ringTex = makeCanvasTex(64, (ctx, s) => {
  ctx.strokeStyle = 'rgba(120,255,255,1)';
  ctx.lineWidth = 5;
  ctx.beginPath(); ctx.arc(s / 2, s / 2, s / 2 - 5, 0, TAU); ctx.stroke();
  ctx.fillStyle = 'rgba(120,255,255,1)';
  ctx.beginPath(); ctx.arc(s / 2, s / 2, 4, 0, TAU); ctx.fill();
});

// ---------------------------------------------------------------------------
// ゴキブリのモデル（すべてプログラムで組み立て）
// ---------------------------------------------------------------------------
const GEO = {
  sphere: new THREE.SphereGeometry(1, 18, 12),
  leg: new THREE.CylinderGeometry(0.022, 0.014, 1, 5).translate(0, -0.5, 0),
  shin: new THREE.CylinderGeometry(0.014, 0.006, 1, 5).translate(0, -0.5, 0),
  shadow: new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2),
  cone: new THREE.ConeGeometry(0.03, 0.18, 5).rotateX(-Math.PI / 2),
};
function antennaGeo(s) {
  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(0, 0, 0),
    new THREE.Vector3(0.12 * s, 0.06, 0.35),
    new THREE.Vector3(0.3 * s, 0.1, 0.8),
    new THREE.Vector3(0.48 * s, 0.04, 1.25),
  ]);
  return new THREE.TubeGeometry(curve, 12, 0.008, 4, false);
}
GEO.antL = antennaGeo(1);
GEO.antR = antennaGeo(-1);

const MAT = {
  body: new THREE.MeshStandardMaterial({ color: 0x4a230f, roughness: 0.25, metalness: 0.15 }),
  wing: new THREE.MeshStandardMaterial({ color: 0x7a3d15, roughness: 0.18, metalness: 0.1 }),
  dark: new THREE.MeshStandardMaterial({ color: 0x1e0d05, roughness: 0.35 }),
  leg: new THREE.MeshStandardMaterial({ color: 0x3a1c0a, roughness: 0.6 }),
  flyWing: new THREE.MeshStandardMaterial({ color: 0x9a5a22, roughness: 0.15, metalness: 0.1 }),
  shadow: new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, depthWrite: false }),
};

function buildRoach(type) {
  const boss = type === 'boss';
  const bodyMat = boss ? MAT.body.clone() : MAT.body;
  let wingMat = boss ? MAT.wing.clone() : MAT.wing;
  if (boss) { bodyMat.color.set(0x2c0f05); wingMat.color.set(0x5a1a08); }
  if (type === 'flyer') wingMat = MAT.flyWing;

  const root = new THREE.Group();
  const tilt = new THREE.Group();
  root.add(tilt);

  const add = (geo, mat, parent, px, py, pz, sx, sy, sz) => {
    const m = new THREE.Mesh(geo, mat);
    m.position.set(px, py, pz);
    m.scale.set(sx, sy, sz);
    parent.add(m);
    return m;
  };
  add(GEO.sphere, bodyMat, tilt, 0, 0.2, -0.08, 0.34, 0.16, 0.52); // 腹
  add(GEO.sphere, wingMat, tilt, 0, 0.27, 0.33, 0.3, 0.1, 0.17);   // 前胸
  add(GEO.sphere, MAT.dark, tilt, 0, 0.17, 0.5, 0.13, 0.1, 0.1);   // 頭
  add(GEO.cone, MAT.dark, tilt, 0.08, 0.18, -0.62, 1, 1, 1);        // 尾角
  add(GEO.cone, MAT.dark, tilt, -0.08, 0.18, -0.62, 1, 1, 1);

  const wings = [];
  for (const s of [1, -1]) {
    const pivot = new THREE.Group();
    pivot.position.set(0.02 * s, 0.355, 0.28);
    pivot.rotation.order = 'YXZ';
    add(GEO.sphere, wingMat, pivot, 0.12 * s, 0, -0.42, 0.18, 0.035, 0.5);
    tilt.add(pivot);
    wings.push({ pivot, s });
  }

  const antennae = [];
  for (const s of [1, -1]) {
    const pivot = new THREE.Group();
    pivot.position.set(0.05 * s, 0.21, 0.57);
    pivot.add(new THREE.Mesh(s > 0 ? GEO.antL : GEO.antR, MAT.dark));
    tilt.add(pivot);
    antennae.push({ pivot, s, ph: Math.random() * TAU });
  }

  const legs = [];
  const zs = [0.3, 0.12, -0.08];
  const splay = [-0.6, 0.05, 0.75];
  for (let i = 0; i < 3; i++) {
    for (const s of [1, -1]) {
      const hip = new THREE.Group();
      hip.position.set(0.16 * s, 0.17, zs[i]);
      hip.rotation.order = 'YXZ';
      const baseY = s * splay[i];
      hip.rotation.set(0, baseY, s * 1.9);
      add(GEO.leg, MAT.leg, hip, 0, 0, 0, 1, 0.35, 1);
      const knee = new THREE.Group();
      knee.position.set(0, -0.35, 0);
      knee.rotation.z = -s * 1.0;
      add(GEO.shin, MAT.leg, knee, 0, 0, 0, 1, 0.48, 1);
      hip.add(knee);
      tilt.add(hip);
      // 三脚歩行：左前・右中・左後 が同じタイミング
      const group = (i % 2 === 0) === (s > 0) ? 0 : Math.PI;
      legs.push({ hip, knee, s, baseY, ph: group });
    }
  }

  const shadow = new THREE.Mesh(GEO.shadow, MAT.shadow);
  shadow.scale.set(1.3, 1, 1.9);
  shadow.position.y = 0.01;
  shadow.renderOrder = 1;
  root.add(shadow);

  return { root, tilt, wings, antennae, legs, shadow, bodyMat, wingMat };
}

// ---------------------------------------------------------------------------
// エフェクト：粒子・体液のシミ・得点ポップ・弾道
// ---------------------------------------------------------------------------
const Particles = (() => {
  const N = 600;
  const pos = new Float32Array(N * 3).fill(-100);
  const col = new Float32Array(N * 3);
  const vel = new Float32Array(N * 3);
  const life = new Float32Array(N);
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const pts = new THREE.Points(geo, new THREE.PointsMaterial({ size: 0.012, vertexColors: true, transparent: true, depthWrite: false }));
  pts.frustumCulled = false;
  scene.add(pts);
  let head = 0;
  const c = new THREE.Color();
  return {
    emit(p, n, color, speed, up = 1) {
      c.set(color);
      for (let k = 0; k < n; k++) {
        const i = head; head = (head + 1) % N;
        pos[i * 3] = p.x; pos[i * 3 + 1] = p.y + 0.01; pos[i * 3 + 2] = p.z;
        const a = Math.random() * TAU, sp = speed * rand(0.3, 1);
        vel[i * 3] = Math.cos(a) * sp; vel[i * 3 + 1] = rand(0.3, 1) * speed * up * 1.6; vel[i * 3 + 2] = Math.sin(a) * sp;
        const v = rand(0.7, 1.2);
        col[i * 3] = c.r * v; col[i * 3 + 1] = c.g * v; col[i * 3 + 2] = c.b * v;
        life[i] = rand(0.6, 1.4);
      }
      geo.attributes.color.needsUpdate = true;
    },
    update(dt) {
      for (let i = 0; i < N; i++) {
        if (life[i] <= 0) continue;
        life[i] -= dt;
        if (life[i] <= 0) { pos[i * 3 + 1] = -100; continue; }
        vel[i * 3 + 1] -= 6 * dt;
        pos[i * 3] += vel[i * 3] * dt; pos[i * 3 + 1] += vel[i * 3 + 1] * dt; pos[i * 3 + 2] += vel[i * 3 + 2] * dt;
        if (pos[i * 3 + 1] < 0.002) { pos[i * 3 + 1] = 0.002; vel[i * 3] *= 0.2; vel[i * 3 + 2] *= 0.2; vel[i * 3 + 1] = 0; }
      }
      geo.attributes.position.needsUpdate = true;
    },
  };
})();

const Splats = (() => {
  const pool = [];
  for (let i = 0; i < 40; i++) {
    const m = new THREE.Mesh(GEO.shadow, new THREE.MeshBasicMaterial({
      map: splatTex[i % 3], transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2,
    }));
    m.visible = false; m.renderOrder = 2; m.userData.t = 0;
    scene.add(m); pool.push(m);
  }
  let head = 0;
  return {
    add(p, size) {
      const m = pool[head]; head = (head + 1) % pool.length;
      m.position.set(p.x, 0.003 + head * 0.00005, p.z);
      m.rotation.y = Math.random() * TAU;
      m.scale.setScalar(size);
      m.material.opacity = 1; m.visible = true; m.userData.t = 12;
    },
    update(dt) {
      for (const m of pool) {
        if (!m.visible) continue;
        m.userData.t -= dt;
        if (m.userData.t < 2) m.material.opacity = Math.max(0, m.userData.t / 2);
        if (m.userData.t <= 0) m.visible = false;
      }
    },
    clear() { pool.forEach((m) => { m.visible = false; }); },
  };
})();

const Popups = (() => {
  const pool = [];
  for (let i = 0; i < 10; i++) {
    const c = document.createElement('canvas'); c.width = 256; c.height = 128;
    const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace;
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false }));
    s.renderOrder = 900; s.visible = false;
    s.userData = { c, tex, t: 0 };
    scene.add(s); pool.push(s);
  }
  let head = 0;
  return {
    show(p, text, color = '#ffe066', big = false) {
      const s = pool[head]; head = (head + 1) % pool.length;
      const { c, tex } = s.userData;
      const ctx = c.getContext('2d');
      ctx.clearRect(0, 0, 256, 128);
      ctx.font = `900 64px ${FONT}`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.lineWidth = 10; ctx.strokeStyle = 'rgba(0,0,0,0.85)'; ctx.strokeText(text, 128, 64);
      ctx.fillStyle = color; ctx.fillText(text, 128, 64);
      tex.needsUpdate = true;
      s.position.copy(p); s.position.y += 0.08;
      const k = big ? 0.5 : 0.22;
      s.scale.set(k, k / 2, 1);
      s.material.opacity = 1; s.visible = true; s.userData.t = 0.9;
    },
    update(dt) {
      for (const s of pool) {
        if (!s.visible) continue;
        s.userData.t -= dt;
        s.position.y += dt * 0.35;
        s.material.opacity = Math.min(1, s.userData.t * 2);
        if (s.userData.t <= 0) s.visible = false;
      }
    },
  };
})();

const Tracers = (() => {
  const pool = [];
  for (let i = 0; i < 8; i++) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3));
    const l = new THREE.Line(g, new THREE.LineBasicMaterial({ color: 0xaaffff, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false }));
    l.frustumCulled = false; l.visible = false; l.userData.t = 0;
    scene.add(l); pool.push(l);
  }
  let head = 0;
  return {
    fire(a, b) {
      const l = pool[head]; head = (head + 1) % pool.length;
      const p = l.geometry.attributes.position;
      p.setXYZ(0, a.x, a.y, a.z); p.setXYZ(1, b.x, b.y, b.z); p.needsUpdate = true;
      l.visible = true; l.userData.t = 0.09; l.material.opacity = 1;
    },
    update(dt) {
      for (const l of pool) {
        if (!l.visible) continue;
        l.userData.t -= dt;
        l.material.opacity = Math.max(0, l.userData.t / 0.09);
        if (l.userData.t <= 0) l.visible = false;
      }
    },
  };
})();

// ダメージ時に視界が赤くなる演出
const hurtSphere = new THREE.Mesh(
  new THREE.SphereGeometry(0.25, 16, 12),
  new THREE.MeshBasicMaterial({ color: 0xff1a1a, side: THREE.BackSide, transparent: true, opacity: 0, depthTest: false })
);
hurtSphere.renderOrder = 2000;
camera.add(hurtSphere);
let hurtT = 0;

// ---------------------------------------------------------------------------
// UI パネル（手首のHUDと、目の前に出るお知らせ）
// ---------------------------------------------------------------------------
const hud = makePanel(0.15, 0.09, 512, 307);
let hudKey = '';
const announce = makePanel(1.0, 0.5, 1024, 512);
scene.add(announce.mesh);
announce.mesh.visible = false;
let announceT = 0;     // 0 以下なら表示し続ける（タイトル等）
let announceSticky = false;

function drawHUD() {
  const key = `${G.score}|${G.wave}|${G.lives}|${G.combo}|${remaining()}|${G.state}`;
  if (key === hudKey) return;
  hudKey = key;
  const { ctx, canvas } = hud;
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  roundRect(ctx, 6, 6, W - 12, H - 12, 28);
  ctx.fillStyle = 'rgba(20,12,6,0.78)'; ctx.fill();
  ctx.lineWidth = 5; ctx.strokeStyle = 'rgba(255,176,46,0.9)'; ctx.stroke();
  ctx.textBaseline = 'middle';
  ctx.fillStyle = '#ffd36b'; ctx.font = `900 40px ${FONT}`; ctx.textAlign = 'left';
  ctx.fillText('SCORE', 30, 50);
  ctx.textAlign = 'right'; ctx.fillStyle = '#fff'; ctx.font = `900 56px ${FONT}`;
  ctx.fillText(String(G.score), W - 30, 52);
  ctx.textAlign = 'left'; ctx.font = `bold 38px ${FONT}`; ctx.fillStyle = '#e8dccd';
  ctx.fillText(`WAVE ${Math.max(1, G.wave)}`, 30, 120);
  ctx.textAlign = 'right';
  ctx.fillText(`のこり ${remaining()}`, W - 30, 120);
  ctx.textAlign = 'left'; ctx.font = `48px ${FONT}`;
  let hearts = '';
  for (let i = 0; i < MAX_LIVES; i++) hearts += i < G.lives ? '♥' : '♡';
  ctx.fillStyle = '#ff5a5a'; ctx.fillText(hearts, 30, 190);
  if (G.combo > 1) {
    ctx.textAlign = 'right'; ctx.fillStyle = '#7dffef'; ctx.font = `900 50px ${FONT}`;
    ctx.fillText(`x${G.combo}`, W - 30, 190);
  }
  ctx.textAlign = 'center'; ctx.font = `bold 30px ${FONT}`; ctx.fillStyle = '#c9b8a5';
  ctx.fillText(`ハイスコア ${G.best}`, W / 2, 255);
  hud.update();
}

function showAnnounce(title, lines = [], dur = 2.5, color = '#ffd36b') {
  const { ctx, canvas } = announce;
  const W = canvas.width, H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  roundRect(ctx, 10, 10, W - 20, H - 20, 40);
  ctx.fillStyle = 'rgba(18,10,5,0.82)'; ctx.fill();
  ctx.lineWidth = 8; ctx.strokeStyle = color; ctx.stroke();
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.font = `900 ${title.length > 8 ? 92 : 120}px ${FONT}`;
  ctx.lineWidth = 12; ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.strokeText(title, W / 2, 150);
  ctx.fillStyle = color; ctx.fillText(title, W / 2, 150);
  ctx.font = `bold 46px ${FONT}`; ctx.fillStyle = '#f2e8dc';
  lines.forEach((l, i) => ctx.fillText(l, W / 2, 285 + i * 66));
  announce.update();
  announce.mesh.visible = true;
  announceT = dur;
  announceSticky = dur <= 0;
  placeAnnounce(true);
}

const _v1 = new THREE.Vector3(), _v2 = new THREE.Vector3(), _v3 = new THREE.Vector3();
const camPos = new THREE.Vector3(), camFwd = new THREE.Vector3(), camUp = new THREE.Vector3();
function placeAnnounce(snap) {
  _v1.copy(camFwd);
  if (renderer.xr.isPresenting) _v1.y = 0; // MRでは目の高さに水平に出す
  if (_v1.lengthSq() < 1e-4) _v1.set(0, 0, -1);
  _v1.normalize();
  _v2.copy(camPos).addScaledVector(_v1, 1.6);
  if (renderer.xr.isPresenting) _v2.y = camPos.y + 0.05;
  else _v2.y += 0.15;
  if (snap) announce.mesh.position.copy(_v2);
  else announce.mesh.position.lerp(_v2, 0.04);
  announce.mesh.lookAt(camPos);
}

// ---------------------------------------------------------------------------
// 武器（殺虫ブラスター）とコントローラー
// ---------------------------------------------------------------------------
function buildBlaster() {
  const g = new THREE.Group();
  const can = new THREE.Mesh(
    new THREE.CylinderGeometry(0.022, 0.022, 0.11, 16).rotateX(Math.PI / 2),
    new THREE.MeshStandardMaterial({ color: 0xffb02e, roughness: 0.3, metalness: 0.5 })
  );
  can.position.z = 0.075; g.add(can);
  const band = new THREE.Mesh(
    new THREE.CylinderGeometry(0.0225, 0.0225, 0.03, 16).rotateX(Math.PI / 2),
    new THREE.MeshStandardMaterial({ color: 0xc81e1e, roughness: 0.4 })
  );
  band.position.z = 0.07; g.add(band);
  const nozzle = new THREE.Mesh(
    new THREE.CylinderGeometry(0.006, 0.01, 0.04, 8).rotateX(Math.PI / 2),
    new THREE.MeshStandardMaterial({ color: 0x222222, roughness: 0.5 })
  );
  nozzle.position.z = 0.0; g.add(nozzle);
  const tipMat = new THREE.MeshBasicMaterial({ color: 0x66ffff, transparent: true, opacity: 0.6 });
  const tip = new THREE.Mesh(new THREE.SphereGeometry(0.008, 10, 8), tipMat);
  tip.position.z = -0.022; g.add(tip);
  g.userData.tipMat = tipMat;
  return g;
}

const laserGeo = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -1)]);
const aims = []; // { obj, kind:'xr'|'mouse', laser, reticle, origin, dir, floor, onFloor, gun }

function makeReticle() {
  const r = new THREE.Mesh(
    new THREE.PlaneGeometry(0.08, 0.08).rotateX(-Math.PI / 2),
    new THREE.MeshBasicMaterial({ map: ringTex, transparent: true, depthWrite: false, opacity: 0.85 })
  );
  r.renderOrder = 3; r.visible = false;
  scene.add(r);
  return r;
}

for (let i = 0; i < 2; i++) {
  const ctrl = renderer.xr.getController(i);
  scene.add(ctrl);
  const gun = buildBlaster();
  gun.visible = false;
  ctrl.add(gun);
  const laser = new THREE.Line(laserGeo, new THREE.LineBasicMaterial({ color: 0x66ffff, transparent: true, opacity: 0.5 }));
  laser.visible = false;
  ctrl.add(laser);
  const aim = { obj: ctrl, kind: 'xr', laser, gun, reticle: makeReticle(), origin: new THREE.Vector3(), dir: new THREE.Vector3(), floor: new THREE.Vector3(), onFloor: false, source: null, active: false, flash: 0 };
  aims.push(aim);
  ctrl.addEventListener('connected', (e) => {
    if (e.data.targetRayMode !== 'tracked-pointer') return;
    aim.source = e.data; aim.active = true;
    gun.visible = true; laser.visible = true;
    if (e.data.handedness === 'left') attachHUD(ctrl);
  });
  ctrl.addEventListener('disconnected', () => {
    aim.active = false; aim.source = null;
    gun.visible = false; laser.visible = false; aim.reticle.visible = false;
    if (hud.mesh.parent === ctrl) attachHUD(null);
  });
  ctrl.addEventListener('selectstart', () => { if (aim.active) shoot(aim); });
}

const mouseAim = { kind: 'mouse', reticle: makeReticle(), origin: new THREE.Vector3(), dir: new THREE.Vector3(), floor: new THREE.Vector3(), onFloor: false, active: false, flash: 0, ndc: new THREE.Vector2() };
aims.push(mouseAim);

function attachHUD(ctrl) {
  if (hud.mesh.parent) hud.mesh.parent.remove(hud.mesh);
  if (ctrl) {
    hud.mesh.position.set(0, 0.07, 0.06);
    hud.mesh.rotation.set(-0.35, 0, 0);
    hud.mesh.scale.setScalar(1);
    ctrl.add(hud.mesh);
  } else {
    hud.mesh.position.set(-0.3, -0.2, -0.7);
    hud.mesh.rotation.set(0, 0.2, 0);
    hud.mesh.scale.setScalar(1.6);
    camera.add(hud.mesh);
  }
}
attachHUD(null);

function haptic(aim, intensity, ms) {
  try {
    const gp = aim.source && aim.source.gamepad;
    if (!gp) return;
    if (gp.hapticActuators && gp.hapticActuators[0] && gp.hapticActuators[0].pulse) gp.hapticActuators[0].pulse(intensity, ms);
    else if (gp.vibrationActuator && gp.vibrationActuator.playEffect) gp.vibrationActuator.playEffect('dual-rumble', { duration: ms, strongMagnitude: intensity, weakMagnitude: intensity });
  } catch (_) { /* 振動非対応 */ }
}
function hapticAll(intensity, ms) { aims.forEach((a) => a.active && haptic(a, intensity, ms)); }

const _q = new THREE.Quaternion();
function updateAim(aim) {
  if (!aim.active) { aim.reticle.visible = false; return; }
  if (aim.kind === 'xr') {
    aim.obj.updateMatrixWorld();
    aim.origin.setFromMatrixPosition(aim.obj.matrixWorld);
    aim.obj.getWorldQuaternion(_q);
    aim.dir.set(0, 0, -1).applyQuaternion(_q).normalize();
  } else {
    aim.origin.copy(camPos);
    aim.dir.set(aim.ndc.x, aim.ndc.y, 0.5).unproject(camera).sub(camPos).normalize();
  }
  aim.onFloor = false;
  let len = 6;
  if (aim.dir.y < -0.02) {
    const t = -aim.origin.y / aim.dir.y;
    if (t < 10) { aim.floor.copy(aim.origin).addScaledVector(aim.dir, t); aim.onFloor = true; len = t; }
  }
  const hit = rayHit(aim.origin, aim.dir);
  if (hit) len = Math.min(len, hit.t);
  aim.reticle.visible = aim.onFloor;
  if (aim.onFloor) aim.reticle.position.set(aim.floor.x, 0.004, aim.floor.z);
  if (aim.laser) {
    aim.laser.scale.z = len;
    aim.laser.material.color.set(hit ? 0xff5050 : 0x66ffff);
  }
  if (aim.gun) {
    aim.flash = Math.max(0, aim.flash - 0.08);
    aim.gun.userData.tipMat.opacity = 0.5 + aim.flash;
    aim.gun.children[3].scale.setScalar(1 + aim.flash * 3);
  }
}

// ---------------------------------------------------------------------------
// ゴキブリ本体の動き
// ---------------------------------------------------------------------------
const MAX_LIVES = 3; // ハードモード：ライフ3つ
const roaches = [];

class Roach {
  constructor(type, x, z) {
    this.type = type;
    this.m = buildRoach(type);
    this.size = type === 'boss' ? 0.3 : type === 'flyer' ? 0.075 : rand(0.058, 0.075);
    this.m.root.scale.setScalar(this.size);
    this.pos = new THREE.Vector3(x, 0, z);
    this.vel = new THREE.Vector3();
    this.heading = Math.random() * TAU;
    this.wander = 0;
    this.state = 'walk';
    this.timer = rand(0.5, 1.5);
    this.gait = Math.random() * TAU;
    this.t = Math.random() * 10;
    this.hp = type === 'boss' ? 10 + G.wave * 2 : 1;
    this.maxHp = this.hp;
    this.baseSpeed = type === 'boss' ? 0.16 : type === 'flyer' ? 0.24 : rand(0.2, 0.28);
    this.flyDelay = type === 'flyer' ? rand(1.5, 4.5) : Infinity;
    this.minionT = 3;
    this.dead = false;
    this.deadT = 0;
    this.falling = false;
    this.flash = 0;
    this.buzz = null;
    this.scatter = false;
    this.spawnT = 0;
    this.m.root.position.copy(this.pos);
    scene.add(this.m.root);
  }

  get flying() { return this.state === 'flyprep' || this.state === 'fly'; }

  center(out) {
    out.copy(this.pos);
    if (!this.flying && !this.falling) out.y += this.size * 0.25;
    return out;
  }
  get hitRadius() { return this.type === 'boss' ? this.size * 0.75 : this.size * 0.7 + 0.035; }

  speed() { return this.baseSpeed * G.speedMul; }

  update(dt) {
    this.t += dt;
    this.spawnT = Math.min(1, this.spawnT + dt * 3);
    const m = this.m;
    if (this.flash > 0 && this.type === 'boss') {
      this.flash -= dt;
      m.bodyMat.emissive.setRGB(Math.max(0, this.flash) * 6, 0, 0);
    }
    if (this.dead) return this.updateDead(dt);

    if (this.state === 'flyprep') return this.updateFlyPrep(dt);
    if (this.state === 'fly') return this.updateFly(dt);

    // ---- 地面を歩く ----
    this.timer -= dt;
    let fleeFrom = null;
    for (const a of aims) {
      if (a.active && a.onFloor && dist2D(a.floor, this.pos) < (this.type === 'boss' ? 0.2 : 0.45)) fleeFrom = a.floor;
    }
    if (this.scatter) fleeFrom = camPos;
    if (fleeFrom && this.state !== 'dash' && this.type !== 'boss') {
      this.state = 'dash';
      this.timer = rand(0.45, 0.9);
      this.heading = Math.atan2(this.pos.x - fleeFrom.x, this.pos.z - fleeFrom.z) + rand(-0.6, 0.6);
    }
    if (this.timer <= 0) {
      const r = Math.random();
      if (this.state === 'pause') { this.state = 'walk'; this.timer = rand(0.6, 2); }
      else if (r < 0.28) { this.state = 'pause'; this.timer = rand(0.2, 1.0); }
      else if (r < 0.5 && this.type !== 'boss') { this.state = 'dash'; this.timer = rand(0.3, 0.7); this.heading += rand(-1.5, 1.5); }
      else { this.state = 'walk'; this.timer = rand(0.5, 1.6); }
    }
    const toPlayer = Math.atan2(camPos.x - this.pos.x, camPos.z - this.pos.z);
    const dPlayer = dist2D(camPos, this.pos);
    if (this.state === 'walk' && !this.scatter) {
      this.wander = THREE.MathUtils.clamp(this.wander + rand(-1, 1) * 6 * dt, -1.3, 1.3);
      this.heading = lerpAngle(this.heading, toPlayer + this.wander, 2.2 * dt);
    }
    if (dPlayer > 4.5 && !this.scatter) this.heading = lerpAngle(this.heading, toPlayer, 4 * dt);

    const spd = this.state === 'pause' ? 0 : this.state === 'dash' ? this.speed() * 3.4 : this.speed();
    this.pos.x += Math.sin(this.heading) * spd * dt;
    this.pos.z += Math.cos(this.heading) * spd * dt;

    m.root.position.copy(this.pos);
    m.root.rotation.set(0, this.heading, 0);
    m.root.scale.setScalar(this.size * (0.3 + 0.7 * this.spawnT));
    this.animateLegs(dt, spd);
    this.animateAntennae(spd);

    if (this.scatter) {
      if (dPlayer > 5.5) this.remove();
      return;
    }
    if (G.state !== 'playing') return;

    // ボスは子分を産む
    if (this.type === 'boss') {
      this.minionT -= dt;
      if (this.minionT <= 0) {
        this.minionT = rand(3, 4.5);
        spawnRoach('normal', this.pos.x + rand(-0.2, 0.2), this.pos.z + rand(-0.2, 0.2));
      }
    }
    // 足元に到達
    if (dPlayer < (this.type === 'boss' ? 0.35 : 0.22)) {
      loseLife(this.type === 'boss' ? 'ボスに踏まれた！' : '足を登ってきた！！');
      Splats.add(this.pos, this.size * 3);
      this.remove();
      return;
    }
    // 飛ぶゴキブリ
    this.flyDelay -= dt;
    if (this.flyDelay <= 0 && dPlayer > 0.9) {
      this.state = 'flyprep';
      this.timer = 0.8;
      this.buzz = Snd.buzz(this.pos);
    }
  }

  animateLegs(dt, spd) {
    this.gait += (spd / this.size) * dt * 2.4;
    const amp = spd > 0 ? 0.45 : 0.04;
    for (const L of this.m.legs) {
      const p = this.gait + L.ph;
      L.hip.rotation.y = L.baseY + Math.sin(p) * amp;
      L.hip.rotation.x = Math.max(0, Math.cos(p)) * amp * 0.5 * -L.s;
    }
  }
  animateAntennae(spd) {
    for (const A of this.m.antennae) {
      A.pivot.rotation.y = Math.sin(this.t * (spd > 0 ? 3 : 7) + A.ph) * 0.35 * A.s;
      A.pivot.rotation.x = -0.1 + Math.sin(this.t * 5 + A.ph) * 0.15;
    }
  }

  updateFlyPrep(dt) {
    this.timer -= dt;
    const k = 1 - Math.max(0, this.timer) / 0.8;
    for (const W of this.m.wings) {
      W.pivot.rotation.set(-0.5 * k, -W.s * 0.7 * k, W.s * Math.sin(this.t * 70) * 0.5 * k);
    }
    this.animateAntennae(0);
    if (this.buzz) Snd.setPos(this.buzz.panner, this.pos);
    if (this.timer <= 0) {
      this.state = 'fly';
      this.m.shadow.visible = false;
      this.vel.set(0, 1.6, 0);
    }
  }

  updateFly(dt) {
    _v1.copy(camPos).sub(this.pos);
    const d = _v1.length();
    _v1.normalize().multiplyScalar(1.3 * G.speedMul);
    if (this.scatter) { _v1.negate(); _v1.y = 0.6; }
    // ふらふら飛ぶ
    _v1.x += Math.sin(this.t * 3.1) * 0.8;
    _v1.y += Math.sin(this.t * 4.3) * 0.5;
    _v1.z += Math.cos(this.t * 2.7) * 0.8;
    this.vel.lerp(_v1, Math.min(1, 2.2 * dt));
    this.pos.addScaledVector(this.vel, dt);
    if (this.pos.y < 0.05) { this.pos.y = 0.05; this.vel.y = Math.abs(this.vel.y); }
    const m = this.m;
    m.root.position.copy(this.pos);
    _v2.copy(this.pos).add(this.vel);
    m.root.lookAt(_v2);
    for (const W of m.wings) W.pivot.rotation.set(-0.5, -W.s * 0.7, W.s * (0.2 + Math.sin(this.t * 80) * 0.6));
    for (const L of m.legs) L.hip.rotation.y = L.baseY + Math.sin(this.t * 20 + L.ph) * 0.2;
    if (this.buzz) Snd.setPos(this.buzz.panner, this.pos);
    if (G.state === 'playing' && d < 0.2) {
      loseLife('顔に飛んできた！！');
      Particles.emit(this.pos, 20, 0x8a6a1e, 0.6);
      this.remove();
    } else if (G.state !== 'playing' && d > 6) {
      this.remove();
    }
  }

  hit(aim, point) {
    if (this.dead) return;
    this.hp -= 1;
    this.flash = 0.12;
    if (this.type === 'boss' && this.hp > 0) {
      Particles.emit(point, 14, 0x9a7a22, 0.7);
      Snd.splat(this.pos, false);
      haptic(aim, 0.5, 40);
      this.heading += rand(-1, 1);
      addScore(50, point);
      return;
    }
    this.kill(aim, point);
  }

  kill(aim, point) {
    this.dead = true;
    this.deadT = 0;
    if (this.buzz) { this.buzz.stop(); this.buzz = null; }
    const boss = this.type === 'boss';
    Particles.emit(point, boss ? 120 : 34, 0x8f7420, boss ? 1.4 : 0.8);
    Particles.emit(point, boss ? 40 : 10, 0x3a1c0a, boss ? 1.0 : 0.5);
    Snd.splat(this.pos, boss);
    haptic(aim, boss ? 1 : 0.7, boss ? 200 : 60);
    if (this.flying) {
      this.falling = true;
      this.vel.multiplyScalar(0.3);
    } else {
      Splats.add(this.pos, this.size * (boss ? 4 : 3.2));
    }
    this.state = 'dead';
    // 得点
    const now = G.time;
    G.combo = now - G.lastKill < 1.6 ? Math.min(5, G.combo + 1) : 1;
    G.lastKill = now;
    G.kills++;
    const base = boss ? 2000 : this.type === 'flyer' ? (this.falling ? 300 : 200) : 100;
    addScore(base * G.combo, point, boss);
    if (boss) { Snd.jingle([523, 659, 784, 1047], 0.09, 'square'); showAnnounce('ボス撃破！', [`+${base * G.combo}`], 1.8, '#7dffef'); }
  }

  updateDead(dt) {
    const m = this.m;
    this.deadT += dt;
    if (this.falling) {
      this.vel.y -= 9.8 * dt;
      this.pos.addScaledVector(this.vel, dt);
      m.root.position.copy(this.pos);
      m.root.rotation.x += dt * 8;
      if (this.pos.y <= 0) {
        this.pos.y = 0;
        this.falling = false;
        Splats.add(this.pos, this.size * 3.2);
        Particles.emit(this.pos, 12, 0x8f7420, 0.5);
        m.shadow.visible = true;
      }
      return;
    }
    m.root.position.copy(this.pos);
    m.root.rotation.set(0, this.heading, 0);
    m.tilt.rotation.z = Math.PI;
    m.tilt.position.y = 0.4;
    m.shadow.visible = false;
    for (const W of m.wings) W.pivot.rotation.set(0, 0, 0);
    // ピクピク…
    const tw = Math.max(0, 1 - this.deadT / 1.8);
    for (const L of m.legs) L.hip.rotation.y = L.baseY + Math.sin(this.t * 30 + L.ph * 3) * 0.5 * tw;
    if (this.deadT > 1.8) m.root.scale.setScalar(this.size * Math.max(0.001, 1 - (this.deadT - 1.8) * 2));
    if (this.deadT > 2.3) this.remove();
  }

  remove() {
    if (this.buzz) { this.buzz.stop(); this.buzz = null; }
    scene.remove(this.m.root);
    if (this.type === 'boss') { this.m.bodyMat.dispose(); this.m.wingMat.dispose(); }
    const i = roaches.indexOf(this);
    if (i >= 0) roaches.splice(i, 1);
  }
}

function spawnRoach(type, x, z) {
  if (x === undefined) {
    const a = Math.random() * TAU, d = type === 'boss' ? rand(2.0, 2.6) : rand(1.4, 2.8);
    x = camPos.x + Math.sin(a) * d;
    z = camPos.z + Math.cos(a) * d;
  }
  const r = new Roach(type, x, z);
  r.heading = Math.atan2(camPos.x - x, camPos.z - z);
  roaches.push(r);
  return r;
}

const _c = new THREE.Vector3();
function rayHit(origin, dir) {
  let best = null;
  for (const r of roaches) {
    if (r.dead || r.scatter) continue;
    r.center(_c);
    _c.sub(origin);
    const t = _c.dot(dir);
    if (t < 0) continue;
    const d2 = _c.lengthSq() - t * t;
    const R = r.hitRadius;
    if (d2 < R * R && (!best || t < best.t)) best = { r, t };
  }
  return best;
}

// ---------------------------------------------------------------------------
// ゲーム進行
// ---------------------------------------------------------------------------
let best = 0;
try { best = Number(localStorage.getItem('roachBest')) || 0; } catch (_) { /* 保存不可 */ }

const G = {
  state: 'title', score: 0, wave: 0, lives: MAX_LIVES, combo: 1, lastKill: -9,
  queue: [], spawnTimer: 0, spawnInterval: 1.2, waveDelay: 0, clearT: 0,
  time: 0, speedMul: 1, kills: 0, shots: 0, hits: 0, best, endT: 0,
};

function remaining() {
  return G.queue.length + roaches.filter((r) => !r.dead && !r.scatter).length;
}

function addScore(n, p, big = false) {
  G.score += n;
  Popups.show(p, `+${n}`, G.combo > 1 ? '#7dffef' : '#ffe066', big);
}

function titleScreen() {
  G.state = 'title';
  showAnnounce('ゴキブリシューター', ['部屋に出たゴキブリを退治しろ！', 'トリガーを引いてスタート'], 0);
}

function startGame() {
  [...roaches].forEach((r) => r.remove());
  Splats.clear();
  Object.assign(G, { state: 'playing', score: 0, wave: 0, lives: MAX_LIVES, combo: 1, lastKill: -9, kills: 0, shots: 0, hits: 0 });
  Snd.jingle([392, 523, 659, 784]);
  nextWave();
}

function nextWave() {
  G.wave++;
  const n = G.wave;
  G.speedMul = Math.min(2.2, 1 + (n - 1) * 0.12);
  const count = 10 + n * 4;
  const flyRatio = Math.min(0.5, 0.2 + 0.08 * (n - 1)); // 最初から飛ぶ
  G.queue = [];
  for (let i = 0; i < count; i++) G.queue.push(Math.random() < flyRatio ? 'flyer' : 'normal');
  const bossWave = n % 2 === 0; // 2WAVEごとにボス
  if (bossWave) G.queue.splice(2, 0, 'boss');
  G.spawnInterval = Math.max(0.25, 0.8 - n * 0.08);
  G.spawnTimer = 0;
  G.waveDelay = 2.6;
  G.clearT = 0;
  const sub = n === 1 ? ['ハードモード：最初から飛んでくるぞ', `${count}匹 ／ ライフは${MAX_LIVES}つだけ`]
    : bossWave ? ['巨大ゴキブリ「ゴキング」襲来！', `${count}匹 ＋ ボス`]
      : n === 2 ? ['羽音がしたら上を見ろ！', `${count}匹`] : [`${count}匹`, `スピード x${G.speedMul.toFixed(1)}`];
  showAnnounce(`WAVE ${n}`, sub, 2.5, bossWave ? '#ff6a4a' : '#ffd36b');
  Snd.jingle(bossWave ? [196, 185, 175, 165] : [523, 659, 784], 0.12, bossWave ? 'sawtooth' : 'triangle');
}

function loseLife(reason) {
  G.lives--;
  G.combo = 1;
  hurtT = 0.6;
  Snd.hurt();
  hapticAll(1, 300);
  if (G.lives <= 0) { gameOver(); return; }
  showAnnounce(reason, [`ライフ のこり ${G.lives}`], 1.4, '#ff5a5a');
}

function gameOver() {
  G.state = 'gameover';
  G.endT = G.time;
  G.queue = [];
  const acc = G.shots ? Math.round((G.hits / G.shots) * 100) : 0;
  const isBest = G.score > G.best;
  if (isBest) {
    G.best = G.score;
    try { localStorage.setItem('roachBest', String(G.best)); } catch (_) { /* 保存不可 */ }
  }
  roaches.forEach((r) => { r.scatter = true; if (r.flying && r.buzz) { /* 飛んでいる奴はそのまま去る */ } });
  Snd.jingle([392, 330, 262, 196], 0.18, 'sawtooth');
  showAnnounce('GAME OVER', [
    `スコア ${G.score}${isBest ? '  ★ハイスコア！' : ''}`,
    `WAVE ${G.wave} ／ 退治 ${G.kills}匹 ／ 命中率 ${acc}%`,
    'トリガーでもう一度',
  ], 0, '#ff5a5a');
}

function shoot(aim) {
  Snd.init();
  if (G.state === 'title') { startGame(); return; }
  if (G.state === 'gameover') { if (G.time - G.endT > 1.5) startGame(); return; }
  updateAim(aim);
  Snd.zap();
  aim.flash = 0.5;
  G.shots++;
  const hit = rayHit(aim.origin, aim.dir);
  _v3.copy(aim.origin).addScaledVector(aim.dir, 0.03);
  if (hit) {
    G.hits++;
    const p = aim.origin.clone().addScaledVector(aim.dir, hit.t);
    Tracers.fire(_v3, p);
    hit.r.hit(aim, p);
  } else {
    haptic(aim, 0.25, 25);
    const end = aim.onFloor ? aim.floor : aim.origin.clone().addScaledVector(aim.dir, 6);
    Tracers.fire(_v3, end);
    if (aim.onFloor) Particles.emit(aim.floor, 6, 0x88ffff, 0.3);
  }
}

function updateGame(dt) {
  if (G.state !== 'playing') return;
  if (G.waveDelay > 0) { G.waveDelay -= dt; return; }
  if (G.queue.length) {
    G.spawnTimer -= dt;
    if (G.spawnTimer <= 0) {
      G.spawnTimer = G.spawnInterval * rand(0.6, 1.4);
      const type = G.queue.shift();
      const r = spawnRoach(type);
      if (type === 'boss') Snd.roar(r.pos);
    }
  } else if (remaining() === 0) {
    G.clearT += dt;
    if (G.clearT > 0.6 && G.clearT - dt <= 0.6) {
      const bonus = G.wave * 500;
      G.score += bonus;
      showAnnounce('WAVE CLEAR!', [`ボーナス +${bonus}`], 2.2, '#7dffef');
      Snd.jingle([659, 784, 988, 1319], 0.1);
    }
    if (G.clearT > 3) nextWave();
  }
}

// カサカサ音：一番近い歩いているゴキブリから
function updateRustle() {
  let best = null, bd = 2.5;
  for (const r of roaches) {
    if (r.dead || r.flying) continue;
    const d = dist2D(r.pos, camPos);
    if (d < bd) { bd = d; best = r; }
  }
  if (best) {
    const moving = best.state === 'dash' ? 1 : best.state === 'walk' ? 0.55 : 0.05;
    Snd.setRustle(best.pos, 0.25 * moving * (best.type === 'boss' ? 2 : 1));
  } else Snd.setRustle(camPos, 0);
}

// ---------------------------------------------------------------------------
// メインループ
// ---------------------------------------------------------------------------
const clock = new THREE.Clock();
function loop() {
  const dt = Math.min(0.05, clock.getDelta());
  G.time += dt;

  camera.updateMatrixWorld();
  camera.getWorldPosition(camPos);
  camera.getWorldDirection(camFwd);
  camUp.set(0, 1, 0).applyQuaternion(camera.getWorldQuaternion(_q));
  Snd.updateListener(camPos, camFwd, camUp);

  aims.forEach(updateAim);
  updateGame(dt);
  for (const r of [...roaches]) r.update(dt);
  Particles.update(dt);
  Splats.update(dt);
  Popups.update(dt);
  Tracers.update(dt);
  updateRustle();

  if (announce.mesh.visible) {
    placeAnnounce(false);
    if (!announceSticky) {
      announceT -= dt;
      if (announceT <= 0) announce.mesh.visible = false;
    }
  }
  hurtT = Math.max(0, hurtT - dt);
  hurtSphere.material.opacity = hurtT * 0.7;
  hurtSphere.visible = hurtT > 0;
  drawHUD();

  renderer.render(scene, camera);
}
renderer.setAnimationLoop(loop);

// ---------------------------------------------------------------------------
// 開始ボタン・PCお試しモード
// ---------------------------------------------------------------------------
const overlay = document.getElementById('overlay');
const btnXR = document.getElementById('btnXR');
const btnPC = document.getElementById('btnPC');
const status = document.getElementById('status');
let xrMode = null;

async function detectXR() {
  if (!navigator.xr) {
    btnXR.textContent = 'この端末はVR/MR非対応';
    status.textContent = 'Meta Quest のブラウザで開くとMRで遊べます。';
    return;
  }
  try {
    if (await navigator.xr.isSessionSupported('immersive-ar')) xrMode = 'immersive-ar';
    else if (await navigator.xr.isSessionSupported('immersive-vr')) xrMode = 'immersive-vr';
  } catch (_) { /* 判定失敗 */ }
  if (xrMode) {
    btnXR.disabled = false;
    btnXR.textContent = xrMode === 'immersive-ar' ? 'MRで遊ぶ（部屋が見える）' : 'VRで遊ぶ';
  } else {
    btnXR.textContent = 'この端末はVR/MR非対応';
    status.textContent = 'Meta Quest のブラウザで開くとMRで遊べます。';
  }
}
detectXR();

btnXR.addEventListener('click', async () => {
  Snd.init();
  try {
    const session = await navigator.xr.requestSession(xrMode, {
      requiredFeatures: ['local-floor'],
      optionalFeatures: ['hand-tracking'],
    });
    const isAR = xrMode === 'immersive-ar';
    scene.background = isAR ? null : new THREE.Color(0x1a1410);
    pcFloor.visible = !isAR;
    mouseAim.active = false;
    await renderer.xr.setSession(session);
    overlay.style.display = 'none';
    titleScreen();
    session.addEventListener('end', () => {
      [...roaches].forEach((r) => r.remove());
      G.state = 'title';
      overlay.style.display = 'flex';
    });
  } catch (e) {
    status.textContent = '開始できませんでした：' + e.message;
  }
});

// PC：ドラッグで見回し、クリックで撃つ
let yaw = 0, pitch = -0.55, dragging = false, downX = 0, downY = 0, lastX = 0, lastY = 0;
camera.rotation.set(pitch, yaw, 0);
btnPC.addEventListener('click', () => {
  Snd.init();
  overlay.style.display = 'none';
  scene.background = new THREE.Color(0x1a1410);
  pcFloor.visible = true;
  mouseAim.active = true;
  titleScreen();
});
const cvs = renderer.domElement;
function setNdc(e) {
  mouseAim.ndc.set((e.clientX / window.innerWidth) * 2 - 1, -(e.clientY / window.innerHeight) * 2 + 1);
}
cvs.addEventListener('pointerdown', (e) => { dragging = true; downX = lastX = e.clientX; downY = lastY = e.clientY; setNdc(e); });
cvs.addEventListener('pointermove', (e) => {
  setNdc(e);
  if (!dragging || renderer.xr.isPresenting) return;
  yaw -= (e.clientX - lastX) * 0.004;
  pitch = THREE.MathUtils.clamp(pitch - (e.clientY - lastY) * 0.004, -1.4, 0.6);
  lastX = e.clientX; lastY = e.clientY;
  camera.rotation.set(pitch, yaw, 0);
});
cvs.addEventListener('pointerup', (e) => {
  dragging = false;
  if (Math.hypot(e.clientX - downX, e.clientY - downY) < 6 && mouseAim.active) { setNdc(e); shoot(mouseAim); }
});
window.addEventListener('resize', () => {
  if (renderer.xr.isPresenting) return;
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

// 動作確認用（自動テストから状態を見られるように）
window.__game = { G, roaches, spawnRoach, shoot: () => shoot(mouseAim), mouseAim };
window.__proj = (p) => {
  const v = new THREE.Vector3(p.x, p.y + 0.015, p.z).project(camera);
  return { x: (v.x + 1) / 2 * window.innerWidth, y: (1 - v.y) / 2 * window.innerHeight };
};
