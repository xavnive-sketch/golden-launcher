# ELM327 simulado para las pruebas del APK en el emulador (responde como un vehiculo real)
import socketserver, sys
R={'ATZ':'ELM327 v1.5','ATDPN':'A6','0100':'SEARCHING...\r4100BE3FA813','010C':'410C1AF8','010D':'410D32','0105':'41057B',
   '015C':'NO DATA','010B':'410B8C','0133':'413365','0111':'41114D','0106':'410685','0107':'41077A','012F':'412F80',
   '0142':'41423840','011F':'411F0E10','01A6':'NO DATA','03':'43020133D300'}
class H(socketserver.StreamRequestHandler):
    def handle(self):
        buf=b''
        while True:
            c=self.request.recv(64)
            if not c: return
            buf+=c
            while b'\r' in buf:
                cmd,buf=buf.split(b'\r',1); cmd=cmd.decode().strip().upper()
                out=R.get(cmd,'OK' if cmd.startswith('AT') else 'NO DATA')
                self.request.sendall((out+'\r\r>').encode())
socketserver.ThreadingTCPServer.allow_reuse_address=True
socketserver.ThreadingTCPServer(('0.0.0.0',35099),H).serve_forever()
