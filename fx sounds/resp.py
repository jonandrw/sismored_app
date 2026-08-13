"""Replica exacta de Sonda.periodicidad() para calibrar UMBRAL_RESP.

No es el codigo de la app: es la misma cuenta en Python para ver que los
numeros separan de verdad una respiracion de un escombro. Si aqui no separan,
en Kotlin tampoco.
"""
import math, random

MUESTRAS_S = 10
RESP_HZ_MIN = 0.15
RESP_HZ_MAX = 0.6


def periodicidad(serie):
    n = len(serie)
    lag_min = round(MUESTRAS_S / RESP_HZ_MAX)
    lag_max = round(MUESTRAS_S / RESP_HZ_MIN)
    if n < lag_max * 3:
        return 0.0, 0, 0.0

    # 1. quitar la recta: el rescatista acercandose hace subir la serie entera,
    #    y una rampa se autocorrelaciona consigo misma en cualquier retardo.
    sx = sy = sxy = sxx = 0.0
    for i, v in enumerate(serie):
        sx += i; sy += v; sxy += i * v; sxx += i * i
    den = n * sxx - sx * sx
    m = (n * sxy - sx * sy) / den if abs(den) > 1e-9 else 0.0
    b = (sy - m * sx) / n
    d = [serie[i] - (m * i + b) for i in range(n)]

    # 2. paso bajo: nada mas rapido que RESP_HZ_MAX es respirar. Sin esto, una
    #    lona batiendo a 2 Hz correlaciona perfecto en lag=2 s (cuatro periodos
    #    enteros) y se cuela como 30 respiraciones por minuto.
    #    Y en CASCADA: una media movil sola deja pasar un 10 % de los 2 Hz, y un
    #    10 % de algo perfectamente periodico sigue correlacionando perfecto,
    #    porque la autocorrelacion normalizada no mira la amplitud. Dos pasadas
    #    lo dejan en un 1 %, por debajo del ruido.
    w = max(1, round(MUESTRAS_S / (2 * RESP_HZ_MAX)))
    x = d
    for _ in range(2):
        y = []
        for i in range(n):
            lo, hi = max(0, i - w), min(n - 1, i + w)
            y.append(sum(x[lo:hi + 1]) / (hi - lo + 1))
        x = y

    e0 = sum(v * v for v in x)
    if e0 < 1e-12:
        return 0.0, 0, 0.0
    energia = math.sqrt(e0 / n)

    # 3. autocorrelacion en toda la banda
    tope = min(lag_max, n // 3)
    r = {}
    for lag in range(lag_min - 1, tope + 2):
        if lag < 1 or lag >= n:
            continue
        num = d1 = d2 = 0.0
        for i in range(n - lag):
            num += x[i] * x[i + lag]
            d1 += x[i] * x[i]
            d2 += x[i + lag] * x[i + lag]
        dd = math.sqrt(d1 * d2)
        r[lag] = num / dd if dd > 1e-12 else 0.0

    # 4. el pico tiene que ser un maximo INTERIOR. Una deriva suave da su maximo
    #    en el retardo mas corto y baja sin parar: eso no es un ritmo, es una
    #    pendiente, y asi se descarta sin tener que mirar la energia.
    mejor, mejor_lag = 0.0, 0
    for lag in range(lag_min, tope + 1):
        if lag - 1 not in r or lag + 1 not in r:
            continue
        if r[lag] > r[lag - 1] and r[lag] >= r[lag + 1] and r[lag] > mejor:
            mejor, mejor_lag = r[lag], lag
    return mejor, mejor_lag, energia


def serie(n, f, amp, ruido, deriva=0.0, semilla=1):
    random.seed(semilla)
    out = []
    for i in range(n):
        t = i / MUESTRAS_S
        v = amp * math.sin(2 * math.pi * f * t)
        v += random.gauss(0, ruido)
        v += deriva * t
        out.append(v)
    return out


N = 25 * MUESTRAS_S  # 25 s

casos = [
    ("respira 15/min, senal limpia",      serie(N, 0.25, 0.5, 0.05)),
    ("respira 15/min, ruido igual",       serie(N, 0.25, 0.3, 0.30)),
    ("respira 15/min, ruido doble",       serie(N, 0.25, 0.2, 0.40)),
    ("respira 9/min (el mas lento)",      serie(N, 0.15, 0.4, 0.15)),
    ("respira 30/min (agitado)",          serie(N, 0.50, 0.4, 0.15)),
    ("respira + rescatista acercandose",  serie(N, 0.25, 0.4, 0.15, deriva=0.5)),
    ("SOLO ruido",                        serie(N, 0.0, 0.0, 0.30)),
    ("SOLO ruido, otra semilla",          serie(N, 0.0, 0.0, 0.30, semilla=7)),
    ("escombro: un golpe y para",         [0.0] * 60 + [1.0] * 8 + [0.0] * 182),
    ("lona con el aire: 2 Hz, rapido",    serie(N, 2.0, 0.6, 0.10)),
    ("motor: 5 Hz",                       serie(N, 5.0, 0.6, 0.10)),
    ("deriva sola, sin ritmo",            serie(N, 0.0, 0.0, 0.05, deriva=1.0)),
]

print(f"{'caso':38} {'fuerza':>7} {'bpm':>6} {'movim.':>7}")
print("-" * 62)
for nombre, s in casos:
    f, lag, e = periodicidad(s)
    bpm = 60.0 * MUESTRAS_S / lag if lag else 0.0
    print(f"{nombre:38} {f:7.2f} {bpm:6.1f} {e:7.3f}")
