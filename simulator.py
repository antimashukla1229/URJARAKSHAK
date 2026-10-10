"""
UrjaRakshak - STEP 1: Grid Simulator
Ek nakli (lekin realistic) bijli grid banata hai jisme chori chhupi hui hai.
Detector (aage ke steps) ko sirf "observable" files milengi,
sach (ground_truth.csv) sirf hum check karne ke liye rakhte hain.

Run:  python simulator.py
"""
import numpy as np
import pandas as pd
from pathlib import Path

SEED, DAYS = 42, 30
T = DAYS * 24                      # total hours
rng = np.random.default_rng(SEED)
OUT = Path("data")
OUT.mkdir(exist_ok=True)

# ---------- 1. Time aur mausam ----------
hours = np.arange(T)
hod = hours % 24                   # hour of day (0-23)
ts = pd.date_range("2026-09-01", periods=T, freq="h")
ambient = 30 + 7 * np.sin((hod - 9) / 24 * 2 * np.pi) + rng.normal(0, 1, T)

# Ghar ka daily pattern: subah 8 baje chhota peak, shaam 8 baje bada peak
shape = 0.45 + 0.35 * np.exp(-((hod - 8) ** 2) / 8) + 0.95 * np.exp(-((hod - 20) ** 2) / 10)
shape = shape / shape.mean()

# ---------- 2. Transformers ----------
caps = [63, 100, 100, 160, 100, 63, 160, 200]          # kVA
tr_ids = [f"TR-{101 + i}" for i in range(len(caps))]
grid = pd.DataFrame({
    "transformer_id": tr_ids,
    "capacity_kva": caps,
    "lat": 28.47 + rng.uniform(-0.03, 0.03, len(caps)),   # map ke liye, apne area se badal lena
    "lon": 77.50 + rng.uniform(-0.03, 0.03, len(caps)),
})

# ---------- 3. Consumers ----------
cons_tr, base = [], []
for tid, cap in zip(tr_ids, caps):
    n = cap // 2                                   # har transformer par consumers
    util = rng.uniform(0.38, 0.52)                 # average load fraction
    w = rng.lognormal(0, 0.5, n)                   # koi bada user, koi chhota
    base += list(w / w.sum() * util * cap)
    cons_tr += [tid] * n
base = np.array(base)
cons_tr = np.array(cons_tr)
C = len(base)
cons_ids = [f"C{i + 1:04d}" for i in range(C)]
consumers = pd.DataFrame({
    "consumer_id": cons_ids,
    "transformer_id": cons_tr,
    "sanctioned_kw": np.round(base * 3 + 1, 1),
})

# ---------- 4. Asli demand (jo log sach me use karte hain) ----------
temp_factor = 1 + 0.015 * (ambient - 30)           # garmi me AC/cooler zyada
demand = base[None, :] * shape[:, None] * temp_factor[:, None] * rng.normal(1, 0.08, (T, C))
demand = np.clip(demand, 0.02, None)
true_kw = demand.copy()        # jo actually kheencha gaya
metered = demand.copy()        # jo meter ne record kiya

# ---------- 5. Chori inject karo (12% consumers) ----------
types = ["meter_bypass", "meter_tamper", "night_tap", "hooking"]
eligible = np.where(~np.isin(cons_tr, ["TR-104", "TR-107"]))[0]
thieves = rng.choice(eligible, size=int(len(eligible) * 0.12), replace=False)
truth = []
for j in thieves:
    kind = str(rng.choice(types))
    start = int(rng.integers(5, 20))               # chori kis din se shuru
    on = hours >= start * 24
    if kind == "meter_bypass":                     # meter ko bypass: sirf 35% record
        true_kw[on, j] *= 1.3
        metered[on, j] = demand[on, j] * 0.35
    elif kind == "meter_tamper":                   # meter slow kiya: aadha record
        true_kw[on, j] *= 1.2
        metered[on, j] = true_kw[on, j] * 0.5
    elif kind == "night_tap":                      # raat ko extra unmetered load
        night = on & ((hod <= 5) | (hod >= 22))
        true_kw[night, j] += rng.uniform(1.5, 2.5)
    else:                                          # hooking: seedha taar daal ke extra load
        true_kw[on, j] += rng.uniform(1.0, 2.0) * shape[on]
    truth.append({
        "consumer_id": cons_ids[j],
        "transformer_id": cons_tr[j],
        "theft_type": kind,
        "start_day": start,
        "avg_stolen_kw": round(float((true_kw[on, j] - metered[on, j]).mean()), 3),
    })

# ---------- 6. Transformer ke input meter ka reading ----------
rows = []
for tid, cap in zip(tr_ids, caps):
    idx = np.where(cons_tr == tid)[0]
    load_kw = true_kw[:, idx].sum(axis=1)
    K = load_kw / 0.95 / cap                       # load factor (1.0 = full capacity)
    losses = 0.015 * cap * K ** 2 + 0.004 * cap    # copper (load^2) + iron (fixed) loss
    inp = (load_kw + losses) * rng.normal(1, 0.01, T)
    rows.append(pd.DataFrame({
        "timestamp": ts, "transformer_id": tid,
        "input_kw": inp.round(3), "ambient_c": ambient.round(1),
    }))
    msum = metered[:, idx].sum(axis=1)
    late = hours >= 20 * 24                        # last 10 din (chori poori active)
    gap = (inp[late] - msum[late]).sum() / inp[late].sum() * 100
    print(f"{tid} | cap {cap:>3} kVA | peak load {K.max() * 100:5.1f}% | energy gap {gap:5.1f}%")

# ---------- 7. Save ----------
grid.to_csv(OUT / "grid.csv", index=False)
consumers.to_csv(OUT / "consumers.csv", index=False)
pd.concat(rows).to_csv(OUT / "transformer_input.csv", index=False)
pd.DataFrame(metered.round(3), index=ts, columns=cons_ids).rename_axis("timestamp").to_csv(OUT / "meter_readings.csv")
pd.DataFrame(truth).to_csv(OUT / "ground_truth.csv", index=False)   # detector isko NAHI dekhega

print(f"\nDone! {C} consumers, {len(truth)} chor, {len(caps)} transformers -> data/ folder")
