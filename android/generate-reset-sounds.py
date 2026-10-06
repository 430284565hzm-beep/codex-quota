"""Original soft confirmation and restoration cues. Standard library only."""
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parent
RAW = ROOT / 'app/src/main/res/raw'
DESKTOP = ROOT.parent / 'windows'
RATE = 44100

def save(name, duration, notes):
    frames = []
    for index in range(round(duration * RATE)):
        time = index / RATE
        sample = 0.0
        for start, frequency, amplitude, decay in notes:
            elapsed = time - start
            if elapsed < 0:
                continue
            attack = 1 - math.exp(-elapsed / .014)
            tail = min(1, max(0, (duration - time) / .12))
            envelope = attack * math.exp(-elapsed / decay) * tail
            tone = (math.sin(2 * math.pi * frequency * elapsed)
                    + .12 * math.sin(2 * math.pi * frequency * 2 * elapsed)
                    + .025 * math.sin(2 * math.pi * frequency * 3 * elapsed))
            sample += amplitude * envelope * tone
        frames.append(round(max(-1, min(1, sample)) * 32767))
    RAW.mkdir(parents=True, exist_ok=True)
    path = RAW / (name.replace('-', '_') + '.wav')
    with wave.open(str(path), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(RATE)
        audio.writeframes(struct.pack('<' + 'h' * len(frames), *frames))
    if DESKTOP.exists():
        (DESKTOP / (name + '.wav')).write_bytes(path.read_bytes())
    peak = max(abs(frame) for frame in frames) / 32767
    assert peak < .22 and frames[0] == 0 and abs(frames[-1]) <= 1
    print(f'{name}: {duration:.2f}s, mono PCM, peak={peak:.3f}')

save('reset-click', .10, [(0, 660, .1, .035)])
save('reset-success', 1.3, [(0, 523.25, .075, .28),
                           (.14, 659.25, .065, .3),
                           (.3, 783.99, .065, .3),
                           (.46, 1046.5, .06, .34)])
