# ELM327 simulado para las pruebas del APK: se comporta como un motor DIÉSEL típico
# (no envía ajustes de combustible, nivel de combustible, temp. de aceite ni odómetro).
import socketserver

VALUES = {  # PID -> bytes de respuesta (hex)
    '04': '4C', '05': '78', '0C': '0C2C', '0D': '32', '0F': '41', '10': '0190', '0B': '8C', '33': '65',
    '1F': '0E10', '42': '', '46': '3A', '49': '33', '5E': '0064', '23': '0C80', '2C': '40', '31': '0FA0',
}
VALUES = {k: v for k, v in VALUES.items() if v}
SUPPORTED = set(int(k, 16) for k in VALUES)


def mask(base):
    m = 0
    for i in range(32):
        pid = base + i + 1
        if pid in SUPPORTED or (pid % 0x20 == 0 and any(p > pid for p in SUPPORTED)):
            m |= 1 << (31 - i)
    return '%08X' % m


R = {'ATZ': 'ELM327 v2.1', 'ATDPN': 'A6', 'ATRV': '13.8V', '03': '43020133D300'}
for b in range(0, 0xE0, 0x20):
    R['01%02X' % b] = ('SEARCHING...\r' if b == 0 else '') + '41%02X' % b + mask(b) if mask(b) != '00000000' else 'NO DATA'
for k, v in VALUES.items():
    R['01' + k] = '41' + k + v


class H(socketserver.StreamRequestHandler):
    def handle(self):
        buf = b''
        while True:
            c = self.request.recv(64)
            if not c:
                return
            buf += c
            while b'\r' in buf:
                cmd, buf = buf.split(b'\r', 1)
                cmd = cmd.decode().strip().upper()
                out = R.get(cmd, 'OK' if cmd.startswith('AT') else 'NO DATA')
                self.request.sendall((out + '\r\r>').encode())


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(('0.0.0.0', 35099), H).serve_forever()
