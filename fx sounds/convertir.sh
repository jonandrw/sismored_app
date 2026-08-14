#!/usr/bin/env bash
# Deja en wav/ una copia de toda la biblioteca en mono a 48 kHz, que es lo que
# graba el micrófono del móvil. El banco no mide otra cosa.
#
#   ./convertir.sh            # solo lo que falte o haya cambiado
#   ./convertir.sh --todo     # de cero
#
# Dos cosas que hace y que la orden de una línea que había antes NO hacía:
#
#   1. **Respeta las carpetas.** Escribía todo aplanado en wav/, y `banco.py`
#      solo recorre subcarpetas: quien seguía la documentación al pie de la letra
#      se quedaba con un wav/ lleno y una salida vacía, sin ningún error.
#   2. Acepta lo que traiga la gente —mp3, m4a, ogg, opus, flac, wav— porque en
#      CONTRIBUIR.md se pide «MP3 o WAV» y a nadie hay que obligarle a convertir
#      antes de aportar.
set -u
cd "$(dirname "$0")" || exit 1

command -v ffmpeg >/dev/null || {
  echo "Falta ffmpeg. En Windows: winget install Gyan.FFmpeg" >&2; exit 1; }

todo=0
[ "${1:-}" = "--todo" ] && todo=1 && rm -rf wav

hechos=0; saltados=0
while IFS= read -r f; do
  f="${f#./}"
  destino="wav/${f%.*}.wav"
  # Sin -nt: un WAV más nuevo que su fuente ya está al día. Convertir 63
  # archivos son dos minutos, y se hace en cada sesión.
  if [ "$todo" -eq 0 ] && [ -f "$destino" ] && [ ! "$f" -nt "$destino" ]; then
    saltados=$((saltados + 1)); continue
  fi
  mkdir -p "$(dirname "$destino")"
  if ffmpeg -y -loglevel error -i "$f" -ac 1 -ar 48000 -c:a pcm_s16le "$destino"; then
    hechos=$((hechos + 1))
  else
    echo "  no se pudo convertir: $f" >&2
  fi
done < <(find . -path ./wav -prune -o -type f \
           \( -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.ogg' \
              -o -iname '*.opus' -o -iname '*.flac' -o -iname '*.wav' \) -print | sort)

echo "wav/ al día: $hechos convertidos, $saltados ya estaban."
[ "$hechos" -gt 0 ] && echo "Ahora: python banco.py wav"
exit 0
