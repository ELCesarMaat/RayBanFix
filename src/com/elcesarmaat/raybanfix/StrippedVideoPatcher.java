package com.elcesarmaat.raybanfix;

import com.elcesarmaat.raybanfix.Mp4Patcher.Box;
import com.elcesarmaat.raybanfix.Mp4Patcher.PatchException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// For glasses videos that went through WhatsApp, Instagram, Telegram... and lost their metadata, so the model comes
// from the user. Those copies rarely have a 'free' box after 'moov' and some keep 'moov' at the end, so when there is
// no free space to borrow, everything after 'moov' moves and the chunk offsets (stco/co64) are updated to match.
// The attribution atom itself comes from Mp4Patcher.patchMoov, the same one the glasses fix writes.
final class StrippedVideoPatcher {

    interface Source {
        InputStream open() throws IOException;
    }

    static final String DEFAULT_DEVICE = "Ray-Ban Meta Smart Glasses 2";
    // Mp4Patcher.findDevice reads at most this many characters back.
    static final int MAX_DEVICE_LENGTH = 100;

    static final String NO_MOOV = "No encontre el indice del video (moov); el archivo no parece un MP4 valido.";
    static final String FRAGMENTED = "Este video esta en formato MP4 fragmentado, que todavia no esta soportado.";
    static final String TOO_BIG = "El video es demasiado grande (mas de 4 GB) para agregarle la etiqueta.";

    // Small top-level boxes besides 'moov' (uuid, meta...) are kept only to look for the glasses' own "device=" tag.
    private static final long MAX_SCANNED_BOX = 1024 * 1024;

    static final class Scan {
        /** Model still present in the video's own metadata, or null if it was stripped. */
        final String detectedDevice;
        /** The first udta already carries the attribution atom, so the file can be shared as is. */
        final boolean alreadyTagged;
        private final Source source;
        private final long moovStart;
        private final Box moov;
        private final long freeAfterMoov;
        private final boolean moovIsLast;

        private Scan(String detectedDevice, boolean alreadyTagged, Source source, long moovStart, Box moov,
                     long freeAfterMoov, boolean moovIsLast) {
            this.detectedDevice = detectedDevice;
            this.alreadyTagged = alreadyTagged;
            this.source = source;
            this.moovStart = moovStart;
            this.moov = moov;
            this.freeAfterMoov = freeAfterMoov;
            this.moovIsLast = moovIsLast;
        }

        Patch patch(String device) throws PatchException {
            Box patched = Mp4Patcher.patchMoov(moov, device);
            if (patched == null) {
                throw new PatchException("Este video ya tiene la etiqueta.");
            }
            int delta = patched.raw.length - moov.raw.length;
            long moovLen = moov.raw.length;
            long freeLeft = freeAfterMoov - delta;
            if (freeAfterMoov >= 0 && (freeLeft == 0 || freeLeft >= 8)) {
                // Same trick as Mp4Patcher: the new atom takes the free box's space and nothing after it moves.
                if (freeLeft == 0) {
                    return new Patch(source, moovStart, moovLen + freeAfterMoov, patched.raw);
                }
                byte[] freeHeader = new byte[8];
                Mp4Patcher.putU32(freeHeader, 0, freeLeft);
                System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, freeHeader, 4, 4);
                return new Patch(source, moovStart, moovLen + delta + 8, Mp4Patcher.concat(patched.raw, freeHeader));
            }
            if (!moovIsLast) {
                // Fragments locate their samples with offsets outside 'moov' that this doesn't rewrite.
                if (Mp4Patcher.indexOf(Mp4Patcher.parseChildren(moov), "mvex") >= 0) {
                    throw new PatchException(FRAGMENTED);
                }
                shiftChunkOffsets(patched.raw, patched.headerLen, patched.raw.length, moovStart + moovLen, delta);
            }
            return new Patch(source, moovStart, moovLen, patched.raw);
        }
    }

    /** Copies the original file, replacing {@code drop} bytes at offset {@code keep} with {@code replacement}. */
    static final class Patch {
        private final Source source;
        private final long keep;
        private final long drop;
        private final byte[] replacement;

        private Patch(Source source, long keep, long drop, byte[] replacement) {
            this.source = source;
            this.keep = keep;
            this.drop = drop;
            this.replacement = replacement;
        }

        void writeTo(OutputStream out) throws IOException {
            try (InputStream in = source.open()) {
                byte[] buf = new byte[1 << 16];
                long left = keep;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n == -1) {
                        throw new EOFException("El video cambio o esta incompleto.");
                    }
                    out.write(buf, 0, n);
                    left -= n;
                }
                Mp4Patcher.skipFully(in, drop);
                out.write(replacement);
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    static Scan scan(Source source) throws IOException, PatchException {
        List<Box> scanned = new ArrayList<>();
        Box moov = null;
        long moovStart = -1;
        long freeAfterMoov = -1;
        boolean moovIsLast = true;
        try (InputStream in = source.open()) {
            long pos = 0;
            while (true) {
                byte[] hdr = new byte[8];
                int got = Mp4Patcher.readUpTo(in, hdr);
                if (moov != null && got < 8) {
                    moovIsLast = got == 0;
                    break;
                }
                if (got == 0) {
                    break;
                }
                if (got < 8) {
                    throw new PatchException("Archivo MP4 invalido (cabecera incompleta).");
                }
                long size = Mp4Patcher.u32(hdr, 0);
                String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
                byte[] header = hdr;
                if (size == 1) {
                    byte[] ext = new byte[8];
                    Mp4Patcher.readFully(in, ext, 0, 8);
                    size = Mp4Patcher.u64(ext, 0);
                    header = Mp4Patcher.concat(hdr, ext);
                }
                if (moov != null) {
                    // Only the box right after 'moov' matters: free space we may borrow.
                    moovIsLast = false;
                    if (isFree(type) && size >= header.length) {
                        freeAfterMoov = size;
                    }
                    break;
                }
                if (size == 0) {
                    // This box runs to the end of the file, so 'moov' can't come after it.
                    throw new PatchException(NO_MOOV);
                }
                if (size < header.length) {
                    throw new PatchException("Archivo MP4 invalido (caja '" + type + "' de " + size + " bytes).");
                }
                long contentLen = size - header.length;
                boolean isMoov = type.equals("moov");
                if (isMoov && contentLen > Mp4Patcher.MAX_PREFIX_BOX) {
                    throw new PatchException("El indice del video (moov) es demasiado grande (" + size + " bytes).");
                }
                if (isMoov || (!type.equals("mdat") && !isFree(type) && contentLen <= MAX_SCANNED_BOX)) {
                    byte[] raw = new byte[(int) size];
                    System.arraycopy(header, 0, raw, 0, header.length);
                    Mp4Patcher.readFully(in, raw, header.length, (int) contentLen);
                    Box box = new Box(type, header.length, raw);
                    scanned.add(box);
                    if (isMoov) {
                        moov = box;
                        moovStart = pos;
                    }
                } else {
                    Mp4Patcher.skipFully(in, contentLen);
                }
                pos += size;
            }
        }
        if (moov == null) {
            throw new PatchException(NO_MOOV);
        }
        boolean alreadyTagged = Mp4Patcher.patchMoov(moov, DEFAULT_DEVICE) == null;
        return new Scan(Mp4Patcher.findDevice(scanned), alreadyTagged, source, moovStart, moov, freeAfterMoov,
                moovIsLast);
    }

    /** Returns why {@code device} can't be written as the model, or null if it's fine. */
    static String checkDevice(String device) {
        if (device.isEmpty()) {
            return "Escribe el modelo de tus lentes.";
        }
        if (device.length() > MAX_DEVICE_LENGTH) {
            return "El modelo es demasiado largo (maximo " + MAX_DEVICE_LENGTH + " caracteres).";
        }
        for (int i = 0; i < device.length(); i++) {
            char c = device.charAt(i);
            // '&' separates fields in "app=Meta AI&device=..." and readers stop at non-ASCII bytes.
            if (c < 0x20 || c > 0x7E || c == '&') {
                return "Usa solo letras sin acentos, numeros, espacios y signos simples (sin '&').";
            }
        }
        return null;
    }

    // Everything that was at or after 'from' in the original file now sits 'delta' bytes later.
    private static void shiftChunkOffsets(byte[] buf, int start, int end, long from, long delta)
            throws PatchException {
        int pos = start;
        while (end - pos >= 8) {
            long size = Mp4Patcher.u32(buf, pos);
            String type = new String(buf, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int headerLen = 8;
            if (size == 1) {
                if (end - pos < 16) {
                    throw new PatchException("Caja '" + type + "' truncada dentro de 'moov'.");
                }
                size = Mp4Patcher.u64(buf, pos + 8);
                headerLen = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerLen || size > end - pos) {
                throw new PatchException("Caja '" + type + "' con tamano invalido dentro de 'moov'.");
            }
            int boxEnd = pos + (int) size;
            if (type.equals("trak") || type.equals("mdia") || type.equals("minf") || type.equals("stbl")) {
                shiftChunkOffsets(buf, pos + headerLen, boxEnd, from, delta);
            } else if (type.equals("stco") || type.equals("co64")) {
                int width = type.equals("co64") ? 8 : 4;
                int entries = pos + headerLen + 8;
                if (entries > boxEnd) {
                    throw new PatchException("Tabla '" + type + "' invalida.");
                }
                long count = Mp4Patcher.u32(buf, entries - 4);
                if (count > (boxEnd - entries) / width) {
                    throw new PatchException("Tabla '" + type + "' invalida.");
                }
                for (int i = 0; i < count; i++) {
                    int at = entries + i * width;
                    long offset = width == 8 ? Mp4Patcher.u64(buf, at) : Mp4Patcher.u32(buf, at);
                    if (offset < from) {
                        continue;
                    }
                    offset += delta;
                    if (width == 8) {
                        Mp4Patcher.putU64(buf, at, offset);
                    } else if (offset > 0xFFFFFFFFL) {
                        throw new PatchException(TOO_BIG);
                    } else {
                        Mp4Patcher.putU32(buf, at, offset);
                    }
                }
            }
            pos = boxEnd;
        }
    }

    private static boolean isFree(String type) {
        return type.equals("free") || type.equals("skip");
    }
}
