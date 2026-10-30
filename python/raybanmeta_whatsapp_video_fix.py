"""
Workaround para que los videos de Ray-Ban Meta Smart Glasses muestren la
insignia de atribucion "Ray-Ban Meta" al publicarlos en un estado de WhatsApp.

Causa raiz (confirmada por ingenieria inversa de com.whatsapp 2.26.36.74,
clase ofuscada X.C175477nH): el parser de video de WhatsApp busca a mano una
caja `udta` hija directa de `moov`, conteniendo `meta` (handler "mdir"/"appl")
-> `ilst` -> un atomo hijo llamado literalmente "\xa9cmt" con un substring
"device=<modelo>" adentro. Las capturas recientes de los lentes ya no incluyen
ese `udta` en absoluto (solo escriben un `meta` con el esquema moderno
keys+ilst-por-indice, que este parser de WhatsApp no lee), asi que la
atribucion nunca se dispara para video.

Este script inyecta un `udta` valido, hecho a mano, como hijo adicional de
`moov`, reutilizando el espacio ya existente de la caja `free` que sigue a
`moov` -- es decir, NO mueve ni un solo byte de `mdat` ni de las tablas de
offsets de los tracks. El archivo resultante es identico al original salvo
por esa caja agregada.

Uso:
    python raybanmeta_whatsapp_video_fix.py entrada.mp4 salida.mp4 ["Ray-Ban Meta Smart Glasses 2"]
"""
import struct
import sys


def read_top_boxes(data):
    boxes = []
    pos = 0
    n = len(data)
    while pos < n:
        size32 = struct.unpack('>I', data[pos:pos + 4])[0]
        btype = data[pos + 4:pos + 8].decode('latin1')
        header_len = 8
        if size32 == 1:
            size = struct.unpack('>Q', data[pos + 8:pos + 16])[0]
            header_len = 16
        elif size32 == 0:
            size = n - pos
        else:
            size = size32
        boxes.append({'type': btype, 'start': pos, 'header_len': header_len,
                      'size': size, 'content_start': pos + header_len,
                      'content_len': size - header_len})
        pos += size
    return boxes


def find(boxes, t):
    for b in boxes:
        if b['type'] == t:
            return b
    return None


def build_atom(fourcc, content):
    return struct.pack('>I', 8 + len(content)) + fourcc.encode('latin1') + content


def build_udta_blob(device_model: str) -> bytes:
    comment_value = f"app=Meta AI&device={device_model}".encode('utf-8')

    data_content = struct.pack('>I', 1) + struct.pack('>I', 0) + comment_value
    data_atom = build_atom('data', data_content)

    cmt_atom = build_atom('\xa9cmt', data_atom)
    ilst_atom = build_atom('ilst', cmt_atom)

    hdlr_content = (struct.pack('>I', 0) + struct.pack('>I', 0) + b'mdir' + b'appl' +
                     struct.pack('>I', 0) + struct.pack('>I', 0) + b'\x00')
    hdlr_atom = build_atom('hdlr', hdlr_content)

    meta_content = struct.pack('>I', 0) + hdlr_atom + ilst_atom
    meta_atom = build_atom('meta', meta_content)

    return build_atom('udta', meta_atom)


def patch(in_path: str, out_path: str, device_model: str):
    with open(in_path, 'rb') as f:
        orig = bytearray(f.read())

    boxes = read_top_boxes(orig)
    moov = find(boxes, 'moov')
    free = find(boxes, 'free')
    mdat = find(boxes, 'mdat')

    if moov is None:
        raise SystemExit("ERROR: el archivo no tiene una caja 'moov' de nivel superior.")
    if moov['header_len'] != 8:
        raise SystemExit("ERROR: 'moov' usa largesize de 64 bits; este script no lo soporta todavia.")

    udta_blob = build_udta_blob(device_model)
    inject_len = len(udta_blob)

    existing_udta = None
    pos = moov['content_start']
    end = moov['content_start'] + moov['content_len']
    while pos < end:
        size = struct.unpack('>I', orig[pos:pos + 4])[0]
        typ = orig[pos + 4:pos + 8].decode('latin1')
        if typ == 'udta':
            existing_udta = pos
            break
        pos += size
    if existing_udta is not None:
        print("AVISO: el archivo ya tiene una caja 'udta' bajo moov (podria contener ©mak/©mod "
              "clasicos, o quedar antes de la que agreguemos y 'ganar' en el escaneo de WhatsApp). "
              "Revisa manualmente si el resultado no funciona.")

    if free is None or free['start'] != moov['start'] + moov['size']:
        raise SystemExit("ERROR: no se encontro una caja 'free' inmediatamente despues de 'moov'; "
                          "este script necesita ese espacio libre para no mover mdat/offsets.")
    if free['content_len'] < inject_len + 8:
        raise SystemExit(f"ERROR: la caja 'free' solo tiene {free['content_len']} bytes, "
                          f"se necesitan {inject_len + 8}.")

    new_moov_size = moov['size'] + inject_len
    new_free_start = free['start'] + inject_len
    new_free_size = free['size'] - inject_len

    struct.pack_into('>I', orig, moov['start'], new_moov_size)
    orig[free['start']:free['start'] + inject_len] = udta_blob
    struct.pack_into('>I', orig, new_free_start, new_free_size)
    orig[new_free_start + 4:new_free_start + 8] = b'free'

    with open(out_path, 'wb') as f:
        f.write(orig)

    # verificacion
    with open(out_path, 'rb') as f:
        patched = f.read()
    patched_boxes = read_top_boxes(patched)
    new_mdat = find(patched_boxes, 'mdat')
    if mdat is None or new_mdat is None or mdat['start'] != new_mdat['start'] or mdat['size'] != new_mdat['size']:
        raise SystemExit("ERROR DE VERIFICACION: mdat cambio de posicion/tamano, no uses este archivo.")

    print(f"OK: {out_path} escrito ({len(patched)} bytes, igual al original).")
    print(f"    mdat permanece en el offset {mdat['start']} sin cambios.")
    print(f"    Se agrego udta/meta/hdlr/ilst/©cmt con device={device_model!r} ({inject_len} bytes).")


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    in_path = sys.argv[1]
    out_path = sys.argv[2]
    model = sys.argv[3] if len(sys.argv) > 3 else "Ray-Ban Meta Smart Glasses 2"
    patch(in_path, out_path, model)
