package com.arkus.beacontest;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.Keyboard;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MainActivity extends AppCompatActivity implements RangeNotifier, BeaconConsumer {

    private static final int PERMISSION_REQUEST_LOCATION = 1;
    private static final String ALL_BEACONS_REGION = "AllBeaconsRegion";
    private static final String IBEACON_UID_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
    private static final String IBEACON_GIMBAL1_LAYOUT = "m:0-3=ad7700c6";
    private static final String IBEACON_GIMBAL2_LAYOUT = "m:0-3=2d24bf16";
    private KalmanFilter kalmanFilter;
    private final double PROCESS_NOISE = 0.001;
    private final double MEASUREMENT_NOISE = 2.847171;

    private RecyclerView recyclerView;
    private LogAdapter logAdapter;
    private BeaconManager mBeaconManager;
    private BluetoothAdapter mBluetoothAdapter;
    private Region mRegion;
    private boolean mIsRunning = false;
    private ArrayList<String> beaconLogs = new ArrayList<>();

    private Button mClear, mSave;
    private EditText mProcess, mMeasurement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mClear = findViewById(R.id.clear);
        mSave = findViewById(R.id.save);
        mProcess = findViewById(R.id.process);
        mMeasurement = findViewById(R.id.measurement);

        setupAdapter();
        init();
        requestPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            if (mBeaconManager != null) {
                mBeaconManager.stopMonitoringBeaconsInRegion(mRegion);
                mBeaconManager.removeAllRangeNotifiers();
                mBeaconManager.unbind(this);
            }
        } catch (RemoteException ignored) {}
    }

    private void init() {
        mProcess.setText(PROCESS_NOISE + "");
        mMeasurement.setText(MEASUREMENT_NOISE + "");

        mClear.setOnClickListener(v -> {
            beaconLogs.clear();
            logAdapter.notifyDataSetChanged();
        });

        mSave.setOnClickListener(v -> {
            kalmanFilter.setMeasurementNoise(Double.parseDouble(mMeasurement.getText().toString()));
            kalmanFilter.setProcessNoise(Double.parseDouble(mProcess.getText().toString()));
            Toast.makeText(this, "Valued Saved", Toast.LENGTH_SHORT).show();
            hideKeyboard();
        });
    }

    private void setupAdapter() {
        recyclerView = (RecyclerView) findViewById(R.id.logs);
        logAdapter = new LogAdapter(this, beaconLogs);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(logAdapter);
    }

    private void startListening() {
        if(!mIsRunning) {
            kalmanFilter = new KalmanFilter(PROCESS_NOISE, MEASUREMENT_NOISE);
            BluetoothAdapter bluetoothAdapter = getDefaultAdapter();
            if(bluetoothAdapter.enable() && isLocationEnabled()) {
                BeaconManager beaconManager = getBeaconManager();
                List<BeaconParser> beaconParsers = beaconManager.getBeaconParsers();
                beaconParsers.add(new BeaconParser().setBeaconLayout(IBEACON_UID_LAYOUT));

                long scanPeriod = 100;
                beaconManager.setForegroundBetweenScanPeriod(scanPeriod);
                beaconManager.bind(this);

                mIsRunning = true;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        for (int grantResult : grantResults) {
            if (!hasPermission(grantResult)) {
                Toast.makeText(this, "Sin permiso", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (!isLocationEnabled()) {
            turnOnGps();
        }

        startListening();
    }

    public boolean hasPermission(int permission) {
        return permission == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (!isLocationEnabled()) {
                turnOnGps();
            }

            startListening();
        } else {
            requestPermissionDialog(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_LOCATION);
        }
    }

    private void requestPermissionDialog(String[] permissions, int requestCode) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Se necesitan permisos");
        builder.setMessage("Aceptalo porfa");
        builder.setPositiveButton(android.R.string.ok, (dialog, id) -> requestPermissions(permissions, requestCode));
        builder.create().show();
    }

    private void turnOnGps() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enciende el GPS");
        builder.setMessage("Porfa prendelo! xD");
        builder.setPositiveButton("Ok", (dialog, id) -> openLocationSettings());
        builder.create().show();
    }

    public void openLocationSettings() {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(intent);
    }

    private BluetoothAdapter getDefaultAdapter() {
        if(mBluetoothAdapter == null) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        return mBluetoothAdapter;
    }

    private Region getBeaconRegion() {
        if(mRegion == null) {
            ArrayList<Identifier> identifiers = new ArrayList<>();
            identifiers.add(null);
            mRegion = new Region(ALL_BEACONS_REGION, identifiers);
        }

        return  mRegion;
    }

    private BeaconManager getBeaconManager() {
        if(mBeaconManager == null) {
            mBeaconManager = BeaconManager.getInstanceForApplication(this);
        }

        return mBeaconManager;
    }

    @Override
    public void onBeaconServiceConnect() {
        Region region = getBeaconRegion();

        try {
            BeaconManager beaconManager = getBeaconManager();
            beaconManager.startRangingBeaconsInRegion(region);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        mBeaconManager.addRangeNotifier(this);
    }

    @Override
    public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
        for (Beacon beacon: beacons) {
            long rssiFiltered = Math.round(kalmanFilter.filter(beacon.getRssi()));
            int rssi = beacon.getRssi();
            String log = "Name: " + beacon.getBluetoothName() + " --- Current RSSI: " + rssi + " --- filtered RSSI: " + rssiFiltered
                    + " --- distance: " +  String.format("%,.2f", Double.parseDouble(beacon.getDistance() + ""));
            beaconLogs.add(log);
        }

        logAdapter.notifyDataSetChanged();
    }

    public boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        assert locationManager != null;
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}