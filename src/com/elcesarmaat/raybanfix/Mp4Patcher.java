package com.elcesarmaat.raybanfix;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Space for the new atom is taken from the 'free' box after 'moov', so mdat and all chunk offsets stay put.
final class Mp4Patcher {

    static final class PatchException extends Exception {
        PatchException(String message) {
            super(message);
        }
    }

    static final String NOT_FROM_GLASSES = "Este video no viene de los lentes Meta: no trae sus metadatos "
            + "(device=...). Solo se pueden arreglar videos grabados con los lentes.";
    static final String GLASSES_MOOV_AT_END = "Este video si es de los lentes, pero tiene el indice (moov) al final, "
            + "probablemente porque lo edito otra app. Ese formato todavia no esta soportado.";

    static final long MAX_PREFIX_BOX = 32L * 1024 * 1024;
    private static final byte[] CMT = {(byte) 0xA9, 'c', 'm', 't'};
    private static final byte[] DEVICE = "device=".getBytes(StandardCharsets.US_ASCII);

    static final class Box {
        final String type;
        final int headerLen;
        final byte[] raw;

        Box(String type, int headerLen, byte[] raw) {
            this.type = type;
            this.headerLen = headerLen;
            this.raw = raw;
        }
    }

    static final class Plan {
        final boolean alreadyPatched;
        final String device;
        private final List<Box> prefix;
        private final byte[] mdatHeader;
        private final long mdatContentLen;
        private final InputStream in;

        private Plan(boolean alreadyPatched, String device, List<Box> prefix, byte[] mdatHeader,
                     long mdatContentLen, InputStream in) {
            this.alreadyPatched = alreadyPatched;
            this.device = device;
            this.prefix = prefix;
            this.mdatHeader = mdatHeader;
            this.mdatContentLen = mdatContentLen;
            this.in = in;
        }

        void writeTo(OutputStream out) throws IOException {
            for (Box b : prefix) {
                out.write(b.raw);
            }
            out.write(mdatHeader);
            byte[] buf = new byte[1 << 16];
            long copied = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                copied += n;
            }
            if (mdatContentLen >= 0 && copied < mdatContentLen) {
                throw new EOFException("El video esta incompleto (faltan datos de mdat).");
            }
        }
    }

    static Plan prepare(InputStream in) throws IOException, PatchException {
        List<Box> prefix = new ArrayList<>();
        byte[] mdatHeader = null;
        long mdatContentLen = -1;
        while (true) {
            byte[] hdr = new byte[8];
            int got = readUpTo(in, hdr);
            if (got == 0) {
                break;
            }
            if (got < 8) {
                throw new PatchException("Archivo MP4 invalido (cabecera incompleta).");
            }
            long size = u32(hdr, 0);
            String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
            byte[] header = hdr;
            if (size == 1) {
                byte[] ext = new byte[8];
                readFully(in, ext, 0, 8);
                size = u64(ext, 0);
                header = concat(hdr, ext);
            }
            if (type.equals("mdat")) {
                mdatHeader = header;
                mdatContentLen = size == 0 ? -1 : size - header.length;
                break;
            }
            if (size == 0) {
                throw new PatchException("Estructura MP4 no soportada (caja '" + type + "' sin tamano).");
            }
            long contentLen = size - header.length;
            if (contentLen < 0 || contentLen > MAX_PREFIX_BOX) {
                throw new PatchException("Estructura MP4 no soportada (caja '" + type + "' de " + size
                        + " bytes antes de los datos de video).");
            }
            byte[] raw = new byte[(int) size];
            System.arraycopy(header, 0, raw, 0, header.length);
            readFully(in, raw, header.length, (int) contentLen);
            prefix.add(new Box(type, header.length, raw));
        }
        if (mdatHeader == null) {
            throw new PatchException("No encontre los datos de video (mdat) en el archivo.");
        }

        int moovIdx = indexOf(prefix, "moov");
        if (moovIdx < 0) {
            throw new PatchException(describeTrailingMoov(in, mdatContentLen));
        }

        String device = findDevice(prefix);
        if (device == null) {
            throw new PatchException(NOT_FROM_GLASSES);
        }

        Box moov = prefix.get(moovIdx);
        Box newMoov = patchMoov(moov, device);
        if (newMoov == null) {
            return new Plan(true, device, prefix, mdatHeader, mdatContentLen, in);
        }

        int delta = newMoov.raw.length - moov.raw.length;
        if (moovIdx + 1 >= prefix.size() || !isFree(prefix.get(moovIdx + 1))) {
            throw new PatchException("No hay una caja 'free' justo despues de 'moov' para insertar la etiqueta "
                    + "sin mover los datos del video.");
        }
        Box free = prefix.get(moovIdx + 1);
        int newFreeSize = free.raw.length - delta;
        if (newFreeSize < 8) {
            throw new PatchException("La caja 'free' no tiene espacio suficiente (" + free.raw.length + " bytes).");
        }
        byte[] freeRaw = new byte[newFreeSize];
        putU32(freeRaw, 0, newFreeSize);
        System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, freeRaw, 4, 4);
        System.arraycopy(free.raw, free.raw.length - (newFreeSize - 8), freeRaw, 8, newFreeSize - 8);

        prefix.set(moovIdx, newMoov);
        prefix.set(moovIdx + 1, new Box("free", 8, freeRaw));
        return new Plan(false, device, prefix, mdatHeader, mdatContentLen, in);
    }

    // Only used to pick the right error message: skips mdat and checks the trailing moov for Meta metadata.
    private static String describeTrailingMoov(InputStream in, long mdatContentLen) throws IOException, PatchException {
        if (mdatContentLen < 0) {
            return NOT_FROM_GLASSES;
        }
        skipFully(in, mdatContentLen);
        while (true) {
            byte[] hdr = new byte[8];
            int got = readUpTo(in, hdr);
            if (got < 8) {
                return NOT_FROM_GLASSES;
            }
            long size = u32(hdr, 0);
            String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
            int headerLen = 8;
            if (size == 1) {
                byte[] ext = new byte[8];
                readFully(in, ext, 0, 8);
                size = u64(ext, 0);
                headerLen = 16;
            }
            if (size < headerLen) {
                return NOT_FROM_GLASSES;
            }
            long contentLen = size - headerLen;
            if (type.equals("moov") && contentLen <= MAX_PREFIX_BOX) {
                byte[] raw = new byte[(int) size];
                readFully(in, raw, headerLen, (int) contentLen);
                List<Box> moov = new ArrayList<>();
                moov.add(new Box(type, headerLen, raw));
                return findDevice(moov) != null ? GLASSES_MOOV_AT_END : NOT_FROM_GLASSES;
            }
            skipFully(in, contentLen);
        }
    }

    /** Returns the patched moov, or null if its first udta already carries the attribution atom. */
    static Box patchMoov(Box moov, String device) throws PatchException {
        List<Box> children = parseChildren(moov);
        byte[] meta = buildMeta(device);
        int udtaIdx = indexOf(children, "udta");
        if (udtaIdx >= 0) {
            // WhatsApp only inspects the first udta and the first meta inside it, so ours goes first.
            Box udta = children.get(udtaIdx);
            List<Box> udtaChildren = parseChildren(udta);
            int metaIdx = indexOf(udtaChildren, "meta");
            if (metaIdx >= 0 && isAttributionMeta(udtaChildren.get(metaIdx))) {
                return null;
            }
            byte[] udtaContent = new byte[udta.raw.length - udta.headerLen];
            System.arraycopy(udta.raw, udta.headerLen, udtaContent, 0, udtaContent.length);
            children.set(udtaIdx, withContent(udta, concat(meta, udtaContent)));
        } else {
            int insertAt = children.size();
            if (insertAt > 0 && children.get(insertAt - 1).headerLen == 0) {
                insertAt--;
            }
            children.add(insertAt, new Box("udta", 8, atom("udta", meta)));
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (Box c : children) {
            content.write(c.raw, 0, c.raw.length);
        }
        return withContent(moov, content.toByteArray());
    }

    private static byte[] buildMeta(String device) {
        byte[] value = ("app=Meta AI&device=" + device).getBytes(StandardCharsets.UTF_8);
        byte[] data = atom("data", concat(u32Bytes(1), u32Bytes(0), value));
        byte[] cmt = atom(CMT, data);
        byte[] ilst = atom("ilst", cmt);
        byte[] hdlr = atom("hdlr", concat(u32Bytes(0), u32Bytes(0), ascii("mdir"), ascii("appl"),
                u32Bytes(0), u32Bytes(0), new byte[]{0}));
        return atom("meta", concat(u32Bytes(0), hdlr, ilst));
    }

    static boolean isAttributionMeta(Box meta) {
        return indexOf(meta.raw, CMT, 0) >= 0 && indexOf(meta.raw, DEVICE, 0) >= 0;
    }

    static String findDevice(List<Box> prefix) {
        for (Box b : prefix) {
            int from = 0;
            while (true) {
                int at = indexOf(b.raw, DEVICE, from);
                if (at < 0) {
                    break;
                }
                int start = at + DEVICE.length;
                int end = start;
                while (end < b.raw.length && end - start < 100) {
                    int c = b.raw[end] & 0xFF;
                    if (c == '&' || c < 0x20 || c > 0x7E) {
                        break;
                    }
                    end++;
                }
                if (end > start) {
                    return new String(b.raw, start, end - start, StandardCharsets.US_ASCII).trim();
                }
                from = start;
            }
        }
        return null;
    }

    static List<Box> parseChildren(Box parent) throws PatchException {
        List<Box> out = new ArrayList<>();
        byte[] raw = parent.raw;
        int pos = parent.headerLen;
        int end = raw.length;
        while (pos < end) {
            if (end - pos < 8) {
                // Trailing bytes too short to be a box (e.g. QuickTime's 32-bit udta terminator): keep verbatim.
                byte[] tail = new byte[end - pos];
                System.arraycopy(raw, pos, tail, 0, tail.length);
                out.add(new Box("", 0, tail));
                break;
            }
            long size = u32(raw, pos);
            String type = new String(raw, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int headerLen = 8;
            if (size == 1) {
                if (end - pos < 16) {
                    throw new PatchException("Caja '" + type + "' truncada dentro de '" + parent.type + "'.");
                }
                size = u64(raw, pos + 8);
                headerLen = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerLen || pos + size > end) {
                throw new PatchException("Caja '" + type + "' con tamano invalido dentro de '" + parent.type + "'.");
            }
            byte[] child = new byte[(int) size];
            System.arraycopy(raw, pos, child, 0, child.length);
            out.add(new Box(type, headerLen, child));
            pos += (int) size;
        }
        return out;
    }

    private static Box withContent(Box box, byte[] content) {
        byte[] type = box.type.getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw;
        if (box.headerLen == 16) {
            raw = new byte[16 + content.length];
            putU32(raw, 0, 1);
            System.arraycopy(type, 0, raw, 4, 4);
            putU64(raw, 8, raw.length);
            System.arraycopy(content, 0, raw, 16, content.length);
        } else {
            raw = new byte[8 + content.length];
            putU32(raw, 0, raw.length);
            System.arraycopy(type, 0, raw, 4, 4);
            System.arraycopy(content, 0, raw, 8, content.length);
        }
        return new Box(box.type, box.headerLen, raw);
    }

    private static boolean isFree(Box b) {
        return b.type.equals("free") || b.type.equals("skip");
    }

    static int indexOf(List<Box> boxes, String type) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).type.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] atom(String type, byte[] content) {
        return atom(ascii(type), content);
    }

    private static byte[] atom(byte[] type, byte[] content) {
        byte[] raw = new byte[8 + content.length];
        putU32(raw, 0, raw.length);
        System.arraycopy(type, 0, raw, 4, 4);
        System.arraycopy(content, 0, raw, 8, content.length);
        return raw;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] u32Bytes(long v) {
        byte[] b = new byte[4];
        putU32(b, 0, v);
        return b;
    }

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void putU64(byte[] b, int off, long v) {
        putU32(b, off, v >>> 32);
        putU32(b, off + 4, v);
    }

    static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new EOFException("El archivo termino antes de lo esperado.");
                }
                skipped = 1;
            }
            n -= skipped;
        }
    }

    static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n == -1) {
                throw new EOFException("El archivo termino antes de lo esperado.");
            }
            total += n;
        }
    }
}
