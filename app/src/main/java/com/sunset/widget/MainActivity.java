package com.sunset.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText etCity = findViewById(R.id.et_city);
        final RadioButton rbEc = findViewById(R.id.rb_ec);
        final RadioButton rbGfs = findViewById(R.id.rb_gfs);
        Button btnSave = findViewById(R.id.btn_save);
        final TextView tvTip = findViewById(R.id.tv_tip);

        SharedPreferences prefs = getSharedPreferences(SunsetWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        String city = prefs.getString(SunsetWidgetProvider.KEY_CITY, "上海");
        String source = prefs.getString(SunsetWidgetProvider.KEY_SOURCE, "EC");
        etCity.setText(city);
        if ("GFS".equals(source)) {
            rbGfs.setChecked(true);
        } else {
            rbEc.setChecked(true);
        }

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newCity = etCity.getText().toString().trim();
                if (newCity.isEmpty()) {
                    tvTip.setText("城市不能为空");
                    return;
                }
                String newSource = rbGfs.isChecked() ? "GFS" : "EC";

                getSharedPreferences(SunsetWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(SunsetWidgetProvider.KEY_CITY, newCity)
                        .putString(SunsetWidgetProvider.KEY_SOURCE, newSource)
                        .apply();

                notifyWidgetRefresh();

                tvTip.setText("已保存：" + newCity + " · " + newSource + "，返回桌面查看");
            }
        });
    }

    private void notifyWidgetRefresh() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        ComponentName cn = new ComponentName(this, SunsetWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids.length == 0) {
            return;
        }
        Intent update = new Intent(this, SunsetWidgetProvider.class);
        update.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        update.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        sendBroadcast(update);
    }
}
