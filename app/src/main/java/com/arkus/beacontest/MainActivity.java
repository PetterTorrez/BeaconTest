package com.arkus.beacontest;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Identifier;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements RangeNotifier, BeaconConsumer {

    private static final int PERMISSION_REQUEST_LOCATION = 1;
    private static final String ALL_BEACONS_REGION = "AllBeaconsRegion";
    private static final String IBEACON_UID_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24";
    private static final String IBEACON_GIMBAL1_LAYOUT = "m:0-3=ad7700c6";
    private static final String IBEACON_GIMBAL2_LAYOUT = "m:0-3=2d24bf16";
    private KalmanFilter kalmanFilter;
    private final double PROCESS_NOISE = 0.33;
    private final double MEASUREMENT_NOISE = 2.847171;
    /*
     *   FEASY BEACON WORKS ~-66DB at 1 m (beacon at 100ms and 0dB power)
     */
    private final double MINIMAL_RISK_LOSS = 50;
    private final int SAMPLES_PER_HISTOGRAM = 10;
    private final double MINIMAL_RISK_DISTANCE =1;
    private int sampleCounter=0;
    private Map<Integer, Integer> histogram = new HashMap<>();
    /*
     * Enviroment loss constant depends on the application
     * 2: For Free space
     * 2.7 - 3.5 for Urban Areas
     * 1.6 to 1.8 inside buildings (line of sight)
     * 4 to 6 Obstructed building
     * 2 to 3 Office (with many other devices operating at the same frequency)
     */
    private final double ENVIROMENT_LOSS_CONSTANT = 2;


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
            //kalmanFilter.setMeasurementNoise(Double.parseDouble(mMeasurement.getText().toString()));
            //kalmanFilter.setProcessNoise(Double.parseDouble(mProcess.getText().toString()));
            //FileOutputStream stream = new FileOutputStream(file);
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
            LocalDateTime now = LocalDateTime.now();
            String d = dtf.format(now)+".csv";
            File path = this.getBaseContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            File file = new File(path,d);
            FileOutputStream stream = null;
            try {
                stream = new FileOutputStream(file);
                for(int li=0; li != beaconLogs.size();li++) {
                    try {
                        stream.write(beaconLogs.get(li).getBytes());
                        stream.write('\n');
                    } catch (IOException e) {
                    }
                }
                stream.close();
            }catch(IOException e)
            {

            }
            finally {

            }
            Toast.makeText(this, "Saved as"+file.getAbsolutePath(), Toast.LENGTH_SHORT).show();
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

                long scanPeriod = 0;
                beaconManager.setForegroundBetweenScanPeriod(scanPeriod);
                beaconManager.setForegroundScanPeriod(41); // Default, so this line not needed
                beaconManager.setForegroundBetweenScanPeriod(0); // Default, so this line not needed
                beaconManager.setBackgroundScanPeriod(41);
                beaconManager.setBackgroundBetweenScanPeriod(1);
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

            //long rssiFiltered = Math.round(kalmanFilter.filter(beacon.getRssi()));
            int rssi = beacon.getRssi();
            sampleCounter++;
            Integer rssiBin = histogram.get(rssi);
            if (rssiBin== null)
                histogram.put(rssi,1);
            else
                histogram.put(rssi,rssiBin+1);

            if (sampleCounter != SAMPLES_PER_HISTOGRAM) return;
            int maxCount = 0;

            for (Map.Entry<Integer, Integer> bin : histogram.entrySet()) {
                if (bin.getValue() > maxCount) {
                    maxCount = bin.getValue();
                    rssi=bin.getKey();
                }
            }
            sampleCounter=0;
            histogram.clear();
            //TODO include Xg which is the stochastic noise variable
            double distance = MINIMAL_RISK_DISTANCE *
                    Math.pow(10, (-MINIMAL_RISK_LOSS- rssi)/(10*ENVIROMENT_LOSS_CONSTANT));
            String log = beacon.getBluetoothAddress() + "," + rssi + "," + distance;

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