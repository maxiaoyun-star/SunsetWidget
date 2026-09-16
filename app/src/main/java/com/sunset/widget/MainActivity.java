package com.sunset.widget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;

    private ListView lvCities;
    private EditText etCity;
    private RadioButton rbEc;
    private RadioButton rbGfs;
    private TextView tvTip;

    private SharedPreferences prefs;
    private ArrayList<String> cityList;
    private ArrayAdapter<String> adapter;

    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationListener activeListener;
    private boolean locating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lvCities = findViewById(R.id.lv_cities);
        etCity = findViewById(R.id.et_city);
        rbEc = findViewById(R.id.rb_ec);
        rbGfs = findViewById(R.id.rb_gfs);
        tvTip = findViewById(R.id.tv_tip);
        Button btnAdd = findViewById(R.id.btn_add);
        Button btnGps = findViewById(R.id.btn_gps);
        Button btnSave = findViewById(R.id.btn_save);

        prefs = getSharedPreferences(SunsetWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        loadCityList();
        setupListeners(btnAdd, btnGps, btnSave);

        // 回填当前数据源
        String source = prefs.getString(SunsetWidgetProvider.KEY_SOURCE, SunsetWidgetProvider.DEFAULT_SOURCE);
        if ("EC".equals(source)) {
            rbEc.setChecked(true);
        } else {
            rbGfs.setChecked(true);
        }
    }

    private void setupListeners(Button btnAdd, Button btnGps, Button btnSave) {
        lvCities.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < cityList.size()) {
                    String selected = cityList.get(position);
                    prefs.edit().putString(SunsetWidgetProvider.KEY_CURRENT_CITY, selected).apply();
                    refreshCityList();
                    tvTip.setText("当前城市：" + selected);
                }
            }
        });

        lvCities.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < cityList.size()) {
                    String toRemove = cityList.get(position);
                    if (cityList.size() <= 1) {
                        tvTip.setText("至少保留一个城市");
                        return true;
                    }
                    cityList.remove(position);
                    String current = prefs.getString(SunsetWidgetProvider.KEY_CURRENT_CITY, SunsetWidgetProvider.DEFAULT_CITY);
                    if (toRemove.equals(current)) {
                        prefs.edit().putString(SunsetWidgetProvider.KEY_CURRENT_CITY, cityList.get(0)).apply();
                    }
                    saveCityList();
                    refreshCityList();
                    tvTip.setText("已删除：" + toRemove);
                }
                return true;
            }
        });

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = etCity.getText().toString().trim();
                if (input.isEmpty()) {
                    tvTip.setText("请输入城市名");
                    return;
                }
                String normalized = normalizeCity(input);
                if (cityList.contains(normalized)) {
                    tvTip.setText(normalized + " 已在列表中");
                    return;
                }
                cityList.add(normalized);
                saveCityList();
                prefs.edit().putString(SunsetWidgetProvider.KEY_CURRENT_CITY, normalized).apply();
                refreshCityList();
                etCity.setText("");
                tvTip.setText("已添加：" + normalized);
            }
        });

        btnGps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGps();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String source = rbGfs.isChecked() ? "GFS" : "EC";
                prefs.edit().putString(SunsetWidgetProvider.KEY_SOURCE, source).apply();
                notifyWidgetRefresh();
                tvTip.setText("已保存，返回桌面查看小组件");
            }
        });
    }

    private void loadCityList() {
        String json = prefs.getString(SunsetWidgetProvider.KEY_CITY_LIST, null);
        cityList = new ArrayList<>();
        if (json == null) {
            cityList.addAll(Arrays.asList("长春", "张家口"));
            if (!prefs.contains(SunsetWidgetProvider.KEY_CURRENT_CITY)) {
                prefs.edit().putString(SunsetWidgetProvider.KEY_CURRENT_CITY, "长春").apply();
            }
            saveCityList();
        } else {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    cityList.add(arr.optString(i, ""));
                }
            } catch (Exception e) {
                cityList.addAll(Arrays.asList("长春", "张家口"));
            }
        }
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lvCities.setAdapter(adapter);
        refreshCityList();
    }

    private void saveCityList() {
        JSONArray arr = new JSONArray();
        for (String c : cityList) {
            arr.put(c);
        }
        prefs.edit().putString(SunsetWidgetProvider.KEY_CITY_LIST, arr.toString()).apply();
    }

    private void refreshCityList() {
        String current = prefs.getString(SunsetWidgetProvider.KEY_CURRENT_CITY, SunsetWidgetProvider.DEFAULT_CITY);
        adapter.clear();
        for (String c : cityList) {
            adapter.add(c.equals(current) ? "● " + c : c);
        }
        adapter.notifyDataSetChanged();
    }

    private String normalizeCity(String input) {
        String s = input.trim();
        if (s.endsWith("市") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ---------------- GPS 定位 ----------------

    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
            return;
        }
        doLocate();
    }

    private void doLocate() {
        Location cached = getCachedLocation();
        if (cached != null) {
            onLocated(cached);
            return;
        }

        tvTip.setText("正在定位…请稍候");
        locating = true;
        activeListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                onLocated(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0,
                    activeListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            tvTip.setText("定位权限被拒绝");
            locating = false;
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (locating) {
                    locating = false;
                    if (activeListener != null) {
                        locationManager.removeUpdates(activeListener);
                    }
                    tvTip.setText("定位超时，请确认已开启手机定位服务");
                }
            }
        }, 15000);
    }

    private Location getCachedLocation() {
        Location loc = null;
        try {
            loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
        }
        if (loc == null) {
            try {
                loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
            }
        }
        return loc;
    }

    private void onLocated(Location location) {
        locating = false;
        if (activeListener != null) {
            locationManager.removeUpdates(activeListener);
            activeListener = null;
        }
        String city = CityDatabase.findNearestCity(location.getLatitude(), location.getLongitude());
        if (!cityList.contains(city)) {
            cityList.add(city);
            saveCityList();
        }
        prefs.edit().putString(SunsetWidgetProvider.KEY_CURRENT_CITY, city).apply();
        refreshCityList();
        tvTip.setText("已定位到：" + city);
        notifyWidgetRefresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doLocate();
            } else {
                tvTip.setText("需要定位权限才能 GPS 定位");
            }
        }
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
