"""
Cuantos marcos dura de verdad una rafaga, vista por el decodificador de la malla.

Replica linea a linea el camino de MallaAcustica.decodificar() que decide
`tonoCrudo`, y mide cuantos marcos seguidos da ON y cuantos OFF sobre una trama
sintetizada igual que emitirUna(). Sirve para poner las ventanas de la firma
temporal con un numero medido y no con una estimacion: si se ponen a ojo, la
malla se queda muda y eso no se nota usandola.

El solape es del 50 % (SALTO = N/2), asi que un marco cae cada 21,3 ms y no cada
42,7: la aritmetica "250 ms son 5,9 marcos" es falsa.
"""
import numpy as np

SR = 48000
N = 2048
SALTO = 1024
MARK = 16000.0
HOP_TONE = [16800.0, 17200.0, 17600.0, 18000.0]
LLAMADA = 18400.0
SILENCIO = 18800.0
TONOS = HOP_TONE + [LLAMADA, SILENCIO]
TOL_HZ = 60.0
MARGEN_DB = 20.0
SUELO_ABS_DB = -80.0
SEPARACION_DB = 6.0
BURST_ON = 0.25
BURST_OFF = 0.15
BURST_N = 6
AMP = 0.45

VENTANA = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N) / (N - 1))

SONDAS = []
f = 14000.0
while f <= 19000.0:
    cerca = abs(f - MARK) < 250 or any(abs(f - t) < 250 for t in HOP_TONE)
    if not cerca:
        SONDAS.append(f)
    f += 250.0


"""El Goertzel de Kotlin da |DFT(f)|·2/N; aqui se calcula igual pero de golpe
   para las 39 frecuencias que mira el decodificador, o esto no termina nunca."""
FRECS = ([MARK - TOL_HZ, MARK, MARK + TOL_HZ]
         + [t + d for t in TONOS for d in (-TOL_HZ, 0.0, TOL_HZ)]
         + SONDAS)
I_MARK = slice(0, 3)
I_TONOS = [slice(3 + 3 * i, 6 + 3 * i) for i in range(len(TONOS))]
I_SONDAS = slice(3 + 3 * len(TONOS), None)
BASE = np.exp(-2j * np.pi * np.outer(np.array(FRECS), np.arange(N)) / SR)


def magnitudes(x):
    return np.abs(BASE @ (x * VENTANA)) * 2.0 / N


def dB(m):
    return 20.0 * np.log10(np.asarray(m) + 1e-12)


def tono_crudo(x):
    """True si el marco tiene tono, con el criterio exacto del decodificador."""
    m = magnitudes(x)
    fondo = np.median(m[I_SONDAS])
    umbral = max(dB(fondo) + MARGEN_DB, SUELO_ABS_DB)
    if dB(m[I_MARK].max()) < umbral:
        return False, 0
    vals = [dB(m[s].max()) for s in I_TONOS]
    orden = np.argsort(vals)
    mejor = int(orden[-1]) + 1
    mejorV, segundoV = vals[orden[-1]], vals[orden[-2]]
    if mejorV <= umbral:
        return False, 0
    return True, (mejor if mejorV - segundoV >= SEPARACION_DB else 0)


def trama(hop=1, amp=AMP, continuo=False):
    """La misma sintesis de emitirUna: 6 rafagas de 250/150 con rampas de 8 ms."""
    nOn = int(BURST_ON * SR)
    nOff = int(BURST_OFF * SR)
    rampa = int(0.008 * SR)
    fHop = TONOS[hop - 1]
    if continuo:
        total = BURST_N * (nOn + nOff)
        t = np.arange(total) / SR
        return (np.sin(2 * np.pi * MARK * t) + np.sin(2 * np.pi * fHop * t)) * amp
    pcm = np.zeros(BURST_N * (nOn + nOff))
    i = np.arange(nOn)
    t = i / SR
    env = np.ones(nOn)
    env[:rampa] = i[:rampa] / rampa
    env[nOn - rampa:] = (nOn - i[nOn - rampa:]) / rampa
    onda = (np.sin(2 * np.pi * MARK * t) + np.sin(2 * np.pi * fHop * t)) * amp * env
    for b in range(BURST_N):
        pcm[b * (nOn + nOff):b * (nOn + nOff) + nOn] = onda
    return pcm


def reverb(x, rt60=0.15):
    """Cola exponencial: alarga el ON y se come el OFF, que es el riesgo real."""
    n = int(rt60 * SR)
    h = np.exp(-6.9 * np.arange(n) / n) * 0.35
    h[0] = 1.0
    return np.convolve(x, h)[:len(x) + n]


def rachas(pcm, ruido=0.0, semilla=7):
    """Recorre la senal con el solape real y devuelve las rachas de ON/OFF."""
    rng = np.random.default_rng(semilla)
    x = pcm.copy()
    if ruido > 0:
        x = x + rng.normal(0, ruido, len(x))
    # 400 ms de silencio delante y detras: el decodificador nunca arranca en frio
    pad = np.zeros(int(0.4 * SR))
    if ruido > 0:
        pad = rng.normal(0, ruido, len(pad))
    x = np.concatenate([pad, x, pad])
    x = np.clip(x, -1.0, 1.0)

    estados, hops = [], []
    for k in range(0, len(x) - N, SALTO):
        t, h = tono_crudo(x[k:k + N])
        estados.append(t)
        hops.append(h)

    out, cur, n = [], estados[0], 0
    for e in estados:
        if e == cur:
            n += 1
        else:
            out.append((cur, n))
            cur, n = e, 1
    out.append((cur, n))
    return out, hops


def informe(nombre, pcm, ruido=0.0):
    r, hops = rachas(pcm, ruido)
    on = [n for s, n in r if s]
    off = [n for s, n in r if not s]
    # las rachas OFF de los extremos son el silencio de fuera, no del protocolo
    off_int = off[1:-1] if len(off) > 2 else []
    print(f"\n=== {nombre} ===")
    print(f"  rachas ON  (marcos): {on}")
    print(f"  rachas OFF internas: {off_int}")
    if on:
        print(f"  ON  min={min(on)} max={max(on)}  = {min(on)*SALTO/SR*1000:.0f}-{max(on)*SALTO/SR*1000:.0f} ms")
    if off_int:
        print(f"  OFF min={min(off_int)} max={max(off_int)} = {min(off_int)*SALTO/SR*1000:.0f}-{max(off_int)*SALTO/SR*1000:.0f} ms")
    per = [on[i] + off_int[i] for i in range(min(len(on) - 1, len(off_int)))]
    if per:
        print(f"  periodos: {per}  ({min(per)}-{max(per)} marcos)")
    print(f"  saltos decodificados distintos de 0: {sorted(set(h for h in hops if h))}")


if __name__ == "__main__":
    print(f"un marco cada {SALTO/SR*1000:.2f} ms · ON ideal {BURST_ON*SR/SALTO:.2f} marcos · "
          f"OFF ideal {BURST_OFF*SR/SALTO:.2f} · periodo ideal {(BURST_ON+BURST_OFF)*SR/SALTO:.2f}")
    informe("baliza limpia, al lado", trama())
    informe("baliza + ruido -60 dBFS", trama(), ruido=0.001)
    informe("baliza lejos (amp/20) + ruido", trama(amp=AMP / 20), ruido=0.0005)
    informe("baliza con reverberacion de 0,15 s", reverb(trama()))
    informe("baliza lejos + reverberacion + ruido", reverb(trama(amp=AMP / 20)), ruido=0.0005)
    informe("tono continuo (un generador)", trama(continuo=True))
    informe("solo ruido", np.zeros(int(2.4 * SR)), ruido=0.002)
