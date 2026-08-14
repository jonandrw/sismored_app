# -*- coding: utf-8 -*-
"""
Banco del detector sismico: por que salta al levantar el movil y con el teclado.

Replica onSensorChanged() de Sismografo.kt sobre senales sinteticas de las cosas
que pasan de verdad encima de una mesa, y compara el detector de hoy con el que
mide la aceleracion lineal de verdad.

Lo que se quiere separar:
  - levantar el movil de golpe          -> NO debe disparar
  - teclear en la mesa donde esta       -> NO debe disparar
  - un portazo                          -> NO debe disparar
  - un terremoto MMI V (se despierta la gente) -> SI debe disparar

La sospecha que se mide aqui: |a| - media pesa lo vertical unas veinte veces mas
que lo horizontal, porque la horizontal entra en cuadratura con la gravedad. Y
resulta que la mano levanta en vertical y el terremoto sacude en horizontal.
"""
import numpy as np

SR = 50.0                 # Hz, SENSOR_DELAY_GAME
G = 9.81


# ---------------------------------------------------------------- senales
def base(seg):
    n = int(seg * SR)
    a = np.zeros((n, 3))
    a[:, 2] = G                                  # movil boca arriba en la mesa
    return a


def ruido(a, s=0.02, semilla=1):
    rng = np.random.default_rng(semilla)
    return a + rng.normal(0, s, a.shape)


def reposo(seg=10):
    return ruido(base(seg))


def teclado(seg=10, golpes_s=6, amp=0.9, semilla=3):
    """Teclear en la mesa: impulsos cortos que resuenan a ~25 Hz y se apagan."""
    a = base(seg)
    rng = np.random.default_rng(semilla)
    n = len(a)
    for t in rng.uniform(0, seg, int(seg * golpes_s)):
        i = int(t * SR)
        L = int(0.06 * SR)
        k = np.arange(min(L, n - i))
        ring = amp * np.exp(-k / (0.012 * SR)) * np.sin(2 * np.pi * 25 * k / SR)
        a[i:i + len(k), 2] += ring              # la mesa transmite en vertical
        a[i:i + len(k), 0] += ring * 0.3
    return ruido(a, 0.02, semilla)


def levantar(seg=10, pico=3.0, t0=4.0, subida=0.18, semilla=5):
    """Cogerlo de golpe: acelera hacia arriba y frena. Un solo ciclo, vertical."""
    a = base(seg)
    i0 = int(t0 * SR)
    L = int(subida * SR)
    a[i0:i0 + L, 2] += pico                      # tiron hacia arriba
    a[i0 + L:i0 + 2 * L, 2] -= pico              # frenada
    # y a partir de ahi esta en la mano: se inclina un poco y tiembla el pulso
    resto = slice(i0 + 2 * L, len(a))
    a[resto, 0] += 0.8
    a[resto, 2] -= 0.4
    return ruido(a, 0.05, semilla)


def portazo(seg=10, amp=4.0, t0=4.0, semilla=7):
    """Un golpe seco: un solo impulso muy corto."""
    a = base(seg)
    i = int(t0 * SR)
    L = int(0.12 * SR)
    k = np.arange(L)
    ring = amp * np.exp(-k / (0.02 * SR)) * np.sin(2 * np.pi * 18 * k / SR)
    a[i:i + L, 2] += ring
    return ruido(a, 0.02, semilla)


def terremoto(seg=14, pico=0.7, t0=3.0, dur=8.0, semilla=11):
    """
    MMI V: 0,38-0,90 m/s2 de pico, lo nota todo el mundo y despierta a la gente.

    Sacude SOBRE TODO EN HORIZONTAL —son ondas S— y en la banda de 0,5 a 5 Hz,
    con envolvente: entra, crece y se va. La vertical es tipica ~2/3 de la
    horizontal.
    """
    a = base(seg)
    rng = np.random.default_rng(semilla)
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.minimum(t / 1.2, 1.0) * np.exp(-t / (dur * 0.7))
    def onda(escala):
        s = np.zeros(n)
        for f in (0.7, 1.3, 2.1, 3.4, 4.6):
            s += np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) / f**0.5
        s /= np.abs(s).max()
        return s * env * escala
    i0 = int(t0 * SR)
    a[i0:i0 + n, 0] += onda(pico)
    a[i0:i0 + n, 1] += onda(pico * 0.8)
    a[i0:i0 + n, 2] += onda(pico * 0.6)
    return ruido(a, 0.02, semilla)


# ---------------------------------------------------------------- detectores
def correr(a, umbral, modo, muestras_disparo=36):
    """
    Un paso a paso fiel a onSensorChanged().

    modo 'magnitud' = lo de hoy: | |a| - lta |
    modo 'lineal'   = | a - g |, con g la gravedad filtrada que ya se calcula
                      para el giro. Mide la aceleracion de verdad, venga de donde
                      venga.
    """
    lta, sta, over = G, 0.0, 0
    g = a[0].copy()
    disparos, pico_sta, cruces, signo_ant = 0, 0.0, 0, 0
    for v in a:
        g += (v - g) * 0.02
        if modo == "magnitud":
            mag = np.linalg.norm(v)
            d0 = abs(mag - lta)
            if d0 < umbral:
                lta += (mag - lta) * 0.004
            dev = abs(mag - lta)
            firmado = mag - lta
        else:
            lin = v - g
            dev = np.linalg.norm(lin)
            # el eje que mas se mueve, con signo: es donde se ve si oscila
            firmado = lin[int(np.argmax(np.abs(lin)))]
        devc = min(dev, umbral * 3)
        sta += (devc - sta) * (0.25 if devc > sta else 0.5)
        pico_sta = max(pico_sta, sta)
        # cruces por cero con banda muerta, para contar oscilacion real
        s = 1 if firmado > umbral * 0.3 else (-1 if firmado < -umbral * 0.3 else 0)
        if s != 0 and signo_ant != 0 and s != signo_ant:
            cruces += 1
        if s != 0:
            signo_ant = s
        if sta > umbral:
            over += 1
            if over > muestras_disparo:
                over = 0
                disparos += 1
        else:
            over = max(0, over - 4)
    return disparos, pico_sta, cruces


CASOS = [
    ("reposo en la mesa", reposo(), False),
    ("teclear en la mesa", teclado(), False),
    ("levantarlo de golpe", levantar(), False),
    ("un portazo", portazo(), False),
    ("TERREMOTO MMI V", terremoto(), True),
]

if __name__ == "__main__":
    for modo in ("magnitud", "lineal"):
        print(f"\n===== {modo} =====")
        for umbral in (0.8, 1.2):
            print(f"  -- umbral {umbral} m/s2 --")
            for nombre, a, debe in CASOS:
                d, pico, cr = correr(a, umbral, modo)
                veredicto = "OK " if (d > 0) == debe else "MAL"
                print(f"   {veredicto} {nombre:22} disparos={d}  pico sta={pico:5.2f}  "
                      f"cruces={cr:3}  ({'debe disparar' if debe else 'no debe'})")
