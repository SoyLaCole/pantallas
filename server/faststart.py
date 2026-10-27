"""Mueve el indice (moov) de un MP4 al principio del archivo, sin recodificar.
Los TV (sobre todo LG) no arrancan bien los MP4 cuyo indice esta al final cuando se ven por la red."""
import os
import struct

_CONTAINERS = (b"trak", b"mdia", b"minf", b"stbl", b"edts", b"udta")


def _atoms(f, start, end):
    pos = start
    while pos + 8 <= end:
        f.seek(pos)
        h = f.read(8)
        size, typ = struct.unpack(">I4s", h)
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", f.read(8))[0]
            hdr = 16
        elif size == 0:
            size = end - pos
        if size < hdr:
            raise ValueError("atomo invalido")
        yield pos, size, hdr, typ
        pos += size


def _shift(buf, delta):
    """Ajusta stco/co64 dentro del contenido de moov (buf es bytearray del moov completo)."""
    def walk(start, end):
        pos = start
        while pos + 8 <= end:
            size, typ = struct.unpack_from(">I4s", buf, pos)
            hdr = 8
            if size == 1:
                size = struct.unpack_from(">Q", buf, pos + 8)[0]
                hdr = 16
            if size < hdr:
                raise ValueError("atomo invalido")
            if typ in _CONTAINERS:
                walk(pos + hdr, pos + size)
            elif typ == b"stco":
                n = struct.unpack_from(">I", buf, pos + hdr + 4)[0]
                for i in range(n):
                    o = pos + hdr + 8 + 4 * i
                    v = struct.unpack_from(">I", buf, o)[0] + delta
                    if v > 0xFFFFFFFF:
                        raise ValueError("desbordamiento stco")
                    struct.pack_into(">I", buf, o, v)
            elif typ == b"co64":
                n = struct.unpack_from(">I", buf, pos + hdr + 4)[0]
                for i in range(n):
                    o = pos + hdr + 8 + 8 * i
                    struct.pack_into(">Q", buf, o, struct.unpack_from(">Q", buf, o)[0] + delta)
            pos += size
    walk(8, len(buf))  # moov: el contenido empieza tras su cabecera de 8 bytes


def faststart(path):
    """Devuelve True si reescribio el archivo. Si algo no cuadra, deja el original intacto."""
    out = path + ".fs"
    try:
        total = os.path.getsize(path)
        with open(path, "rb") as f:
            atoms = list(_atoms(f, 0, total))
            types = [a[3] for a in atoms]
            if b"moov" not in types or b"mdat" not in types:
                return False
            mi, di = types.index(b"moov"), types.index(b"mdat")
            if mi < di:
                return False  # ya esta al principio
            mpos, msize, mhdr, _ = atoms[mi]
            if mhdr != 8:
                return False
            f.seek(mpos)
            moov = bytearray(f.read(msize))
            _shift(moov, msize)  # el moov se inserta antes de mdat: todo lo posterior se corre msize bytes
            # el moov va justo despues de ftyp (si existe), o al inicio
            insert_at = 0
            for pos, size, hdr, typ in atoms:
                if typ == b"ftyp":
                    insert_at = pos + size
                    break
            with open(out, "wb") as o:
                f.seek(0)
                left = insert_at
                while left:
                    b = f.read(min(left, 1 << 20))
                    o.write(b)
                    left -= len(b)
                o.write(moov)
                pos = insert_at
                f.seek(pos)
                while pos < total:
                    if pos == mpos:
                        pos += msize
                        f.seek(pos)
                        continue
                    n = min(1 << 20, (mpos if pos < mpos else total) - pos)
                    b = f.read(n)
                    o.write(b)
                    pos += len(b)
        os.replace(out, path)
        return True
    except Exception:
        try:
            os.remove(out)
        except OSError:
            pass
        return False
