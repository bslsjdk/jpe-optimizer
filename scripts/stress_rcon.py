# RCON client + chunk-gen stress driver.
# Connects to a running NeoForge server RCON, then forces loading a large grid
# of chunks so the server generates terrain continuously while the lean-heap
# mod's AllocationSampler records bytes/s + GC into logs/jpe-optimizer/*.csv.
import socket, struct, time, sys, os

HOST = '127.0.0.1'
PORT = 25575
PASSWORD = 'leanheap'
GRID = int(os.environ.get('STRESS_GRID', '40'))   # 40x40 = 1600 chunks per axis pass

def packet(kind, payload):
    body = struct.pack('<ii', kind, 0) + payload.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(body)) + body

def recv_exact(s, n):
    buf = b''
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c: raise EOFError
        buf += c
    return buf

def rcon(s, cmd, req=1):
    s.sendall(packet(3, cmd))  # SERVERDATA_EXECCOMMAND
    size = struct.unpack('<i', recv_exact(s, 4))[0]
    body = recv_exact(s, size)
    _req, _type = struct.unpack_from('<ii', body, 0)
    return body[8:].split(b'\x00', 1)[0].decode('utf-8', 'replace')

def main():
    deadline = time.time() + 120
    while True:
        try:
            s = socket.create_connection((HOST, PORT), timeout=3)
            break
        except OSError:
            if time.time() > deadline:
                print('RCON not reachable in time', flush=True); sys.exit(2)
            time.sleep(2)
    s.sendall(packet(2, PASSWORD))  # auth
    size = struct.unpack('<i', recv_exact(s, 4))[0]
    recv_exact(s, size)
    print('RCON authenticated', flush=True)

    print(rcon(s, 'gamerule doMobSpawning false'), flush=True)
    print(rcon(s, 'gamerule doTickSpeedUpdate true'), flush=True)
    start = time.time()
    count = 0
    # Sweep expanding rings: forceload huge grids at increasing distance,
    # forcing fresh chunk generation across ever-new terrain.
    for ring in range(6):
        base = ring * GRID
        for cx in range(-GRID, GRID, 8):
            for cz in range(-GRID, GRID, 8):
                x = cx + base
                z = cz + base
                rcon(s, f'forceload add {x} {z}')
                count += 1
        print(f'ring {ring}: forced {count} chunk columns, t={time.time()-start:.0f}s', flush=True)
    print(f'STRESS DONE: {count} forceload commands in {time.time()-start:.0f}s', flush=True)
    # let generation drain
    time.sleep(20)
    rcon(s, 'save-all')
    time.sleep(3)
    s.close()

if __name__ == '__main__':
    main()