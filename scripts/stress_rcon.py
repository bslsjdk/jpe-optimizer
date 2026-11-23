# RCON client + continuous chunk-gen stress driver.
# Keeps the server generating NEW terrain for a fixed duration by rolling a
# moving window of forceloaded chunks: add a fresh far-away grid, then release
# the oldest grid so memory stays bounded but generation never idles.
# lean-heap AllocationSampler records bytes/s + GC into logs/jpe-optimizer/*.csv.
import socket, struct, time, sys, os

HOST = '127.0.0.1'
PORT = 25575
PASSWORD = 'leanheap'
GRID = int(os.environ.get('STRESS_GRID', '24'))      # grid half-size in chunks
STEP = int(os.environ.get('STRESS_STEP', '12'))      # spacing between forced columns
DURATION = int(os.environ.get('STRESS_DURATION', '300'))  # seconds of continuous loading

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

def rcon(s, cmd):
    s.sendall(packet(3, cmd))
    size = struct.unpack('<i', recv_exact(s, 4))[0]
    body = recv_exact(s, size)
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
    s.sendall(packet(2, PASSWORD))
    recv_exact(s, struct.unpack('<i', recv_exact(s, 4))[0])
    print('RCON authenticated', flush=True)

    rcon(s, 'gamerule doMobSpawning false')
    rcon(s, 'gamerule doFireTick false')
    print('gamerules set', flush=True)

    start = time.time()
    windows = []   # list of coord lists, rolling
    batch = 0
    total_cmds = 0
    # start around origin, then march outward in a spiral of ever-new chunks
    while time.time() - start < DURATION:
        cx0 = batch * (GRID * 2 + 8)
        coords = []
        for dx in range(-GRID, GRID, STEP):
            for dz in range(-GRID, GRID, STEP):
                coords.append((cx0 + dx, dz))
        # add new window
        for x, z in coords:
            rcon(s, f'forceload add {x} {z}')
            total_cmds += 1
        windows.append(coords)
        # release oldest window to keep memory bounded
        if len(windows) > 3:
            old = windows.pop(0)
            for x, z in old:
                rcon(s, f'forceload remove {x} {z}')
                total_cmds += 1
        elapsed = time.time() - start
        print(f'batch {batch}: +{len(coords)} cols, windows={len(windows)}, '
              f'total_cmds={total_cmds}, t={elapsed:.0f}s', flush=True)
        batch += 1
        time.sleep(2)

    print(f'STRESS DONE: {total_cmds} forceload cmds, {batch} batches in '
          f'{time.time()-start:.0f}s', flush=True)
    rcon(s, 'save-all')
    time.sleep(3)
    s.close()

if __name__ == '__main__':
    main()