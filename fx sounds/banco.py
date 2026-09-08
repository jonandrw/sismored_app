# -*- coding: utf-8 -*-
"""
Banco de pruebas del motor de escucha de SismoRed contra la biblioteca real.

Es un port fiel de `Escucha.tick` a numpy: mismas ventanas, mismos rasgos, los
mismos umbrales y los mismos enfriamientos. No se toca la app — esto solo MIDE,
para poder decidir los umbrales con datos en vez de a ojo. Cuando los números
estén, el cambio en Escucha.kt son cuatro líneas.

**Fiel quiere decir fiel.** Este archivo se separó de `Escucha.kt` sin que nadie
se diera cuenta y estuvo midiendo un detector que la app ya no llevaba: el tope
de oscilación del tono seguía en 0,35 cuando en el móvil era 1,2, y de ahí salió
el «la voz no se enciende NUNCA» que está escrito en la documentación. Si tocas
un umbral en un sitio, tócalo en el otro **en la misma sesión**.

Lo que mide, en este orden de importancia:

1. **Falsos por hora** donde tiene que callar. Es la cifra que decide si esto
   sirve, no el porcentaje de aciertos: un detector que acierta el 99 % pero
   grita una vez por hora es inservible, porque a la tercera vez nadie corre.
2. **Aciertos** donde sí tiene que sonar, y **cuánto tarda**: un derrumbe
   detectado ocho segundos tarde ya no avisa de nada.
3. **No concluyente.** Cuando dos clases empatan, el motor no elige — y eso no
   es un fallo, es la respuesta correcta. Aquí se cuenta para que se vea.

Uso:  python banco.py [carpeta_wav]
"""
import sys, os, wave, numpy as np

try:                      # la consola de Windows es cp1252 y se come los acentos
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

SR = 48000
N = 2048            # ventana de análisis
SALTO = 1024        # solape 50 %
MARCOS_POR_TICK = 5
PASO = MARCOS_POR_TICK * SALTO      # 5120 muestras ≈ 107 ms por tick

CLAVES = ["estruendo", "grito", "voz", "animal", "golpes"]

# Los tres números de cada detector, copiados de `Escucha.ev`: cuánta evidencia
# necesita y cuánto se calla después de disparar. El enfriamiento importa aquí
# tanto como en el móvil: sin él, un generador de tres minutos cuenta cuarenta
# falsos en vez de los seis que de verdad daría.
NEED = {"estruendo": 4, "grito": 3, "voz": 4, "animal": 3, "golpes": 1}
CD_S = {"estruendo": 8.0, "grito": 4.0, "voz": 6.0, "animal": 6.0, "golpes": 5.0}

# El único que puede levantar la sirena él solo. Todos los demás anotan.
ALARMA = "estruendo"

ENV_N, MARGEN, RITMO_MIN = 24, 0.15, 0.45

VENTANA = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N) / (N - 1))


def leer_wav(ruta):
    with wave.open(ruta, "rb") as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError("tiene que ser WAV mono de 16 bits (usa convertir.sh)")
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
    """NSDF de McLeod, diezmando x2. Mismo cálculo que Escucha.tono.

    El móvil lo hace con dos bucles y aquí va vectorizado, porque esta función
    se llevaba tres cuartas partes del tiempo del banco. No es un capricho de
    velocidad: la carpeta Ambiente pide HORAS de grabación, y con los bucles
    una noche entera de audio tardaba veinte minutos en medirse. Se comprobó
    que la salida del banco entero no cambia ni un carácter.
    """
    d, n = 2, 1024
    if len(x) < n * d: return 0.0, 0.0
    y = x[: n * d].reshape(n, d).mean(axis=1)
    y = y - y.mean()
    if float(y @ y) < 1e-7: return 0.0, 0.0
    srd = SR / d
    lag_min = max(2, int(srd / 2000)); lag_max = min(n - 64, int(srd / 70))
    if lag_max <= lag_min + 1: return 0.0, 0.0
    nsdf = np.zeros(lag_max + 2)
    # ac[lag] es el a·b del bucle; los dos a·a y b·b salen de la suma acumulada
    ac = np.correlate(y, y, "full")[n - 1:]
    cum = np.concatenate(([0.0], np.cumsum(y * y)))
    lags = np.arange(lag_min, lag_max + 1)
    m = cum[n - lags] + (cum[n] - cum[lags])
    nsdf[lag_min:lag_max + 1] = np.where(m > 1e-12, 2 * ac[lags] / np.where(m > 1e-12, m, 1.0), 0.0)
    gmax = nsdf[lag_min:lag_max + 1].max()
    if gmax <= 0.2: return 0.0, 0.0
    # el primer máximo interior que llegue al 85 % del mayor, igual que el bucle
    l = np.arange(lag_min + 1, lag_max)
    hay = np.flatnonzero((nsdf[l] > nsdf[l - 1]) & (nsdf[l] >= nsdf[l + 1]) &
                         (nsdf[l] >= 0.85 * gmax))
    if len(hay) == 0: return 0.0, 0.0
    big = int(l[hay[0]])
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
    # Compuerta de cadencia biológica de golpes SOS: 250 ms a 1250 ms entre impactos
    if np.any(h < 0.25) or np.any(h > 1.25):
        return 0.0
    m = float(np.mean(h))
    return float(np.clip(1 - np.std(h) / m, 0, 1)) if m > 0 else 0.0


def con_rodaje(x, cat):
    """Le pone delante al clip la habitación que había antes.

    Sin esto el banco medía un caso que no pasa nunca: un sonido que empieza sin
    que existiera un antes. Los efectos de sonido vienen recortados al ataque, y
    el fondo adaptativo se enganchaba al propio derrumbe durante los 12 ticks de
    calentamiento — así que la novedad salía cero y el detector se quedaba ciego
    justo en los archivos más brutales de la biblioteca. La app no funciona así:
    el micrófono lleva horas abierto y lo que oye es una habitación.

    Es la misma decisión que ya tenía el autotest del móvil (`SILENCIO_N` en
    Escucha.kt), con una diferencia: allí es silencio digital y aquí es ruido a
    −72 dBFS, que es el fondo que midió el interfono en una habitación normal
    del A10s. Con silencio digital la novedad sale de 100 dB y la puerta deja de
    existir, que es engañarse por el otro lado.

    El ruido es siempre el mismo (semilla fija): un instrumento de medida no
    puede dar un número distinto en cada pasada.

    **Y no vale para todas las carpetas.** Un generador lleva media hora
    encendido cuando el móvil lo oye: ya ES el fondo, y la novedad tiene que
    salir cero — para eso está el fondo adaptativo. Ponerle delante una
    habitación en silencio sería inventarse un arranque que no existe y contarle
    al detector tres falsos que no habría tenido. Las carpetas de `YA_SONABA`
    arrancan en frío a propósito, que es justo lo que significa «ya sonaba».
    """
    if cat in YA_SONABA: return x, 0.0
    n = RODAJE * PASO
    if n <= 0: return x, 0.0
    a = 10 ** (RODAJE_DB / 20.0)
    ruido = np.random.default_rng(20260813).normal(0.0, a, n)
    return np.concatenate([ruido, x]), n / SR


def analizar(x):
    """Corre el motor entero sobre una señal y devuelve TODO lo que pasó.

    Ya no para en el primer disparo. Paraba, y por eso el banco no sabía
    contestar a la única pregunta que importa —cuántas veces se enciende esto
    por hora—: un archivo con un falso y un archivo con veinte contaban igual.
    Ahora corre hasta el final aplicando el enfriamiento de cada detector, que
    es lo mismo que hace el móvil.

    Devuelve un diccionario con:
      eventos  lista de (segundo, clave, confianza)
      dudas    ticks en que dos clases empataron y el motor no eligió
      pico     lo más lleno que llegó a estar el acumulador de cada clase, de 0
               a 1. Es el `progreso()` que pinta la app, y es lo que distingue
               «no dispara por poco» de «no lo ve en absoluto»: sin esto, un
               archivo que se queda al 95 % y otro que se queda al 0 % salen
               los dos como «calla», y son problemas distintos.
      rasgos   los rasgos del momento del primer disparo; si no hubo ninguno,
               los del tick más fuerte, que es donde más cerca estuvo
    """
    acum = {k: 0 for k in CLAVES}
    pico = {k: 0.0 for k in CLAVES}
    ult = {k: -1e9 for k in CLAVES}          # segundo del último disparo
    eventos, dudas = [], 0
    rasgos_ev, db_max = None, -1e9
    env, wander, onsets = [], [], []
    last_db, sostenido = -90.0, 0
    last_hz = 0.0
    lta, calienta = None, 0
    rasgos = dict(db=-90.0, grave=0.0, plano=0.0, cl=0.0, hz=0.0,
                  mod=0.0, vib=0.0, sost=0, alto=0.0, centro=0.0, nov=0.0)
    for t0 in range(0, len(x) - max(N, PASO), PASO):
        t = t0 / SR
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
        env.append(db); env[:] = env[-ENV_N:]
        mod = modulacion(env)
        sostenido = sostenido + 1 if db > -55 else 0
        if lta is None: lta = db
        calienta += 1
        nov = db - lta
        lta += (db - lta) * TAU
        if hz > 60:
            wander.append(hz); wander[:] = wander[-12:]
        vib = spread(wander)
        dhz = abs(hz - last_hz) if last_hz > 0 else 0.0
        if hz > 0:
            last_hz = hz

        p = {}
        if calienta < WARMUP:
            nov = 0.0
        # Las cuatro condiciones, en el mismo orden y con los mismos números que
        # `Escucha.tick`. Si cambias una, cambia la otra.
        if db > UMB_EST_DB and rumble > 0.60 and clarity < 0.25 and (3 <= sostenido <= 35) \
                and nov > NOV_EST and mod < 0.25:
            p["estruendo"] = min(1.0, rumble * (db + 60) / 40)

        # VOZ: habla humana natural (70-480 Hz, modulación silábica 0.07-0.48, sin saltos de ladrido)
        es_rango_voz = (70 < hz < 480)
        es_mod_habla = (0.07 < mod < 0.48 or (speech > 0.40 and 0.03 < mod < 0.48))
        es_nivel_habla = (abs(jump) < 11.0)
        if db > -52 and clarity > 0.28 and es_rango_voz and plano < 0.45 \
                and VIB_MIN < vib < VIB_MAX and es_mod_habla and nov > NOV_VOZ:
            p["voz"] = min(1.0, clarity * 0.45 + speech * 0.35 + mod * 0.3)

        # GRITO: grito humano o llanto de auxilio (sostenido, sin rumble de perro < 0.20, sin saltos de ladrido < 14 dB)
        es_grito_estable = (abs(jump) < 14.0 and vib < 0.40)
        if db > -38 and clarity > 0.35 and 300 < hz < 1600 and speech > 0.30 \
                and rumble < 0.20 and plano < 0.35 and mod < 0.35 and sostenido >= 3 \
                and nov > NOV_EV and es_grito_estable:
            p["grito"] = min(1.0, clarity + 0.20 - mod)

        # ANIMAL: perros (ladrido impulsivo / gruñido grave) y gatos (armónico agudo con variación tonal)
        es_ladrido = (clarity > 0.25 and 150 < hz < 900 and (abs(jump) > 10.0 or mod > 0.48) and (rumble > 0.15 or abs(jump) > 12.0))
        es_grunido = (clarity > 0.35 and rumble > 0.60 and vib > 0.50 and 70 < hz < 500)
        es_gato = (clarity > 0.65 and 250 < hz < 1800 and rumble < 0.12 and plano < 0.05 \
                   and (dhz > 45 or vib > 0.20) and sostenido <= 12)
        es_chillido = (high > 0.45 and jump > 8 and centro > 2500)
        es_habla_humana = (speech > 0.60 and plano < 0.08 and dhz < 35 and sostenido > 6 and abs(jump) < 8.0)
        if db > -45 and nov > NOV_EV and not es_habla_humana:
            if es_ladrido or es_grunido or es_gato or es_chillido:
                conf = 0.50
                if es_gato: conf = clarity * 0.80
                elif es_grunido: conf = rumble * 0.85
                elif es_ladrido: conf = clarity * 0.75
                p["animal"] = min(1.0, conf)

        ganador = None
        if p:
            k = max(p, key=p.get)
            if not any(v > p[k] - MARGEN for kk, v in p.items() if kk != k):
                ganador = k
        # NO CONCLUYENTE: había candidatos y ninguno se despegó. El motor no
        # elige, y hace bien; lo que hacía falta era contarlo.
        if len(p) >= 2 and ganador is None:
            dudas += 1
        ahora = dict(db=db, grave=rumble, plano=plano, cl=clarity, hz=hz,
                     mod=mod, vib=vib, sost=sostenido, alto=high, centro=centro, nov=nov)
        if db > db_max:
            db_max, rasgos = db, ahora
        for k in CLAVES:
            if k == "golpes": continue
            if k == ganador:
                acum[k] += 2
                pico[k] = max(pico[k], min(1.0, acum[k] / (NEED[k] * 2.0)))
                if acum[k] >= NEED[k] * 2:
                    acum[k] = 0                     # igual que `e.n = 0` al disparar
                    if t - ult[k] >= CD_S[k]:       # igual que el `cd` de Escucha.fire
                        ult[k] = t
                        eventos.append((t, k, p[k]))
                        if rasgos_ev is None: rasgos_ev = ahora
            else:
                acum[k] = max(0, acum[k] - 1)

        if jump > 9 and db > -50 and plano > 0.30 and rumble > 0.10:
            onsets.append(t)
        onsets[:] = [o for o in onsets if t - o < 5.0]
        pico["golpes"] = max(pico["golpes"], min(1.0, len(onsets) / 3.0))
        if len(onsets) >= 3:
            ritmo = regularidad(onsets)
            if ritmo > RITMO_MIN:
                onsets.clear()                      # el móvil también los tira
                if t - ult["golpes"] >= CD_S["golpes"]:
                    ult["golpes"] = t
                    eventos.append((t, "golpes", 0.5 + ritmo * 0.5))
                    if rasgos_ev is None: rasgos_ev = ahora
    return dict(eventos=eventos, dudas=dudas, pico=pico,
                rasgos=rasgos_ev if rasgos_ev is not None else rasgos)


# --- perillas que se están ajustando ---
# Los valores por defecto son EXACTAMENTE los de Escucha.kt, para que correr el
# banco sin tocar nada mida la app que se instala. Para probar una idea:
#   UMB_EST_DB=-30 python banco.py wav
TAU     = float(os.environ.get("TAU", 0.02))       # FONDO_TAU
WARMUP  = int(os.environ.get("WARMUP", 12))        # CALIENTA_N
NOV_EST = float(os.environ.get("NOV_EST", 20))     # NOV_ESTRUEN_CATÁSTROFE sobre fondo
NOV_EV  = float(os.environ.get("NOV_EV", 6))       # NOV_EVENTO, grito y animal
NOV_VOZ = float(os.environ.get("NOV_VOZ", 3))      # NOV_VOZ
UMB_EST_DB = float(os.environ.get("UMB_EST_DB", -25))
VIB_MIN = float(os.environ.get("VIB_MIN", 0.005))
VIB_MAX = float(os.environ.get("VIB_MAX", 1.2))    # VIB_MAX
# Habitación antes del clip, en ticks y en dBFS (ver `con_rodaje`). Con
# RODAJE=0 se mide el caso contrario: la app abriendo el micrófono justo en el
# instante del suceso.
RODAJE = int(os.environ.get("RODAJE", 16))         # SILENCIO_N
RODAJE_DB = float(os.environ.get("RODAJE_DB", -72))

# Qué se espera de cada carpeta. `None` = tiene que callar, y de esas sale la
# cifra de falsos por hora. La estructura está explicada en README.md.
ESPERADO = {
    "Derrumbe": "estruendo", "Escombros": "estruendo", "Golpes": "golpes",
    "Animales": "animal",
    "Humanos": None, "Maquinaria": None, "Ambiente": None,
    "Rescatistas": None, "Alarmas": None, "Falsos": None,
}

# Carpetas cuyo sonido YA estaba sonando cuando el móvil empezó a escuchar: un
# generador, el fondo de una casa. No llevan rodaje —arrancan en frío—, y así el
# fondo adaptativo se engancha a ellas desde el primer tick, que es exactamente
# lo que pasa en la calle. Las demás son sucesos: llegan a una habitación que
# estaba tranquila, y eso es el rodaje. La diferencia no es cosmética: mide el
# derrumbe 3 de 8 o 8 de 8 según de qué lado te equivoques.
YA_SONABA = {"Ambiente", "Maquinaria"}


def h_m_s(seg):
    return "%d:%02d" % (int(seg // 60), int(seg % 60))


if __name__ == "__main__":
    raiz = sys.argv[1] if len(sys.argv) > 1 else "wav"
    if not os.path.isdir(raiz):
        sys.exit("No existe la carpeta '%s'. Genera los WAV con ./convertir.sh" % raiz)

    filas, conf, avisos = [], {}, []
    horas = {}          # segundos de audio por carpeta
    for cat in sorted(os.listdir(raiz)):
        d = os.path.join(raiz, cat)
        if not os.path.isdir(d): continue
        if cat not in ESPERADO:
            avisos.append("carpeta '%s' sin fila en ESPERADO: no se sabe qué se "
                          "espera de ella, así que no cuenta para nada" % cat)
            continue
        for f in sorted(os.listdir(d)):
            if not f.lower().endswith(".wav"): continue
            try:
                x, sr = leer_wav(os.path.join(d, f))
            except Exception as e:
                avisos.append("%s/%s no se pudo leer: %s" % (cat, f, e))
                continue
            if sr != SR:
                avisos.append("%s/%s va a %d Hz y no a 48000: reconviértelo" % (cat, f, sr))
                continue
            xr, off = con_rodaje(x, cat)
            r = analizar(xr)
            # los tiempos se cuentan desde el principio del clip, no del rodaje
            r["eventos"] = [(max(0.0, s - off), k, c) for s, k, c in r["eventos"]]
            dur = len(x) / SR          # y las horas también: el rodaje no es audio real
            horas[cat] = horas.get(cat, 0.0) + dur
            filas.append((cat, f, r, dur))
            # el veredicto del archivo: lo primero que disparó, o si no llegó a
            # disparar nada, si al menos estuvo dudando
            ev = r["eventos"]
            k = ev[0][1] if ev else ("dudoso" if r["dudas"] >= 3 else "nada")
            conf.setdefault(cat, {}).setdefault(k, 0)
            conf[cat][k] += 1

    # Una carpeta sin un solo archivo es un caso que nadie ha comprobado nunca, y
    # eso tiene que salir en la salida: si no, se lee «no hay falsos» donde lo que
    # hay es «no hay medida». Vale igual si la carpeta ni siquiera llegó a wav/.
    vacias = [cat for cat in ESPERADO if cat not in horas]

    if not filas:
        sys.exit("No hay ni un WAV en '%s'. Genera los WAV con ./convertir.sh" % raiz)

    print("\n===== QUÉ ENCIENDE CADA SONIDO =====")
    print("«primero» es lo que disparó antes y en qué segundo, «disp» todos los "
          "disparos\ndel archivo, «cerca» el detector que más evidencia llegó a "
          "acumular —100 %\nes que disparó— y «dudas» los ticks en que había dos "
          "candidatos y no quiso\nelegir. Los rasgos son los del disparo, o los "
          "del momento más fuerte si no\ndisparó nada.\n")
    for cat, f, r, dur in filas:
        ev, g = r["eventos"], r["rasgos"]
        prim = "%s @%.1fs" % (ev[0][1], ev[0][0]) if ev else "nada"
        # de qué detector es ese «cerca»: sin decirlo se lee mal. Un 100 % de
        # golpes son tres impactos SIN ritmo, que es otra cosa muy distinta de
        # un derrumbe que se queda a las puertas.
        ck = max(r["pico"], key=r["pico"].get)
        print("%-11s %-44s %-17s %2d disp cerca=%3d%% %-9s %3d dudas  db=%.0f nov=%.0f grave=%.2f plano=%.2f cl=%.2f hz=%.0f mod=%.2f vib=%.2f" %
              (cat, f[:44], prim, len(ev), int(r["pico"][ck] * 100), ck if r["pico"][ck] else "",
               r["dudas"], g["db"], g["nov"], g["grave"], g["plano"], g["cl"],
               g["hz"], g["mod"], g["vib"]))

    print("\n===== MATRIZ DE CONFUSIÓN =====")
    print("Una fila por carpeta, y en cada columna cuántos archivos acabaron ahí.\n")
    clases = sorted({c for v in conf.values() for c in v})
    print("%-12s %s" % ("carpeta", " ".join("%10s" % c for c in clases)))
    for cat in sorted(conf):
        print("%-12s %s" % (cat, " ".join("%10d" % conf[cat].get(c, 0) for c in clases)))

    print("\n===== DONDE SÍ TIENE QUE SONAR =====")
    print("Y cuánto tarda: un derrumbe reconocido ocho segundos tarde no avisa "
          "de nada.\n")
    print("%-12s %-10s %8s %8s %8s %9s %9s" %
          ("carpeta", "espera", "acierta", "confunde", "calla", "tarda", "los que"))
    print("%-12s %-10s %8s %8s %8s %9s %9s" %
          ("", "", "", "", "", "(mediana)", "callan"))
    for cat in sorted(horas):
        esp = ESPERADO.get(cat)
        if esp is None: continue
        acierta = confunde = calla = 0
        tardanzas, cercas = [], []
        for c, f, r, dur in filas:
            if c != cat: continue
            ev = r["eventos"]
            propios = [e for e in ev if e[1] == esp]
            if propios:
                acierta += 1; tardanzas.append(propios[0][0])
            elif ev:
                confunde += 1
            else:
                calla += 1; cercas.append(r["pico"][esp])
        med = "%.1f s" % float(np.median(tardanzas)) if tardanzas else "—"
        # de los que no dicen nada: ¿se quedaron a las puertas o ni se enteraron?
        cer = "%d%%" % int(np.median(cercas) * 100) if cercas else "—"
        print("%-12s %-10s %8d %8d %8d %9s %9s" %
              (cat, esp, acierta, confunde, calla, med, cer))

    print("\n===== FALSOS POR HORA (donde tiene que callar) =====")
    print("La cifra que decide. Un detector que acierta el 99 % pero grita una "
          "vez por\nhora es inservible: a la tercera vez nadie corre.\n")
    print("%-12s %8s %9s %9s %9s %9s" %
          ("carpeta", "audio", "DERRUMBE", "por hora", "otros", "por hora"))
    tot_s = tot_alarma = tot_otros = 0.0
    for cat in sorted(horas):
        if ESPERADO.get(cat) is not None: continue
        seg = horas[cat]
        al = sum(1 for c, f, r, d in filas if c == cat for e in r["eventos"] if e[1] == ALARMA)
        ot = sum(1 for c, f, r, d in filas if c == cat for e in r["eventos"] if e[1] != ALARMA)
        tot_s += seg; tot_alarma += al; tot_otros += ot
        print("%-12s %8s %9d %9.1f %9d %9.1f" %
              (cat, h_m_s(seg), al, al * 3600 / seg, ot, ot * 3600 / seg))
    if tot_s > 0:
        print("%-12s %8s %9d %9.1f %9d %9.1f" %
              ("TOTAL", h_m_s(tot_s), tot_alarma, tot_alarma * 3600 / tot_s,
               tot_otros, tot_otros * 3600 / tot_s))
    print("""
Qué significa y qué NO significa este número, para que nadie lo cite mal:

  · DERRUMBE es el único que puede levantar la sirena él solo. Los demás anotan
    en el registro y, con la alarma activa, contestan hacia abajo. Por eso van
    en columnas distintas: un «otro» de más cuesta una línea; un DERRUMBE de más
    cuesta la sirena en el bolsillo.
  · Desde que el estruendo pide corroboración del acelerómetro (ServicioSos), un
    DERRUMBE de esta tabla solo dispara si ADEMÁS el móvil se está moviendo. O
    sea que esto es la cota superior por el lado del micrófono, no la cuenta
    final de sirenas.
  · Esta biblioteca es material adverso a propósito —generadores, martillos,
    sirenas—, no una casa un martes por la tarde. El número real de falsos por
    hora de uso normal solo sale de horas de grabación ambiente, y de eso hay
    lo que diga la fila Ambiente. Si son minutos, la cifra de esa fila no vale
    para decidir nada: vale para saber que falta grabar.""")

    if vacias or avisos:
        print("\n===== AVISOS =====")
        for c in sorted(vacias):
            print("· la carpeta %s está vacía: nadie ha comprobado nunca ese caso" % c)
        for a in avisos:
            print("· " + a)
