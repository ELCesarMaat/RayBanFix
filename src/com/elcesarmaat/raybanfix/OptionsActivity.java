package com.elcesarmaat.raybanfix;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;

public class OptionsActivity extends Activity {

    private static final int REQUEST_MEDIA_ACCESS = 1;

    private Switch saveSwitch;
    private TextView saveSummary;
    private TextView accessSummary;
    private TextView accessState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_options);
        Ui.applySystemBarInsets(findViewById(R.id.root));
        saveSwitch = findViewById(R.id.save_switch);
        saveSummary = findViewById(R.id.save_summary);
        accessSummary = findViewById(R.id.access_summary);
        accessState = findViewById(R.id.access_state);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.access_row).setOnClickListener(v -> {
            if (MediaAccess.level(this) != MediaAccess.Level.FULL) {
                MediaAccess.request(this, REQUEST_MEDIA_ACCESS);
            }
        });
        findViewById(R.id.save_row).setOnClickListener(v -> {
            Settings.setSaveFixed(this, !Settings.saveFixed(this));
            bind();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        bind();
    }

    private void bind() {
        boolean save = Settings.saveFixed(this);
        saveSwitch.setChecked(save);
        saveSummary.setText(save ? R.string.option_save_on : R.string.option_save_off);

        MediaAccess.Level level = MediaAccess.level(this);
        int summary;
        int state;
        int background;
        int color;
        if (level == MediaAccess.Level.FULL) {
            summary = R.string.option_access_full;
            state = R.string.access_state_full;
            background = R.drawable.bg_chip_ok;
            color = R.color.rb_on_ok_container;
        } else if (level == MediaAccess.Level.PARTIAL) {
            summary = R.string.option_access_partial;
            state = R.string.access_state_partial;
            background = R.drawable.bg_chip_warn;
            color = R.color.rb_on_warn_container;
        } else {
            summary = R.string.option_access_none;
            state = R.string.access_state_none;
            background = R.drawable.bg_chip_warn;
            color = R.color.rb_on_warn_container;
        }
        accessSummary.setText(summary);
        accessState.setText(state);
        accessState.setBackgroundResource(background);
        accessState.setTextColor(getColor(color));
    }
}
