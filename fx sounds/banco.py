# -*- coding: utf-8 -*-
"""
Banco de pruebas del motor de escucha de SismoRed contra la biblioteca real.

Es un port fiel de `Escucha.tick` a numpy: mismas ventanas, mismos rasgos y los
mismos umbrales. No se toca la app — esto solo MIDE, para poder decidir los
umbrales con datos en vez de a ojo. Cuando los números estén, el cambio en
Escucha.kt son cuatro líneas.

Uso:  python banco.py [carpeta_wav]
"""
import sys, os, wave, numpy as np

SR = 48000
N = 2048            # ventana de análisis
SALTO = 1024        # solape 50 %
MARCOS_POR_TICK = 5
PASO = MARCOS_POR_TICK * SALTO      # 5120 muestras ≈ 107 ms por tick

CLAVES = ["estruendo", "grito", "voz", "animal", "golpes"]
NEED = {"estruendo": 4, "grito": 3, "voz": 6, "animal": 3, "golpes": 1}
ENV_N, MARGEN, RITMO_MIN = 24, 0.15, 0.45

VENTANA = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N) / (N - 1))


def leer_wav(ruta):
    with wave.open(ruta, "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return x.astype(np.float64) / 32768.0, w.getframerate()


def espectro_de(marco):
    e = np.abs(np.fft.rfft(marco * VENTANA)) * (2.0 / N)
    return e[: N // 2]


def band_pow(esp, f0, f1):
    res = SR / N
    a = max(1, int(f0 / res)); b = min(int(f1 / res), len(esp) - 1)
    return float(np.sum(esp[a:b + 1] ** 2))


def planitud(esp, f0=200.0, f1=6000.0):
    res = SR / N
    a = max(1, int(f0 / res)); b = min(int(f1 / res), len(esp) - 1)
    if b <= a: return 1.0
    p = esp[a:b + 1] ** 2 + 1e-15
    return float(np.clip(np.exp(np.mean(np.log(p))) / (np.mean(p) + 1e-15), 0, 1))


def centroide(esp):
    res = SR / N
    p = esp[1:] ** 2
    d = p.sum()
    return float((np.arange(1, len(esp)) * res * p).sum() / d) if d > 1e-15 else 0.0


def tono(x):
    """NSDF de McLeod, diezmando x2. Copia exacta de Escucha.tono."""
    d, n = 2, 1024
    if len(x) < n * d: return 0.0, 0.0
    y = x[: n * d].reshape(n, d).mean(axis=1)
    y = y - y.mean()
    if float(y @ y) < 1e-7: return 0.0, 0.0
    srd = SR / d
    lag_min = max(2, int(srd / 2000)); lag_max = min(n - 64, int(srd / 70))
    if lag_max <= lag_min + 1: return 0.0, 0.0
    nsdf = np.zeros(lag_max + 2)
    for lag in range(lag_min, lag_max + 1):
        a, b = y[: n - lag], y[lag:]
        m = float(a @ a + b @ b)
        nsdf[lag] = 2 * float(a @ b) / m if m > 1e-12 else 0.0
    gmax = nsdf[lag_min:lag_max + 1].max()
    if gmax <= 0.2: return 0.0, 0.0
    big = 0
    for l in range(lag_min + 1, lag_max):
        if nsdf[l] > nsdf[l - 1] and nsdf[l] >= nsdf[l + 1] and nsdf[l] >= 0.85 * gmax:
            big = l; break
    if big == 0: return 0.0, 0.0
    a, b, c = nsdf[big - 1], nsdf[big], nsdf[big + 1]
    den = a - 2 * b + c
    lag = big + (0.5 * (a - c) / den if abs(den) > 1e-9 else 0.0)
    return srd / lag, float(np.clip(b, 0, 1))


def spread(a):
    if len(a) < 5: return 0.0
    m = float(np.mean(a))
    return float(np.std(a) / m) if m > 0 else 0.0


def modulacion(env):
    n = len(env)
    if n < 12: return 0.0
    m = float(np.mean(env))
    tot = float(np.sum((np.array(env) - m) ** 2))
    if tot < 1e-6: return 0.0
    fs = SR / PASO                       # ~9,4 Hz
    sil, f = 0.0, 2.0
    i = np.arange(n)
    while f <= min(4.5, fs / 2 - 0.2):
        a = 2 * np.pi * f * i / fs
        re = float(np.sum((np.array(env) - m) * np.cos(a)))
        im = float(np.sum((np.array(env) - m) * np.sin(a)))
        sil += (re * re + im * im) * 2.0 / n
        f += 0.5
    return float(np.clip(sil / tot, 0, 1))


def regularidad(t):
    if len(t) < 3: return 0.0
    h = np.diff(t)
    m = float(np.mean(h))
    return float(np.clip(1 - np.std(h) / m, 0, 1)) if m > 0 else 0.0


def analizar(x):
    """Devuelve (clave que dispara primero, rasgos del momento) o (None, ...)."""
    acum = {k: 0 for k in CLAVES}
    env, wander, onsets = [], [], []
    last_db, sostenido = -90.0, 0
    lta, calienta, esp_prev = None, 0, None
    for t0 in range(0, len(x) - max(N, PASO), PASO):
        marco = x[t0: t0 + N]
        crudo = x[t0: t0 + 2048]
        esp = espectro_de(marco)
        db = 10 * np.log10(float(np.mean(crudo ** 2)) + 1e-12)
        sub = band_pow(esp, 20, 150); low = band_pow(esp, 150, 400)
        sp = band_pow(esp, 400, 3000); hi = band_pow(esp, 3000, 8000)
        tot = sub + low + sp + hi + 1e-12
        rumble, speech, high = (sub + low) / tot, sp / tot, hi / tot
        hz, clarity = tono(crudo)
        jump = db - last_db; last_db = db
        plano = planitud(esp); centro = centroide(esp)
        # flujo espectral: cuánto cambia el espectro de un marco al siguiente.
        # Un motor sostiene el mismo espectro; un derrumbe lo cambia entero.
        P = esp ** 2
        if esp_prev is None:
            esp_prev = P.copy(); flujo = 0.0
        else:
            # distancia al espectro MEDIO de los últimos segundos, normalizado en
            # forma (no en nivel): una máquina se parece a su propia media aunque
            # suba de volumen; un derrumbe cambia de forma entero
            a = P / (P.sum() + 1e-15)
            b = esp_prev / (esp_prev.sum() + 1e-15)
            flujo = float(np.abs(a - b).sum() / 2.0)
            esp_prev += (P - esp_prev) * 0.12
        env.append(db); env[:] = env[-ENV_N:]
        mod = modulacion(env)
        sostenido = sostenido + 1 if db > -55 else 0
        # fondo adaptativo: un generador o una sirena se vuelven fondo en unos
        # segundos; un derrumbe o un grito NO da tiempo a que lo hagan
        if lta is None: lta = db
        calienta += 1
        nov = db - lta
        lta += (db - lta) * TAU
        if hz > 60:
            wander.append(hz); wander[:] = wander[-12:]
        vib = spread(wander)

        p = {}
        if calienta < WARMUP:
            nov = 0.0
        if db > UMB_EST_DB and rumble > 0.5 and clarity < 0.25 and sostenido >= 3 and nov > NOV_EST and flujo > FLUJO_EST:
            p["estruendo"] = min(1.0, rumble * (db + 60) / 40)
        if db > -38 and clarity > 0.35 and 300 < hz < 1200 and speech > 0.30 \
                and plano < 0.40 and mod < 0.35 and sostenido >= 3:
            p["grito"] = min(1.0, clarity + 0.2 - mod)
        if db > -52 and clarity > 0.3 and 70 < hz < 320 and plano < 0.45 \
                and 0.005 < vib < 0.35 and mod > 0.25:
            p["voz"] = min(1.0, clarity * 0.5 + mod)
        if db > -45 and mod < 0.25 and nov > NOV_EV and (
                (clarity > 0.3 and hz > 900 and sostenido < 12) or
                (high > 0.45 and jump > 8 and centro > 2500)):
            p["animal"] = min(1.0, 0.35 + high + (0.2 if hz > 900 else 0.0))

        ganador = None
        if p:
            k = max(p, key=p.get)
            if not any(v > p[k] - MARGEN for kk, v in p.items() if kk != k):
                ganador = k
        rasgos = dict(db=db, grave=rumble, plano=plano, cl=clarity, hz=hz,
                      mod=mod, vib=vib, sost=sostenido, alto=high, centro=centro, nov=nov, flujo=flujo)
        for k in CLAVES:
            if k == "golpes": continue
            if k == ganador:
                acum[k] += 2
                if acum[k] >= NEED[k] * 2: return k, rasgos
            else:
                acum[k] = max(0, acum[k] - 1)

        if jump > 9 and db > -50 and plano > 0.30:
            onsets.append(t0 / SR)
        onsets[:] = [o for o in onsets if t0 / SR - o < 5.0]
        if len(onsets) >= 3 and regularidad(onsets) > RITMO_MIN:
            return "golpes", rasgos
    return None, rasgos


# --- perillas que se están ajustando ---
TAU     = float(os.environ.get("TAU", 0.02))     # rapidez del fondo adaptativo
WARMUP  = int(os.environ.get("WARMUP", 12))      # ticks antes de fiarse del fondo
NOV_EST = float(os.environ.get("NOV_EST", 6))    # dB de novedad para el derrumbe
NOV_EV  = float(os.environ.get("NOV_EV", 5))     # para grito y animal
NOV_VOZ = float(os.environ.get("NOV_VOZ", 3))
UMB_EST_DB = float(os.environ.get("UMB_EST_DB", -40))
FLUJO_EST = float(os.environ.get("FLUJO_EST", 0.0))
VIB_MIN = float(os.environ.get("VIB_MIN", 0.005))
VIB_MAX = float(os.environ.get("VIB_MAX", 1.2))

ESPERADO = {
    "Animales": "animal", "Derrumbe": "estruendo", "Escombros": "estruendo",
    "Humanos": None, "Maquinaria": None, "Ambiente": None, "Rescatistas": None,
}

if __name__ == "__main__":
    raiz = sys.argv[1] if len(sys.argv) > 1 else "wav"
    filas, conf = [], {}
    for cat in sorted(os.listdir(raiz)):
        d = os.path.join(raiz, cat)
        if not os.path.isdir(d): continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".wav"): continue
            x, sr = leer_wav(os.path.join(d, f))
            if sr != SR: continue
            k, r = analizar(x)
            filas.append((cat, f, k, r))
            conf.setdefault(cat, {}).setdefault(k or "nada", 0)
            conf[cat][k or "nada"] += 1

    print("\n===== QUÉ ENCIENDE CADA SONIDO =====")
    for cat, f, k, r in filas:
        print("%-12s %-52s -> %-10s db=%.0f grave=%.2f plano=%.2f cl=%.2f hz=%.0f mod=%.2f vib=%.2f" %
              (cat, f[:52], k or "nada", r["db"], r["grave"], r["plano"], r["cl"], r["hz"], r["mod"], r["vib"]))

    print("\n===== MATRIZ DE CONFUSIÓN =====")
    clases = sorted({c for v in conf.values() for c in v})
    print("%-12s %s" % ("carpeta", " ".join("%10s" % c for c in clases)))
    for cat in sorted(conf):
        print("%-12s %s" % (cat, " ".join("%10d" % conf[cat].get(c, 0) for c in clases)))
