# "Running/rambling" simulator: continuously load DENSE, CONTIGUOUS chunks in a
# moving window, mimicking a player whose view distance keeps entering fresh
# terrain. Uses MC's ranged `forceload add x1 z1 x2 z2` so each batch is a solid
# rectangle (not sparse points) -> the worldgen queue stays saturated like real
# running. lean-heap AllocationSampler records bytes/s + GC into logs/.
import socket, struct, time, os

HOST='127.0.0.1'; PORT=25575; PASSWORD='leanheap'
HALF = int(os.environ.get('RUN_HALF','10'))      # window half-size -> (2H+1)^2 chunks
ADVANCE = int(os.environ.get('RUN_ADVANCE','6')) # chunks to move forward each batch
WINDOWS = int(os.environ.get('RUN_WINDOWS','3'))# active windows kept loaded
BATCH_SEC = float(os.environ.get('RUN_BATCH_SEC','2.5'))
DURATION = int(os.environ.get('STRESS_DURATION','240'))

def packet(kind,payload):
    body=struct.pack('<ii',kind,0)+payload.encode('utf-8')+b'\x00\x00'
    return struct.pack('<i',len(body))+body

def recv_exact(s,n):
    buf=b''
    while len(buf)<n:
        c=s.recv(n-len(buf))
        if not c: raise EOFError
        buf+=c
    return buf

def rcon(s,cmd):
    s.sendall(packet(3,cmd))
    size=struct.unpack('<i',recv_exact(s,4))[0]
    return recv_exact(s,size)[8:].split(b'\x00',1)[0].decode('utf-8','replace')

def rect(anchor):
    # anchor = leading edge coordinate; window extends backward in X
    x2=anchor; x1=anchor-2*HALF
    z1=-HALF; z2=HALF
    return x1,z1,x2,z2

def main():
    deadline=time.time()+120
    while True:
        try:
            s=socket.create_connection((HOST,PORT),timeout=3); break
        except OSError:
            if time.time()>deadline:
                print('RCON unreachable',flush=True); return 2
            time.sleep(2)
    s.sendall(packet(2,PASSWORD))
    recv_exact(s,struct.unpack('<i',recv_exact(s,4))[0])
    print('RCON authenticated',flush=True)
    rcon(s,'gamerule doMobSpawning false')
    rcon(s,'gamerule doFireTick false')

    start=time.time(); anchors=[]; batch=0
    per_window=(2*HALF+1)**2
    print(f'window={per_window} chunks, advance={ADVANCE}, keep={WINDOWS} windows',flush=True)
    while time.time()-start<DURATION:
        anchor=batch*ADVANCE
        anchors.append(anchor)
        x1,z1,x2,z2=rect(anchor)
        rcon(s,f'forceload add {x1} {z1} {x2} {z2}')
        # drop oldest window(s) so memory stays bounded
        while len(anchors)>WINDOWS:
            old=anchors.pop(0)
            ox1,oz1,ox2,oz2=rect(old)
            rcon(s,f'forceload remove {ox1} {oz1} {ox2} {oz2}')
        active=sum((2*HALF+1)**2 for _ in anchors)
        print(f'batch {batch}: anchor={anchor} loaded~{active} chunks '
              f't={time.time()-start:.0f}s',flush=True)
        batch+=1
        time.sleep(BATCH_SEC)
    print(f'RUN SIM DONE: {batch} batches, {time.time()-start:.0f}s',flush=True)
    # release all before save so we measure generation, not retention
    for a in list(anchors):
        x1,z1,x2,z2=rect(a)
        rcon(s,f'forceload remove {x1} {z1} {x2} {z2}')
    rcon(s,'save-all'); time.sleep(3); s.close()

if __name__=='__main__':
    main()