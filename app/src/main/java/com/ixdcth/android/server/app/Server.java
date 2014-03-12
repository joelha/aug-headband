package com.ixdcth.android.server.app;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.UUID;

public class Server extends Activity implements GooglePlayServicesClient, ConnectionCallbacks, OnConnectionFailedListener, LocationListener, SensorEventListener {

    private TextView serverStatus;
    private TextView output;

    // DEFAULT IP
    public static String SERVERIP = "10.0.2.15";
    // DESIGNATE A PORT
    public static final int SERVERPORT = 8080;

    private Handler handler = new Handler();
    private ServerSocket serverSocket;

    private boolean updatesRequested = true;

    private LocationClient locationClient;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private LocationManager locationManager;

    private Location clientPos = null;
    private double clientLat;
    private double clientLng;

    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream btOutStream = null;

    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static String address = "00:13:EF:01:10:EF";

    private float[] gravity;
    private float[] magnetic;
    private float lastCompassBearing;
    private float distanceToClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);

        Log.d("Startup", "Initializing");
        serverStatus = (TextView) findViewById(R.id.connectionTextView);
        output = (TextView) findViewById(R.id.output);

        SERVERIP = getLocalIpAddress();

        initSensors();

        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(1000);
        locationRequest.setSmallestDisplacement(1);
        locationClient = new LocationClient(this, this, this);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        checkBTState();

        Thread fst = new Thread(new ServerThread());
        fst.start();
    }

    private void initSensors() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        Sensor sensorMagneticField = sensorManager
                .getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Initialize the gravity sensor
        if (sensorGravity != null) {
            Log.i("SENSOR", "Gravity sensor available. (TYPE_GRAVITY)");
            sensorManager.registerListener(sensorEventListener,
                    sensorGravity, SensorManager.SENSOR_DELAY_GAME);
        } else {
            Log.i("SENSOR", "Gravity sensor unavailable. (TYPE_GRAVITY)");
        }

        //Initialize the magnetic field sensor
        if (sensorMagneticField != null) {
            Log.i("SENSOR", "Magnetic field sensor available. (TYPE_MAGNETIC_FIELD)");
            sensorManager.registerListener(sensorEventListener,
                    sensorMagneticField, SensorManager.SENSOR_DELAY_GAME);
        } else {
            Log.i("SENSOR",
                    "Magnetic field sensor unavailable. (TYPE_MAGNETIC_FIELD)");
        }
    }

    private SensorEventListener sensorEventListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        private void compensateDeviceOrientation(float[] rotationMatrix) {
            // http://android-developers.blogspot.de/2010/09/one-screen-turn-deserves-another.html
            float[] remappedRotationMatrix = new float[9];
            switch (getWindowManager().getDefaultDisplay()
                    .getRotation()) {
                case Surface.ROTATION_0:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_X, SensorManager.AXIS_Y,
                            remappedRotationMatrix);
                    break;
                case Surface.ROTATION_90:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_Y,
                            SensorManager.AXIS_MINUS_X,
                            remappedRotationMatrix);
                    break;
                case Surface.ROTATION_180:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_MINUS_X,
                            SensorManager.AXIS_MINUS_Y,
                            remappedRotationMatrix);
                    break;
                case Surface.ROTATION_270:
                    SensorManager.remapCoordinateSystem(rotationMatrix,
                            SensorManager.AXIS_MINUS_Y,
                            SensorManager.AXIS_X, remappedRotationMatrix);
                    break;
            }

            // Calculate Orientation
            float results[] = new float[3];
            SensorManager.getOrientation(remappedRotationMatrix,
                    results);

            // Get measured value
            float currentMeasuredBearing = (float) (results[0] * 180 / Math.PI);
            if (currentMeasuredBearing < 0) {
                currentMeasuredBearing += 360;
            }

            // Smooth values using a 'Low Pass Filter'
               /*     currentMeasuredBearing += SMOOTHING_FACTOR_COMPASS
                            * (currentMeasuredBearing - lastMeasuredBearing);   */

            //Update variables for next use (Required for Low Pass Filter)
            lastCompassBearing = currentMeasuredBearing;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                gravity = event.values.clone();
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                magnetic = event.values.clone();
            }

            if (gravity != null && magnetic != null) {
            /* Create rotation Matrix */
                float[] rotationMatrix = new float[9];
                if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {

                    compensateDeviceOrientation(rotationMatrix);
                }
            }
        }
    };

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if(Build.VERSION.SDK_INT >= 10) {
            try {
               final Method m = device.getClass().getMethod("CreateInsecureRfcommSocketToServiceRecord", new Class[] {UUID.class});
               return (BluetoothSocket) m.invoke(device, MY_UUID);
            } catch (Exception e) {
                Log.e("ERROR", "Could not create secure rf....");
            }
        }
        return device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    @Override
    public void onResume() {
        super.onResume();

        Log.d("TAG", "...onResume - try connect...");
        // Set up a pointer to the remote node using it's address.
        BluetoothDevice device = btAdapter.getRemoteDevice(address);

        // Two things are needed to make a connection:
        //   A MAC address, which we got above.
        //   A Service ID or UUID.  In this case we are using the
        //     UUID for SPP.

        try {
            btSocket = createBluetoothSocket(device);
        } catch (IOException e1) {
            errorExit("Fatal Error", "In onResume() and socket create failed: " + e1.getMessage() + ".");
        }

        // Discovery is resource intensive.  Make sure it isn't going on
        // when you attempt to connect and pass your message.
        btAdapter.cancelDiscovery();

        // Establish the connection.  This will block until it connects.
        Log.d("TAG", "...Connecting...");
        try {
            btSocket.connect();
            Log.d("TAG", "...Connection ok...");
        } catch (IOException e) {
            try {
                btSocket.close();
            } catch (IOException e2) {
                errorExit("Fatal Error", "In onResume() and unable to close socket during connection failure" + e2.getMessage() + ".");
            }
        }

        // Create a data stream so we can talk to server.
        Log.d("TAG", "...Create Socket...");

        try {
            btOutStream = btSocket.getOutputStream();
        } catch (IOException e) {
            errorExit("Fatal Error", "In onResume() and output stream creation failed:" + e.getMessage() + ".");
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // to stop the listener and save battery
        //dont think we need this..
        sensorManager.unregisterListener(this);

        Log.d("TAG", "...In onPause()...");
        if (btOutStream != null) {
            try {
                btOutStream.flush();
            } catch (IOException e) {
                errorExit("Fatal Error", "In onPause() and failed to flush output stream: " + e.getMessage() + ".");
            }
        }

        try     {
            btSocket.close();
        } catch (IOException e2) {
            errorExit("Fatal Error", "In onPause() and failed to close socket." + e2.getMessage() + ".");
        }
    }

    private void checkBTState() {
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        if(btAdapter==null) {
            errorExit("Fatal Error", "Bluetooth not support");
        } else {
            if (btAdapter.isEnabled()) {
                Log.d("TAG", "...Bluetooth ON...");
            } else {
                //Prompt user to turn on Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }
    }

    private void errorExit(String title, String message){
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }

    private double bearing(Location server, Location client) {
        double lat = client.getLatitude() - server.getLatitude();
        double lng = client.getLongitude() - server.getLongitude();
        double angle = StrictMath.atan2(lng, lat);
        angle = StrictMath.toDegrees(angle);

        if(angle < 0) {
            angle += 360;
        }
        return angle;
    }

    private double[] getAngleToClient() {
        Location serverPos = locationClient.getLastLocation();
            double dummy[] = {1};
        if(serverPos == null) {
            return dummy;
        }

        clientPos = new Location(serverPos);
        clientPos.setLongitude(clientLng);
        clientPos.setLatitude(clientLat);

        distanceToClient = clientPos.distanceTo(serverPos);
        double angle =  bearing(serverPos, clientPos);
        double angle2 = angle - lastCompassBearing;

        if(angle2 < 0) {
            angle2 += 360;
        }

        double[] field = {angle,angle2};
        return field;
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d("ERROR", "Connected to google!");
        // If already requested, start periodic updates
        if (updatesRequested) {
            locationClient.requestLocationUpdates(locationRequest, this);
        }
    }

    //Called when the Activity becomes visible.
    @Override
    protected void onStart() {
        super.onStart();
        // Connect the client.
        Log.d("ONSTART", "Connect to google");
        locationClient.connect();
    }

    @Override
    protected void onStop() {
        Log.d("ONSTOP", "Disconnect from google");
        locationClient.disconnect();

        super.onStop();
        try {
            // MAKE SURE YOU CLOSE THE SOCKET UPON EXITING
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Disconnecting the client invalidates it.
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.d("ERROR", "Updated Location: " + Double.toString(location.getLatitude()) + "," +
                Double.toString(location.getLongitude()));
    }

    private void sendData(String message) {
        byte[] msgBuffer = message.getBytes();

        Log.d("SEND DATA", "sending data maybe");
        try {
            btOutStream.write(msgBuffer);
        } catch (IOException e) {
            String msg = "In onResume() and an exception occurred during write: " + e.getMessage();
            if (address.equals("00:00:00:00:00:00"))
                msg = msg + ".\n\nUpdate your server address from 00:00:00:00:00:00 to the correct address on line 35 in the java code";
            msg = msg +  ".\n\nCheck that the SPP UUID: " + MY_UUID.toString() + " exists on server.\n\n";

            errorExit("Fatal Error", msg);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // get the angle around the z-axis rotated
        float degree = Math.round(event.values[0]);
        Log.d("COMPASS", " degrees: " + degree);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public class ServerThread implements Runnable {

        public void run() {
            try {
                if (SERVERIP != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            serverStatus.setText("Listening on IP: " + SERVERIP);
                        }
                    });
                    serverSocket = new ServerSocket(SERVERPORT);
                    while (true) {
                        // LISTEN FOR INCOMING CLIENTS
                        Socket client = serverSocket.accept();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                serverStatus.setText("Connected.");
                                Log.d("Connection","Phone connected");
                            }
                        });

                        try {
                            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            String line = null;
                            Log.d("Try","Send");
                            while ((line = in.readLine()) != null) {
                                Log.d("ServerActivity", line);
                                //the received message from client
                                String lineField[] = line.split(":");
                                final String lat = lineField[0];
                                final String lng = lineField[1];

                                clientLat = Double.parseDouble(lat);
                                clientLng = Double.parseDouble(lng);
                               // final double deg = getAngleToClient();

                                final double[] degField = getAngleToClient();

                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        //display message in the textview "output"
                                        output.setText("Deg to client: " + Double.toString(degField[1]) + " SC deg: " + degField[0]);
                                        //send data to arduino
                                        sendData(Double.toString(degField[1]) + ";" + Float.toString(distanceToClient) + ":");
                                    }
                                });
                            }
                            break;
                        } catch (Exception e) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    serverStatus.setText("Oops. Connection interrupted. Please reconnect your phones.");
                                }
                            });
                            e.printStackTrace();
                        }
                    }
                } else {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            serverStatus.setText("Couldn't detect internet connection.");
                        }
                    });
                }
            } catch (Exception e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        serverStatus.setText("Error");
                    }
                });
                e.printStackTrace();
            }
        }
    }

    // GETS THE IP ADDRESS OF YOUR PHONE'S NETWORK
    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    @Override
    public void connect() {

    }

    @Override
    public void disconnect() {

    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public void onDisconnected() {
        Log.d("ERROR", "Disconnected from google!");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void registerConnectionCallbacks(ConnectionCallbacks connectionCallbacks) {

    }

    @Override
    public boolean isConnectionCallbacksRegistered(ConnectionCallbacks connectionCallbacks) {
        return false;
    }

    @Override
    public void unregisterConnectionCallbacks(ConnectionCallbacks connectionCallbacks) {

    }

    @Override
    public void registerConnectionFailedListener(OnConnectionFailedListener onConnectionFailedListener) {

    }

    @Override
    public boolean isConnectionFailedListenerRegistered(OnConnectionFailedListener onConnectionFailedListener) {
        return false;
    }

    @Override
    public void unregisterConnectionFailedListener(OnConnectionFailedListener onConnectionFailedListener) {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.server, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
