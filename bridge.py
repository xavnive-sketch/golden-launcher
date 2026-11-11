#!/usr/bin/env python3
"""
bridge.py · Servidor del dashboard + puente OBD-II (ELM327 Wi-Fi)

- Sirve los archivos del dashboard (index.html, bus3d.js, assets/, vendor/).
- Se conecta a un adaptador ELM327 Wi-Fi (por defecto 192.168.0.10:35000),
  lee los PID estándar de OBD-II y los códigos de avería (DTC), y los publica
  en  GET /obd  como JSON para el dashboard.
- Si el adaptador no responde, el dashboard sigue funcionando y muestra
  "OBD-II no conectado"; el puente reintenta cada pocos segundos.

Solo usa la librería estándar de Python (funciona en Termux sin pip).

Uso:
    python bridge.py                         # dashboard en http://localhost:8080
    python bridge.py --elm-host 192.168.0.10 --elm-port 35000
    python bridge.py --no-obd                # solo servir el dashboard
Variables de entorno equivalentes: ELM_HOST, ELM_PORT, DASH_PORT.
"""
import argparse
import json
import os
import re
import socket
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))


def _u16(b):
    return b[0] * 256 + b[1]


# PID modo 01 -> (clave, bytes necesarios, fórmula)
PIDS = {
    '0C': ('rpm', 2, lambda b: round(_u16(b) / 4)),
    '0D': ('speed', 1, lambda b: b[0]),
    '05': ('temp', 1, lambda b: b[0] - 40),
    '5C': ('oil', 1, lambda b: b[0] - 40),
    '0B': ('map', 1, lambda b: b[0]),
    '33': ('baro', 1, lambda b: b[0]),
    '11': ('throttle', 1, lambda b: round(b[0] * 100 / 255, 1)),
    '06': ('stft', 1, lambda b: round((b[0] - 128) * 100 / 128, 1)),
    '07': ('ltft', 1, lambda b: round((b[0] - 128) * 100 / 128, 1)),
    '2F': ('fuel', 1, lambda b: round(b[0] * 100 / 255)),
    '42': ('volts', 2, lambda b: round(_u16(b) / 1000, 1)),
    '1F': ('runtime', 2, _u16),
    'A6': ('odo', 4, lambda b: round(((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) / 10)),
}
FAST = ('0C', '0D', '11')          # se leen en cada vuelta
DTC_EVERY = 30                     # segundos entre lecturas de códigos de avería


class ELM327:
    def __init__(self, host, port):
        self.host, self.port = host, port
        self.sock = None
        self.is_can = True

    def connect(self):
        self.close()
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        self.sock.settimeout(5)
        self.cmd('ATZ', 6)
        for c in ('ATE0', 'ATL0', 'ATS0', 'ATH0', 'ATSP0'):
            self.cmd(c)
        # Fuerza la búsqueda de protocolo con una consulta real
        self.cmd('0100', 15)
        proto = ''.join(self.cmd('ATDPN')).strip().upper().lstrip('A')
        self.is_can = proto[:1] in ('6', '7', '8', '9', 'A', 'B', 'C')

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    def cmd(self, c, timeout=5):
        self.sock.sendall((c + '\r').encode('ascii'))
        buf = b''
        deadline = time.time() + timeout
        while b'>' not in buf:
            if time.time() > deadline:
                raise TimeoutError('ELM327 no respondió a ' + c)
            chunk = self.sock.recv(512)
            if not chunk:
                raise ConnectionError('el ELM327 cerró la conexión')
            buf += chunk
        txt = buf.decode('ascii', 'ignore').replace('>', '').replace('\r', '\n')
        lines = [l.strip() for l in txt.split('\n')]
        return [l for l in lines if l and l != c and not l.startswith('SEARCHING')]

    def query(self, pid):
        """Devuelve los bytes de datos de un PID de modo 01, o None si no hay dato."""
        lines = self.cmd('01' + pid, 10)
        joined = ' '.join(lines).upper()
        if 'UNABLE TO CONNECT' in joined or 'BUS INIT' in joined and 'ERROR' in joined:
            raise EngineOff()
        for l in lines:
            l = l.replace(' ', '').upper()
            i = l.find('41' + pid)
            if i >= 0:
                h = l[i + 4:]
                try:
                    return [int(h[k:k + 2], 16) for k in range(0, len(h) - 1, 2)]
                except ValueError:
                    return None
        return None

    def read_dtc(self):
        lines = self.cmd('03', 10)
        data, chunks = '', []
        for l in lines:
            l = l.replace(' ', '').upper()
            if l in ('NODATA', '?', 'STOPPED'):
                continue
            if re.fullmatch(r'[0-9A-F]{3}', l):      # cabecera de longitud (multi-trama CAN)
                continue
            m = re.match(r'^[0-9A-F]:(.*)$', l)       # "0:4302..." multi-trama
            if m:
                data += m.group(1)
            elif re.fullmatch(r'[0-9A-F]+', l):
                chunks.append(l)
        if self.is_can:
            payload = data or ''.join(chunks)
            i = payload.find('43')
            if i < 0:
                return []
            payload = payload[i + 2:]
            n = int(payload[:2], 16) if len(payload) >= 2 else 0
            payload = payload[2:2 + n * 4]
        else:
            payload = ''.join(c[2:] for c in chunks if c.startswith('43'))
        codes = []
        for k in range(0, len(payload) - 3, 4):
            g = payload[k:k + 4]
            if g == '0000':
                continue
            v = int(g, 16)
            code = 'PCBU'[v >> 14] + str((v >> 12) & 3) + g[1:]
            if code not in codes:
                codes.append(code)
        return codes


class EngineOff(Exception):
    pass


class ObdState:
    def __init__(self):
        self.lock = threading.Lock()
        self.snapshot = {'connected': False, 'ecu': False, 'error': 'Iniciando…',
                         'data': {}, 'dtc': None, 'ts': 0}

    def set(self, **kw):
        with self.lock:
            self.snapshot.update(kw)

    def get(self):
        with self.lock:
            return json.loads(json.dumps(self.snapshot))


def obd_loop(state, host, port):
    elm = ELM327(host, port)
    trip_km, trip_s, vmax = 0.0, 0.0, 0
    fail = {}
    while True:
        try:
            state.set(connected=False, ecu=False, error='Conectando a %s:%s…' % (host, port))
            elm.connect()
            state.set(connected=True, error=None)
            data, last_t, last_dtc, n = {}, time.time(), 0, 0
            while True:
                n += 1
                for pid in PIDS:
                    if pid not in FAST and n % 5:
                        continue
                    f = fail.get(pid, (0, 0))
                    if f[0] >= 3 and time.time() - f[1] < 60:
                        continue                     # PID no soportado: reintentar en 1 min
                    key, size, fn = PIDS[pid]
                    b = elm.query(pid)
                    if b is None or len(b) < size:
                        fail[pid] = (f[0] + 1, time.time())
                        data.pop(key, None)
                    else:
                        fail.pop(pid, None)
                        data[key] = fn(b[:size])
                now = time.time()
                dt, last_t = now - last_t, now
                spd = data.get('speed')
                if spd is not None and dt < 5:
                    trip_km += spd * dt / 3600.0
                    vmax = max(vmax, spd)
                if data.get('rpm'):
                    trip_s += dt if dt < 5 else 0
                if 'map' in data and 'baro' in data:
                    data['boost'] = round((data['map'] - data['baro']) / 100.0, 2)
                out = dict(data, trip=round(trip_km, 1), trip_time=int(trip_s), vmax=vmax)
                upd = {'ecu': True, 'error': None, 'data': out, 'ts': now}
                if now - last_dtc > DTC_EVERY:
                    try:
                        upd['dtc'] = elm.read_dtc()
                    except (TimeoutError, ValueError):
                        pass
                    last_dtc = now
                state.set(**upd)
                time.sleep(0.2)
        except EngineOff:
            state.set(connected=True, ecu=False, error='Adaptador OK, pero la ECU no responde (¿motor apagado?)')
            time.sleep(3)
        except (OSError, TimeoutError, ConnectionError) as e:
            state.set(connected=False, ecu=False, dtc=None, error=str(e) or e.__class__.__name__)
            elm.close()
            time.sleep(5)


def make_handler(state):
    class Handler(SimpleHTTPRequestHandler):
        extensions_map = dict(SimpleHTTPRequestHandler.extensions_map, **{
            '.glb': 'model/gltf-binary', '.js': 'text/javascript',
            '.woff2': 'font/woff2', '.css': 'text/css'})

        def __init__(self, *a, **kw):
            super().__init__(*a, directory=ROOT, **kw)

        def do_GET(self):
            if self.path.split('?')[0] == '/obd':
                body = json.dumps(state.get() if state else
                                  {'connected': False, 'ecu': False, 'error': 'Puente OBD desactivado (--no-obd)',
                                   'data': {}, 'dtc': None, 'ts': 0}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            super().do_GET()

        def log_message(self, fmt, *args):
            if args and str(args[1])[:1] in ('4', '5'):
                super().log_message(fmt, *args)
    return Handler


def main():
    p = argparse.ArgumentParser(description='Dashboard Golden Dragon + puente OBD-II (ELM327 Wi-Fi)')
    p.add_argument('--port', type=int, default=int(os.environ.get('DASH_PORT', 8080)))
    p.add_argument('--host', default='127.0.0.1', help='interfaz de escucha (127.0.0.1 = solo la radio)')
    p.add_argument('--elm-host', default=os.environ.get('ELM_HOST', '192.168.0.10'))
    p.add_argument('--elm-port', type=int, default=int(os.environ.get('ELM_PORT', 35000)))
    p.add_argument('--no-obd', action='store_true', help='no intentar conectar con el ELM327')
    a = p.parse_args()

    state = None
    if not a.no_obd:
        state = ObdState()
        threading.Thread(target=obd_loop, args=(state, a.elm_host, a.elm_port), daemon=True).start()
        print('OBD-II: buscando ELM327 en %s:%s' % (a.elm_host, a.elm_port))
    srv = ThreadingHTTPServer((a.host, a.port), make_handler(state))
    print('Dashboard: http://localhost:%d   (Ctrl+C para detener)' % a.port)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
