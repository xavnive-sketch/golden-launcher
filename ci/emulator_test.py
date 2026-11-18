#!/usr/bin/env python3
"""
Prueba el APK en un emulador Android (GitHub Actions).

- Instala el APK de depuración (permite controlar el WebView por Chrome DevTools).
- Pantalla 1280x720 (como la radio), con densidad 160 y 240.
- Abre cada página, guarda una captura y revisa que nada se salga de la pantalla.
- Conecta la app a un ELM327 simulado (ci/fake_elm.py en el host, 10.0.2.2:35099) para probar
  la lectura OBD nativa (Java) de principio a fin.
Resultados en shots/: capturas PNG, resultados.json y logcat.txt.
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

import websocket  # pip install websocket-client

APK = sys.argv[1] if len(sys.argv) > 1 else 'golden-launcher-debug.apk'
PKG = 'com.goldenlauncher.app'
OUT = 'shots'
os.makedirs(OUT, exist_ok=True)
PAGES = ['inicio', 'tablero', 'mapa', 'autonomia', 'registro', 'diagnostico', 'jarvis', 'ajustes']
results = {'ok': True, 'densidades': {}}

AUDIT = r"""(() => {
  const VW = innerWidth, VH = innerHeight, out = [];
  const name = (el) => (el.id ? '#' + el.id : el.tagName.toLowerCase()) + ' "' + (el.innerText || '').trim().slice(0, 30).replace(/\s+/g, ' ') + '"';
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    if (!(r.width > 0 && r.height > 0) || cs.visibility === 'hidden' || el.closest('.leaflet-container')) continue;
    if (r.right > VW + 1 || r.bottom > VH + 1 || r.left < -1 || r.top < -1) out.push('FUERA ' + name(el));
    if (/hidden|clip/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 2 && !el.classList.contains('bus-3d-box')) out.push('RECORTA ' + name(el));
  }
  return { viewport: VW + 'x' + VH, problemas: [...new Set(out)].slice(0, 10) };
})()"""


def sh(*cmd, check=True, capture=False):
    print('$', ' '.join(cmd), flush=True)
    r = subprocess.run(cmd, check=check, stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    return r.stdout.decode(errors='replace') if capture else ''


def adb(*a, **kw):
    return sh('adb', *a, **kw)


def screencap(name):
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=subprocess.PIPE, check=True).stdout)


class Cdp:
    def __init__(self):
        sock = None
        for _ in range(60):
            txt = adb('shell', 'cat', '/proc/net/unix', capture=True, check=False)
            names = [l.split('@')[-1].strip() for l in txt.splitlines() if 'webview_devtools_remote' in l]
            if names:
                sock = names[0]
                break
            time.sleep(1)
        if not sock:
            raise RuntimeError('No apareció el socket de depuración del WebView')
        adb('forward', '--remove-all', check=False)
        adb('forward', 'tcp:9222', 'localabstract:' + sock)
        url = None
        for _ in range(30):
            try:
                targets = json.load(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=5))
                pages = [t for t in targets if t.get('type') == 'page' and 'index.html' in t.get('url', '')]
                if pages:
                    url = pages[0]['webSocketDebuggerUrl']
                    break
            except Exception as e:
                print('esperando DevTools…', e)
            time.sleep(1)
        if not url:
            raise RuntimeError('No se encontró la página del dashboard en DevTools')
        self.ws = websocket.create_connection(url, timeout=30, suppress_origin=True)
        self.n = 0

    def ev(self, expr):
        self.n += 1
        self.ws.send(json.dumps({'id': self.n, 'method': 'Runtime.evaluate',
                                 'params': {'expression': expr, 'returnByValue': True, 'awaitPromise': True}}))
        while True:
            m = json.loads(self.ws.recv())
            if m.get('id') == self.n:
                res = m.get('result', {})
                if 'exceptionDetails' in res:
                    return {'error': res['exceptionDetails'].get('exception', {}).get('description') or str(res['exceptionDetails'])}
                return res.get('result', {}).get('value')


def run_density(dpi, obd_test):
    key = str(dpi)
    info = {'paginas': {}}
    results['densidades'][key] = info
    try:
        _run_density(dpi, obd_test, info)
    except Exception as e:
        results['ok'] = False
        info['excepcion'] = repr(e)
        try:
            screencap(f'd{dpi}-99-error.png')
        except Exception:
            pass


def _run_density(dpi, obd_test, info):
    key = str(dpi)
    adb('shell', 'am', 'force-stop', PKG, check=False)
    adb('shell', 'wm', 'density', key)
    adb('logcat', '-c', check=False)
    adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(8)
    screencap(f'd{dpi}-00-arranque.png')        # pantalla de inicio con el logo
    cdp = Cdp()
    for _ in range(60):                          # esperar a que termine de cargar
        if cdp.ev("document.readyState==='complete' && !document.getElementById('splash')"):
            break
        time.sleep(1)
    time.sleep(6)                                # dar tiempo al modelo 3D
    info['nativo'] = cdp.ev("typeof GLNative==='object' && GLNative.version()")
    info['viewport'] = cdp.ev("innerWidth+'x'+innerHeight+' dpr='+devicePixelRatio+' screen='+screen.width+'x'+screen.height+' outer='+outerWidth+'x'+outerHeight+' escala='+document.body.style.transform")
    for i, pg in enumerate(PAGES, 1):
        cdp.ev(f"setPage('{pg}')")
        time.sleep(2.5 if pg in ('inicio', 'mapa') else 1.2)
        screencap(f'd{dpi}-{i:02d}-{pg}.png')
        a = cdp.ev(AUDIT)
        info['paginas'][pg] = a
        if a and a.get('problemas'):
            results['ok'] = False
    if obd_test:
        # Lectura OBD nativa contra el ELM327 simulado en el host (10.0.2.2 desde el emulador)
        cdp.ev("""(() => { lsSet(OBD_KEY, Object.assign(obdCfg(), {mode:'bt', btAddr:'10.0.2.2:35099', btName:'ELM327 simulado', btKind:'auto'}));
                 loadObdForm(); startObd(); return true; })()""")
        ok = False
        for _ in range(45):
            if cdp.ev('obdFresh()'):
                ok = True
                break
            time.sleep(1)
        time.sleep(4)
        info['obd'] = cdp.ev("""(() => ({ fresco: obdFresh(), modo: S.obd.mode, error: S.obd.error, dtc: S.obd.dtc,
            datos: S.obd.data, estado: document.getElementById('status-label').textContent,
            rpm: document.getElementById('t-rpm').textContent, temp: document.getElementById('m-temp').textContent,
            diag: document.getElementById('d-obd').textContent + ' · ' + document.getElementById('d-obd-sub').textContent }))()""")
        if not ok:
            results['ok'] = False
        cdp.ev("setPage('tablero')")
        time.sleep(1.5)
        screencap(f'd{dpi}-20-obd-tablero.png')
        cdp.ev("setPage('diagnostico')")
        time.sleep(1.2)
        screencap(f'd{dpi}-21-obd-diagnostico.png')
        cdp.ev("setPage('inicio')")
        time.sleep(2.5)
        screencap(f'd{dpi}-22-obd-inicio.png')
        cdp.ev("toggleNight()")
        time.sleep(4)
        screencap(f'd{dpi}-23-noche.png')
        cdp.ev("toggleNight()")
    log = adb('logcat', '-d', '-s', 'GoldenLauncher:D', 'AndroidRuntime:E', 'chromium:E', capture=True, check=False)
    with open(os.path.join(OUT, f'logcat-d{dpi}.txt'), 'w') as f:
        f.write(log)
    errs = [l for l in log.splitlines() if 'Uncaught' in l or 'FATAL' in l]
    info['errores_js'] = errs[:20]
    if errs:
        results['ok'] = False
    cdp.ws.close()


def main():
    fake = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(__file__), 'fake_elm.py')])
    full = open(os.path.join(OUT, 'logcat-completo.txt'), 'w')
    logcat = None
    try:
        adb('wait-for-device')
        # Registro completo de Android (para ver cualquier cierre de la app)
        logcat = subprocess.Popen(['adb', 'logcat', '-v', 'time', '*:W', 'GoldenLauncher:D', 'ActivityManager:I'], stdout=full, stderr=subprocess.STDOUT)
        # Descartar el aviso de "pantalla completa" de Android para que no tape las capturas
        adb('shell', 'settings', 'put', 'secure', 'immersive_mode_confirmations', 'confirmed', check=False)
        adb('install', '-r', '-g', APK)
        adb('shell', 'wm', 'size', '1280x720')
        run_density(160, obd_test=True)
        run_density(240, obd_test=False)
    except Exception as e:
        results['ok'] = False
        results['excepcion'] = repr(e)
        try:
            screencap('error.png')
        except Exception:
            pass
    finally:
        fake.terminate()
        if logcat:
            logcat.terminate()
        full.close()
        with open(os.path.join(OUT, 'resultados.json'), 'w') as f:
            json.dump(results, f, indent=1, ensure_ascii=False)
        print(json.dumps(results, indent=1, ensure_ascii=False))
    sys.exit(0 if results['ok'] else 1)


if __name__ == '__main__':
    main()
