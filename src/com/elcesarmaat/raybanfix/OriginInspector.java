package com.elcesarmaat.raybanfix;

import com.elcesarmaat.raybanfix.Mp4Patcher.Box;
import com.elcesarmaat.raybanfix.Mp4Patcher.PatchException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

// Tells apart videos that still carry the glasses' own metadata from ones that only have the attribution atom this
// app writes (inside moov/udta). Only the header boxes are read; mdat is skipped.
final class OriginInspector {

    enum Origin { GLASSES, TAG_ONLY, UNTAGGED }

    private static final long MAX_SCANNED_BOX = 1024 * 1024;

    private OriginInspector() {
    }

    static Origin inspect(InputStream in) throws IOException, PatchException {
        boolean original = false;
        boolean tagged = false;
        while (true) {
            byte[] hdr = new byte[8];
            if (Mp4Patcher.readUpTo(in, hdr) < 8) {
                break;
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
            if (size == 0 || size < header.length) {
                break;
            }
            long contentLen = size - header.length;
            boolean isMoov = type.equals("moov");
            boolean read = isMoov ? contentLen <= Mp4Patcher.MAX_PREFIX_BOX
                    : !type.equals("mdat") && contentLen <= MAX_SCANNED_BOX;
            if (!read) {
                Mp4Patcher.skipFully(in, contentLen);
                continue;
            }
            byte[] raw = new byte[(int) size];
            System.arraycopy(header, 0, raw, 0, header.length);
            Mp4Patcher.readFully(in, raw, header.length, (int) contentLen);
            Box box = new Box(type, header.length, raw);
            if (!isMoov) {
                original |= hasDevice(box);
                continue;
            }
            for (Box child : Mp4Patcher.parseChildren(box)) {
                if (!child.type.equals("udta")) {
                    original |= hasDevice(child);
                    continue;
                }
                for (Box item : Mp4Patcher.parseChildren(child)) {
                    if (item.type.equals("meta") && Mp4Patcher.isAttributionMeta(item)) {
                        tagged = true;
                    } else {
                        original |= hasDevice(item);
                    }
                }
            }
            break;
        }
        if (original) {
            return Origin.GLASSES;
        }
        return tagged ? Origin.TAG_ONLY : Origin.UNTAGGED;
    }

    private static boolean hasDevice(Box box) {
        return Mp4Patcher.findDevice(Collections.singletonList(box)) != null;
    }
}
