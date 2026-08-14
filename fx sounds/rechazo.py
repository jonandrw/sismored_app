"""
Cuanto rechaza la firma temporal que no rechazaba contar flancos.

La regla vieja pedia DOS flancos de subida: cualquier parpadeo del ruido los da.
La nueva pide TRES rafagas seguidas con la forma y el periodo de la trama. Aqui
se mide cuantas veces por hora pasa cada una cuando lo que hay es ruido que
parpadea, barriendo todas las velocidades de parpadeo posibles: no se sabe como
parpadea el ruido ultrasonico de una habitacion, asi que se prueban todas.
"""
import numpy as np

SR = 48000
SALTO = 1024
MS_MARCO = SALTO * 1000.0 / SR          # 21,33 ms

ON_MIN, ON_MAX = 200, 400               # ms, medidos con cadencia.py (235-363)
OFF_MIN, OFF_MAX = 40, 220              # medidos 43-171
PER_MIN, PER_MAX = 340, 460             # medidos 341-405; el ideal son 400
PER_JITTER = 45                         # dos marcos: el periodo real no se mueve mas
RAFAGAS_MIN = 3


def paso(estado, tono, n):
    """Un paso de la maquina de estados que va a ir en Kotlin."""
    cad, off_ms, off_ok, per_ant = estado
    ms = n * MS_MARCO
    if not tono:
        return (cad, ms, OFF_MIN <= ms <= OFF_MAX, per_ant)
    if not (ON_MIN <= ms <= ON_MAX):
        return (0, off_ms, False, 0.0)
    per = off_ms + ms
    sigue = (off_ok and PER_MIN <= per <= PER_MAX
             and (per_ant == 0.0 or abs(per - per_ant) <= PER_JITTER))
    return (cad + 1, off_ms, off_ok, per) if sigue else (1, off_ms, off_ok, 0.0)


def cadencia_nueva(rachas):
    estado, mejor = (0, 0.0, False, 0.0), 0
    for tono, n in rachas:
        estado = paso(estado, tono, n)
        mejor = max(mejor, estado[0])
    return mejor


def cadencia_vieja(rachas):
    """Contar flancos de subida, sin mirar cuanto duran."""
    return sum(1 for tono, _ in rachas if tono)


def parpadeo(rng, media_on, media_off, marcos):
    """Ruido que cruza el umbral y vuelve a bajar, con dos tiempos medios."""
    r, total, tono = [], 0, True
    while total < marcos:
        n = max(1, rng.geometric(1.0 / (media_on if tono else media_off)))
        r.append((tono, n))
        total += n
        tono = not tono
    return r


if __name__ == "__main__":
    rng = np.random.default_rng(11)
    HORA = int(3600 * 1000 / MS_MARCO)      # marcos en una hora de escucha
    print(f"una hora son {HORA} marcos de analisis\n")
    print(f"{'ON medio':>9} {'OFF medio':>9} | {'2 flancos (viejo)':>18} | {'3 rafagas (nuevo)':>18}")
    peor_nuevo = 0
    for m_on in (2, 4, 6, 9, 12, 13, 16, 20):
        for m_off in (2, 4, 6, 9, 12):
            r = parpadeo(rng, m_on, m_off, HORA)
            # el viejo: 2 flancos en la ventana de corroboracion de 12 s
            viejo = "siempre" if cadencia_vieja(r) >= 2 else "no"
            # el nuevo: cuantas veces se llega a 3 rafagas seguidas en toda la hora
            veces, estado = 0, (0, 0.0, False, 0.0)
            for tono, n in r:
                estado = paso(estado, tono, n)
                if estado[0] >= RAFAGAS_MIN:
                    veces += 1
                    estado = (0, estado[1], estado[2], 0.0)
            peor_nuevo = max(peor_nuevo, veces)
            print(f"{m_on*MS_MARCO:8.0f}ms {m_off*MS_MARCO:8.0f}ms | {viejo:>18} | {veces:>13}/hora")
    print(f"\npeor caso de la regla nueva: {peor_nuevo} por hora")

    # y la contraprueba que importa: la baliza de verdad tiene que pasar
    real = [(False, 100)] + [(True, 13), (False, 5)] * 6
    print(f"baliza real (ON 13, OFF 5 marcos): cadencia = {cadencia_nueva(real)} (hace falta {RAFAGAS_MIN})")
    lejos = [(False, 100)] + [(True, 11), (False, 8)] * 6
    print(f"baliza al limite (ON 11, OFF 8):   cadencia = {cadencia_nueva(lejos)}")
    reverb = [(False, 100)] + [(True, 17), (False, 2)] * 6
    print(f"baliza reverberada (ON 17, OFF 2): cadencia = {cadencia_nueva(reverb)}")
    print(f"tono continuo:                     cadencia = {cadencia_nueva([(False, 50), (True, 5000)])}")
