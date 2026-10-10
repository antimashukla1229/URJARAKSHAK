const TOKEN = () => localStorage.getItem('urja_token');

const authFetch = (u, o = {}) => fetch(u, {
  ...o, 
  headers: { ...(o.headers || {}), Authorization: 'Bearer ' + TOKEN() }
}).then(r => {
  if (r.status === 401 && !location.pathname.endsWith('login.html')) { 
    localStorage.removeItem('urja_token'); 
    location.href = 'login.html'; 
  }
  return r;
});

const J = (u, o) => authFetch(u, o).then(r => { 
  if (!r.ok) throw new Error(r.status); 
  return r.json(); 
});

async function logout() {
  await authFetch('/api/auth/logout', { method: 'POST' }).catch(() => {});
  localStorage.removeItem('urja_token'); 
  location.href = 'login.html';
}

const COL = { r: '#ff4d4f', a: '#f5a623', g: '#2ecc71' };
const NAME = { r: 'ACT NOW', a: 'WATCH', g: 'HEALTHY' };
const inr = x => '₹' + Math.round(x).toLocaleString('en-IN');
const qs = k => new URLSearchParams(location.search).get(k);

let TR = [], TH = {};

const lvl = t => {
  const h = TH[t.id]?.hoursAbove110C || 0, u = t.unexplainedPct;
  return (u >= 12 || h >= 24) ? 'r' : (u >= 4 || h > 0) ? 'a' : 'g';
};

async function loadBase() {
  const [tr, th] = await Promise.all([J('/api/transformers'), J('/api/thermal')]);
  TR = tr; 
  TH = {}; 
  th.forEach(t => TH[t.id] = t);
}

function nav(active) {
  if (!TOKEN()) { location.href = 'login.html'; return; }
  
  const pages = [
    ['index.html', 'Overview'], 
    ['health.html', 'Transformer Health'], 
    ['redteam.html', 'Red-Team'], 
    ['consumer.html', 'Consumer Portal'],
    ['search.html', 'Search'], 
    ['alerts.html', 'Alerts'], 
    ['hardware.html', 'Hardware']
  ];

  document.body.insertAdjacentHTML('afterbegin',
    `<header>
      <div>
        <h1>⚡ Urja<span>Rakshak</span></h1>
        <div class="sub">AI-powered grid intelligence · simulated data</div>
      </div>
      <nav>
        ${pages.map(p => `<a href="${p[0]}" class="${p[0] === active ? 'on' : ''}">${p[1]}</a>`).join('')}
        <a id="who" href="profile.html" class="${active === 'profile.html' ? 'on' : ''}">👤 Profile</a>
        <button class="ghost" style="padding:5px 10px" onclick="logout()">Log out</button>
      </nav>
    </header>`);

  const R = { officer: 'DISCOM officer', inspector: 'Field inspector' };
  J('/api/profile').then(p => {
    document.getElementById('who').textContent = '👤 ' + p.fullName + ' · ' + (R[p.role] || p.role);
    if (!p.complete && active !== 'profile.html' && !sessionStorage.getItem('skipProfile')) location.href = 'profile.html?first=1';
  }).catch(() => {});

  startReminders();
}

function lineChart(id, series, opt = {}) {
  const W = 400, H = 170, p = { l: 34, r: 8, t: 10, b: 22 };
  const all = series.flatMap(s => s.data).concat(opt.ref ? [opt.ref] : []);
  let min = Math.min(...all), max = Math.max(...all);
  if (min === max) max = min + 1;

  const n = series[0].data.length;
  const x = i => p.l + i * (W - p.l - p.r) / (n - 1);
  const y = v => H - p.b - (v - min) * (H - p.t - p.b) / (max - min);

  let g = '';
  for (let k = 0; k <= 4; k++) {
    const v = min + (max - min) * k / 4;
    g += `<line x1="${p.l}" x2="${W - p.r}" y1="${y(v)}" y2="${y(v)}" stroke="#222c3d"/><text x="2" y="${y(v) + 3}" fill="#8b95a7" font-size="10">${v.toFixed((max - min) < 10 ? 1 : 0)}</text>`;
  }

  for (let i = 0; i < n; i += 5) {
    g += `<text x="${x(i)}" y="${H - 6}" fill="#8b95a7" font-size="10" text-anchor="middle">D${i + 1}</text>`;
  }

  if (opt.ref) {
    g += `<line x1="${p.l}" x2="${W - p.r}" y1="${y(opt.ref)}" y2="${y(opt.ref)}" stroke="#f5a623" stroke-dasharray="4 3"/><text x="${W - p.r}" y="${y(opt.ref) - 3}" fill="#f5a623" font-size="10" text-anchor="end">${opt.ref}°C limit</text>`;
  }

  const paths = series.map(s => `<polyline fill="none" stroke="${s.color}" stroke-width="2" points="${s.data.map((v, i) => x(i) + ',' + y(v)).join(' ')}"/>`).join('');
  const leg = series.filter(s => s.label).map((s, i) => `<text x="${p.l + 6 + i * 80}" y="${p.t + 8}" fill="${s.color}" font-size="11">● ${s.label}</text>`).join('');

  document.getElementById(id).innerHTML = `<svg viewBox="0 0 ${W} ${H}">${g}${paths}${leg}</svg>`;
}
/* ERIC chatbot: Ensure script loads on every page */
(function () {
  if (!TOKEN()) return;
  const load = () => {
    if (document.getElementById('eric-script')) return;
    const s = document.createElement('script');
    s.id = 'eric-script';
    s.src = 'eric.js';
    document.body.appendChild(s);
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();

function barChart(id, data) {
  const W = 400, H = 150, p = { l: 30, r: 6, t: 8, b: 20 };
  const max = Math.max(...data, 1), bw = (W - p.l - p.r) / 24;
  let s = '';

  for (let k = 0; k <= 2; k++) {
    const v = max * k / 2, yy = H - p.b - (v / max) * (H - p.t - p.b);
    s += `<line x1="${p.l}" x2="${W - p.r}" y1="${yy}" y2="${yy}" stroke="#222c3d"/><text x="2" y="${yy + 3}" fill="#8b95a7" font-size="10">${v.toFixed(0)}</text>`;
  }

  data.forEach((v, i) => {
    const hgt = Math.max(0, v) / max * (H - p.t - p.b);
    const night = i <= 5 || i >= 22;
    s += `<rect x="${p.l + i * bw + 1}" y="${H - p.b - hgt}" width="${bw - 2}" height="${hgt}" fill="${night ? '#f5a623' : '#4da3ff'}" rx="2"/>`;
    if (i % 6 === 0) {
      s += `<text x="${p.l + i * bw}" y="${H - 6}" fill="#8b95a7" font-size="10">${i}h</text>`;
    }
  });

  document.getElementById(id).innerHTML = `<svg viewBox="0 0 ${W} ${H}">${s}</svg>`;
}

/* ERIC chatbot: auto-inject on loaded pages */
(function () {
  if (!TOKEN()) return;
  const load = () => {
    const s = document.createElement('script');
    s.src = 'eric.js';
    document.body.appendChild(s);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', load); else load();
})();

/* Reminders banner & browser notification handler */
function startReminders() {
  const base = document.title; 
  let blink = null; 
  const notified = new Set();

  document.head.insertAdjacentHTML('beforeend', `<style>
   #rem{position:sticky;top:0;z-index:9000;display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:10px 24px;background:#7a1f22;color:#fff;font-weight:600;border-bottom:2px solid #ff4d4f}
   #rem.esc{animation:remp 1s infinite}@keyframes remp{50%{background:#c0262b}}
   #rem button,#rem a{background:#fff;color:#7a1f22;border:0;border-radius:8px;padding:6px 12px;font-weight:700;cursor:pointer;text-decoration:none;font-size:13px}
  </style>`);

  async function poll() {
    let x; 
    try { x = await J('/api/reminders'); } catch (e) { return; }
    
    const pend = x.items.filter(i => !i.acked && i.ageSec >= x.remindAfterSec);
    let el = document.getElementById('rem');

    if (!pend.length) { 
      if (el) el.remove(); 
      if (blink) { clearInterval(blink); blink = null; } 
      document.title = base; 
      return; 
    }

    const oldest = Math.max(...pend.map(i => i.ageSec));
    const mins = Math.max(1, Math.round(oldest / 60));

    if (!el) { 
      el = document.createElement('div'); 
      el.id = 'rem'; 
      document.body.prepend(el); 
    }

    el.className = oldest >= 180 ? 'esc' : '';
    const canAsk = 'Notification' in window && Notification.permission === 'default';

    el.innerHTML = `<span>🔔 Reminder: <b>${pend.map(i => i.id).join(', ')}</b> ${pend.length > 1 ? 'are' : 'is'} still CRITICAL and not acknowledged (${mins} min).</span>
      <a href="health.html?id=${pend[0].id}">View</a><button onclick="ackReminders()">Acknowledge all</button>
      ${canAsk ? '<button onclick="enableNotify()">Enable desktop alerts</button>' : ''}`;

    if ('Notification' in window && Notification.permission === 'granted') {
      pend.filter(i => !notified.has(i.id)).forEach(i => { 
        notified.add(i.id); 
        new Notification('UrjaRakshak reminder', { body: i.id + ' is still CRITICAL and not acknowledged.' }); 
      });
    }

    if (!blink) {
      blink = setInterval(() => { 
        document.title = document.title === base ? '🔔 CRITICAL · ' + base : base; 
      }, 1000);
    }
  }

  window.ackReminders = async () => {
    await authFetch('/api/reminders/ack', {
      method: 'POST', 
      headers: { 'Content-Type': 'application/json' }, 
      body: JSON.stringify({ id: 'ALL' })
    });
    poll();
  };

  window.enableNotify = () => Notification.requestPermission().then(poll);

  poll(); 
  setInterval(poll, 20000);
}