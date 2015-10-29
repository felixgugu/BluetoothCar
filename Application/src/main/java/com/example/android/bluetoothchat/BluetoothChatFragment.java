package com.example.android.bluetoothchat;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Set;


public class BluetoothChatFragment extends Fragment {

    private static final String TAG = "BluetoothChatFragment";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    // Layout Views
    private Button mUpButton;
    private Button mRightButton;
    private Button mLeftButton;
    private Button mStopButton;
    private EditText mMotoSpeedText;
    private ImageView mImageView;//IP Camera只正一張張的圖

    private ImageButton mBluetoothButton;
    private ImageButton mCameraButton;


    private static final String VIDEO_URL = "http://192.168.43.104:8080/stream/live.jpg";

    private int mMotoSpeed = 200;

    /**
     * 已連線設備名稱
     */
    private String mConnectedDeviceName = null;

    /**
     * 藍芽設備
     */
    private BluetoothAdapter mBluetoothAdapter = null;
    private String mBluetoothDeviceName = "HC-06";

    /**
     * 藍芽服務
     */
    private BluetoothService mChatService = null;


    //最後送出的指令
    private String lastCmd;

    private boolean mStartRunning = false;//是否正在移動(前進、後退)


    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 取得藍芽設備
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // 沒有藍芽
        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity, "您的手機不支援藍芽", Toast.LENGTH_LONG).show();
            activity.finish();
        }

        mHandler = new MyHandler(this);
    }


    @Override
    public void onStart() {
        super.onStart();
        // 藍芽未開啟，請使用者打開
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        } else if (mChatService == null) {
            setupChat();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
    }


    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {

        mUpButton = (Button) view.findViewById(R.id.button_up);
        mLeftButton = (Button) view.findViewById(R.id.button_left);
        mRightButton = (Button) view.findViewById(R.id.button_right);
        mStopButton = (Button) view.findViewById(R.id.button_stop);
        mMotoSpeedText = (EditText) view.findViewById(R.id.moto_speed);
        mImageView = (ImageView) view.findViewById(R.id.imageView);
        mBluetoothButton = (ImageButton) view.findViewById(R.id.button_bluetooth);
        mCameraButton = (ImageButton) view.findViewById(R.id.button_camera);
    }

    int error = 0;

    Thread videoThread;
    Runnable VideoRunnable = new Runnable() {
        @Override
        public void run() {

            error = 0;

            do {

                if (error > 300) {
                    break;
                }

                try {
                    URL url = new URL(VIDEO_URL);
                    InputStream is = url.openStream();

                    byte[] bytes = new byte[4096];
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    int n;
                    while ((n = is.read(bytes)) != -1) {
                        os.write(bytes, 0, n);
                    }

                    Message msg = new Message();
                    msg.what = Constants.MESSAGE_VIDEO;
                    msg.obj = os;
                    mHandler.sendMessage(msg);

                    Thread.sleep(100);


                } catch (Exception e) {
                    //e.printStackTrace();
                    //Log.e("test", "無法連線影像", e);
                    error = error + 1;
                }

            } while (true);


            Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
            Bundle bundle = new Bundle();
            bundle.putString(Constants.TOAST, "無法連線影像!!");
            msg.setData(bundle);
            mHandler.sendMessage(msg);

            videoThread = null;

        }
    };

    /**
     * Set up the UI and background operations for chat.
     */
    private void setupChat() {
        Log.d(TAG, "setupChat()");


        //範圍
        int moveButtonHeight = UnitUtil.dp2px(getContext(), 300);
        final int centerVal = moveButtonHeight / 2; //小於此值等於前進，大於反之

        Log.d("test", "moveButtonHeight:" + moveButtonHeight);
        Log.d("test", "centerVal:" + centerVal);

        //控制前進後退
        mUpButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mStartRunning = true;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    mStartRunning = false;
                    sendCommand("x,0");//停車
                }

                if (mStartRunning && event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (event.getY() < centerVal) {
                        sendCommand("f," + mMotoSpeed);
                    } else {
                        sendCommand("b," + mMotoSpeed);
                    }
                }

                return false;
            }
        });


        //右轉
        mRightButton.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendCommand("r,0");
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    sendCommand("a,90");//轉回中間
                }

                return false;
            }
        });

        //左轉
        mLeftButton.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {

                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    sendCommand("l,0");
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    sendCommand("a,90");//轉回中間
                }

                return false;
            }
        });

        //備用停車(一般來說，手放開就停了，這個算緊急用的
        mStopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendCommand("x,0");//停車
            }
        });

        // Initialize the BluetoothService to perform bluetooth connections
        mChatService = new BluetoothService(getActivity(), mHandler);

        //
        mMotoSpeedText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                try {
                    int speed = Integer.parseInt(v.getText().toString());
                    Log.d("test", "speed:" + speed);
                    if (speed > 0 && speed < 250) {
                        mMotoSpeed = speed;
                    } else {
                        mMotoSpeed = 200;
                    }
                } catch (Exception e) {
                    mMotoSpeed = 200;
                    Log.e("test", "error", e);
                }

                mMotoSpeedText.setText(mMotoSpeed + "");

                return false;
            }
        });


        // 藍芽
        mBluetoothButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                //startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);

                //改成直接連

                //若已綁定，先解除，(不知為何綁定過的，難連上)
                try {

                    Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
                    for (BluetoothDevice bluetoothDevice : pairedDevices) {
                        if (mBluetoothDeviceName.equals(bluetoothDevice.getName())) {
                            Method method = bluetoothDevice.getClass().getMethod("removeBond");
                            method.invoke(bluetoothDevice);

                            Log.d("test", "已解除 " + bluetoothDevice.getName() + " 綁定");
                        }
                    }

                    if (mBluetoothAdapter.isDiscovering()) {
                        mBluetoothAdapter.cancelDiscovery();
                    }

                    IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                    getActivity().registerReceiver(mReceiver, filter);

                    IntentFilter filter2 = new IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST");
                    getActivity().registerReceiver(pairingRequest, filter2);

                    Log.d("test", "bluetooth start discovery");
                    mBluetoothAdapter.startDiscovery();


                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    Log.d("test", "bluetooth unregisterReceiver");
                    //getActivity().unregisterReceiver(mReceiver);
                }
            }
        });

        // 影像IP Camera
        mCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (videoThread == null) {
                    videoThread = new Thread(VideoRunnable);
                    videoThread.start();
                }

            }
        });


        if (videoThread == null) {
            videoThread = new Thread(VideoRunnable);
            videoThread.start();
        }

    }

    private BluetoothDevice mBluetoothDevice;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (mBluetoothDeviceName.equals(device.getName())) {
                    //mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                    //mBluetoothAddress = device.getAddress();
                    Log.d("test", device.getName() + "\n" + device.getAddress());

                    mBluetoothDevice = device;
                    mChatService.connect(device, false);
                }
            }
        }
    };

    private final BroadcastReceiver pairingRequest = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals("android.bluetooth.device.action.PAIRING_REQUEST")) {
                try {
                    byte[] pin = (byte[]) BluetoothDevice.class.getMethod("convertPinToBytes", String.class).invoke(BluetoothDevice.class, "1234");
                    Method m = mBluetoothDevice.getClass().getMethod("setPin", byte[].class);
                    m.invoke(mBluetoothDevice, pin);
                    mBluetoothDevice.getClass().getMethod("setPairingConfirmation", boolean.class).invoke(mBluetoothDevice, true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    /**
     * 送出指令
     *
     * @param cmd
     */
    private void sendCommand(String cmd) {

        if (!mStartRunning
                && (cmd.startsWith("f") || cmd.startsWith("b"))) {
            return;
        }

        // 指令重覆退回不執行(touch時會持續發出相同的指令)
        if (cmd.equals(lastCmd)) {
            return;
        }

        Log.d("test", "send:" + cmd);

        lastCmd = cmd;

        if (mChatService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (cmd.length() > 0) {
            byte[] send = (cmd).getBytes();
            mChatService.write(send);
        }
    }


    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }


    ;


    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving,
                            Toast.LENGTH_SHORT).show();
                    getActivity().finish();
                }
                break;

        }
    }

    /**
     * Establish connection with other divice
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device, secure);
    }

    /**
     * 縮小/放大 圖片
     *
     * @param bitmap
     * @param w
     * @param h
     * @return
     */
    public static Bitmap resizeImage(Bitmap bitmap, int w, int h) {
        Bitmap BitmapOrg = bitmap;
        int width = BitmapOrg.getWidth();
        int height = BitmapOrg.getHeight();
        int newWidth = w;
        int newHeight = h;

        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;

        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        Bitmap resizedBitmap = Bitmap.createBitmap(BitmapOrg, 0, 0, width,
                height, matrix, true);
        return resizedBitmap;
    }


    /**
     * 根據建議"This Handler class should be static or leaks might occur""
     * 改用靜態+弱參考，防止momery leaks
     */
    static class MyHandler extends Handler {

        private WeakReference<BluetoothChatFragment> weakReference;

        MyHandler(BluetoothChatFragment activity) {
            weakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {

            BluetoothChatFragment fragment = weakReference.get();

            if (fragment == null || fragment.getActivity() == null) {
                return;
            }

            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothService.STATE_CONNECTED:
                            fragment.setStatus(fragment.getString(R.string.title_connected_to,
                                    fragment.mConnectedDeviceName));
                            break;
                        case BluetoothService.STATE_CONNECTING:
                            fragment.setStatus(R.string.title_connecting);
                            break;
                        case BluetoothService.STATE_LISTEN:
                        case BluetoothService.STATE_NONE:
                            fragment.setStatus(R.string.title_not_connected);
                            break;
                    }
                    break;

                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    break;

                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    fragment.mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    Toast.makeText(fragment.getActivity(), "Connected to "
                            + fragment.mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    break;

                case Constants.MESSAGE_TOAST:
                    if (null != fragment) {

                        Log.d("test", msg.getData().getString(Constants.TOAST));

                        Toast.makeText(fragment.getActivity(), msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;


                case Constants.MESSAGE_VIDEO:
                    ByteArrayOutputStream os = (ByteArrayOutputStream) msg.obj;

                    Bitmap bitmap = BitmapFactory.decodeByteArray(os.toByteArray(), 0, os.size());

                    fragment.mImageView.setImageBitmap(resizeImage(bitmap,
                            fragment.mImageView.getWidth(),
                            fragment.mImageView.getHeight()));
                    break;
            }
        }
    }

}
