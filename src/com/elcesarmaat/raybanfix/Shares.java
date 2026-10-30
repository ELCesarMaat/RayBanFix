package com.elcesarmaat.raybanfix;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class Shares {

    private Shares() {
    }

    static Intent chooser(Context context, Uri video) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, video);
        send.setClipData(ClipData.newRawUri("", video));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, context.getString(R.string.share_title));
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{new ComponentName(context, FixActivity.class)});
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return chooser;
    }

    static Intent player(Uri video) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(video, "video/mp4");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view;
    }
}
