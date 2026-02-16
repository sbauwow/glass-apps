package com.glassflipper;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;

/**
 * USB CDC serial connection to Flipper Zero for screen streaming.
 *
 * Flipper Zero USB: VID=0x0483 PID=0x5740, CDC ACM class.
 *
 * RPC state machine: IDLE → STARTING_RPC → RPC_READY → STREAMING
 */
public class FlipperUsb {

    private static final String TAG = "FlipperUSB";

    // Flipper Zero USB IDs (STMicroelectronics CDC)
    private static final int FLIPPER_VID = 0x0483;
    private static final int FLIPPER_PID = 0x5740;

    // CDC ACM control requests
    private static final int SET_LINE_CODING = 0x20;
    private static final int SET_CONTROL_LINE_STATE = 0x22;

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING_RPC = 1;
    private static final int STATE_RPC_READY = 2;
    private static final int STATE_STREAMING = 3;

    private static final int READ_TIMEOUT_MS = 100;
    private static final int WRITE_TIMEOUT_MS = 1000;
    private static final String ACTION_USB_PERMISSION = "com.glassflipper.USB_PERMISSION";

    public interface Listener {
        void onStatusChanged(String status);
        void onFrameReceived(byte[] xbmData);
    }

    private final Context context;
    private final Handler handler;
    private Listener listener;

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbInterface dataInterface;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;

    private volatile boolean running = false;
    private boolean permissionReceiverRegistered = false;
    private int rpcState = STATE_IDLE;
    private Thread ioThread;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(TAG, "USB permission " + (granted ? "granted" : "denied"));
                if (granted && device != null) {
                    start();
                } else {
                    notifyStatus("USB permission denied");
                }
            }
        }
    };

    public FlipperUsb(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Start with auto-detected device (requests permission if needed). */
    public void start() {
        if (running) return;

        UsbDevice flipper = findFlipper();
        if (flipper == null) {
            notifyStatus("No Flipper USB found");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { start(); }
            }, 2000);
            return;
        }

        if (!usbManager.hasPermission(flipper)) {
            notifyStatus("Requesting USB permission...");
            if (!permissionReceiverRegistered) {
                context.registerReceiver(permissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                permissionReceiverRegistered = true;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(flipper, pi);
            return;
        }

        connectDevice(flipper);
    }

    /** Start with a device from USB_DEVICE_ATTACHED intent (permission already granted). */
    public void startWithDevice(UsbDevice device) {
        if (running) return;
        connectDevice(device);
    }

    private void connectDevice(UsbDevice device) {
        if (!openDevice(device)) {
            notifyStatus("USB open failed");
            return;
        }

        running = true;
        notifyStatus("Connected via USB");

        ioThread = new Thread(new IoRunnable(), "FlipperIO");
        ioThread.start();
    }

    public void stop() {
        running = false;
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException e) { /* ignore */ }
            ioThread = null;
        }
        closeDevice();
        rpcState = STATE_IDLE;
        if (permissionReceiverRegistered) {
            try { context.unregisterReceiver(permissionReceiver); } catch (Exception e) { /* ignore */ }
            permissionReceiverRegistered = false;
        }
    }

    private UsbDevice findFlipper() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == FLIPPER_VID && device.getProductId() == FLIPPER_PID) {
                Log.i(TAG, "Found Flipper: " + device.getDeviceName());
                return device;
            }
        }
        // Fallback: look for any CDC ACM device
        for (UsbDevice device : devices.values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                    Log.i(TAG, "Found CDC device: " + device.getDeviceName()
                        + " VID=" + Integer.toHexString(device.getVendorId())
                        + " PID=" + Integer.toHexString(device.getProductId()));
                    return device;
                }
            }
        }
        return null;
    }

    private boolean openDevice(UsbDevice device) {
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device");
            return false;
        }

        // Find CDC data interface with bulk endpoints
        UsbInterface ctrlInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            // Control interface: CDC ACM (class 2, subclass 2)
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                ctrlInterface = iface;
            }
            // Data interface: CDC Data (class 10)
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                dataInterface = iface;
            }
        }

        if (dataInterface == null) {
            Log.e(TAG, "No CDC data interface found");
            connection.close();
            connection = null;
            return false;
        }

        if (!connection.claimInterface(dataInterface, true)) {
            Log.e(TAG, "Failed to claim data interface");
            connection.close();
            connection = null;
            return false;
        }

        // Claim control interface too if present
        if (ctrlInterface != null) {
            connection.claimInterface(ctrlInterface, true);
            // Set DTR + RTS (required for Flipper CDC)
            connection.controlTransfer(
                0x21, SET_CONTROL_LINE_STATE, 0x03, 0, null, 0, WRITE_TIMEOUT_MS);
        }

        // Find bulk IN and OUT endpoints on data interface
        for (int i = 0; i < dataInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = dataInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    epIn = ep;
                } else {
                    epOut = ep;
                }
            }
        }

        if (epIn == null || epOut == null) {
            Log.e(TAG, "Missing bulk endpoints (in=" + epIn + " out=" + epOut + ")");
            connection.close();
            connection = null;
            return false;
        }

        Log.i(TAG, "USB device opened: epIn=" + epIn.getAddress()
            + " epOut=" + epOut.getAddress()
            + " maxIn=" + epIn.getMaxPacketSize()
            + " maxOut=" + epOut.getMaxPacketSize());
        return true;
    }

    private void closeDevice() {
        if (connection != null) {
            if (dataInterface != null) {
                connection.releaseInterface(dataInterface);
            }
            connection.close();
            connection = null;
        }
        dataInterface = null;
        epIn = null;
        epOut = null;
    }

    // ---- IO thread ----

    private class IoRunnable implements Runnable {
        @Override
        public void run() {
            try {
                startRpcSession();
                if (!running) return;
                sendStartStream();
                if (!running) return;
                readFrameLoop();
            } catch (Exception e) {
                Log.e(TAG, "IO error", e);
            }

            if (running) {
                running = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("USB disconnected");
                        // Retry after delay
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() { start(); }
                        }, 2000);
                    }
                });
            }
        }

        private void startRpcSession() {
            rpcState = STATE_STARTING_RPC;
            handler.post(new Runnable() {
                @Override
                public void run() { notifyStatus("Starting RPC..."); }
            });

            // Send start_rpc_session command
            byte[] cmd = "start_rpc_session\r".getBytes();
            int sent = connection.bulkTransfer(epOut, cmd, cmd.length, WRITE_TIMEOUT_MS);
            Log.i(TAG, "Sent start_rpc_session: " + sent + " bytes");

            // Wait for \n response
            byte[] buf = new byte[256];
            long deadline = System.currentTimeMillis() + 5000;
            while (running && System.currentTimeMillis() < deadline) {
                int read = connection.bulkTransfer(epIn, buf, buf.length, READ_TIMEOUT_MS);
                if (read > 0) {
                    for (int i = 0; i < read; i++) {
                        if (buf[i] == '\n') {
                            Log.i(TAG, "RPC session started");
                            rpcState = STATE_RPC_READY;
                            return;
                        }
                    }
                }
            }
            Log.w(TAG, "RPC session timeout — proceeding anyway");
            rpcState = STATE_RPC_READY;
        }

        private void sendStartStream() {
            rpcState = STATE_STREAMING;
            byte[] msg = FlipperProto.encodeStartScreenStream();
            int sent = connection.bulkTransfer(epOut, msg, msg.length, WRITE_TIMEOUT_MS);
            Log.i(TAG, "Sent start_screen_stream: " + sent + " bytes");
            handler.post(new Runnable() {
                @Override
                public void run() { notifyStatus("Streaming"); }
            });
        }

        private void readFrameLoop() {
            byte[] readBuf = new byte[4096];
            byte[] rxBuffer = new byte[8192];
            int rxLen = 0;

            while (running) {
                int read = connection.bulkTransfer(epIn, readBuf, readBuf.length, READ_TIMEOUT_MS);
                if (read <= 0) continue;

                // Append to reassembly buffer
                if (rxLen + read > rxBuffer.length) {
                    Log.w(TAG, "RX buffer overflow, resetting");
                    rxLen = 0;
                }
                System.arraycopy(readBuf, 0, rxBuffer, rxLen, read);
                rxLen += read;

                // Try to decode complete protobuf messages
                while (rxLen > 0) {
                    long[] varint = FlipperProto.decodeVarint(rxBuffer, 0, rxLen);
                    if (varint == null) break;

                    int prefixLen = (int) varint[1];
                    int msgLen = (int) varint[0];
                    int totalLen = prefixLen + msgLen;

                    if (totalLen > rxLen) break; // need more data

                    byte[] xbm = FlipperProto.decodeMainResponse(rxBuffer, prefixLen, msgLen);

                    // Consume message
                    int remaining = rxLen - totalLen;
                    if (remaining > 0) {
                        System.arraycopy(rxBuffer, totalLen, rxBuffer, 0, remaining);
                    }
                    rxLen = remaining;

                    if (xbm != null && xbm.length == FlipperProto.XBM_FRAME_SIZE) {
                        final byte[] frame = xbm;
                        if (listener != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() { listener.onFrameReceived(frame); }
                            });
                        }
                    }
                }
            }
        }
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }
}
