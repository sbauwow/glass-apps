package com.glasscanon;

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
 * USB PTP connection to Canon cameras for live view streaming.
 *
 * Canon cameras use PTP (Picture Transfer Protocol) over USB:
 *   - Interface class 6 (Imaging), subclass 1, protocol 1
 *   - Bulk IN + bulk OUT endpoints for PTP commands/data
 *   - Canon vendor extensions for live view (0x9153) and shutter (0x910F)
 *
 * IO thread lifecycle:
 *   OpenSession → SetRemoteMode → SetEventMode → SetEVFOutputDevice →
 *   [GetViewFinderData loop] → SetEVFOutputDevice(off) → CloseSession
 */
public class CanonUsb {

    private static final String TAG = "CanonUSB";

    private static final int CANON_VID = 0x04A9;

    // PTP imaging interface class
    private static final int USB_CLASS_IMAGE = 6;

    private static final int READ_TIMEOUT_MS = 2000;
    private static final int WRITE_TIMEOUT_MS = 1000;
    private static final int LIVE_VIEW_POLL_MS = 50;

    // Max read buffer: live view JPEGs can be ~200KB
    private static final int MAX_READ_BUF = 512 * 1024;

    private static final String ACTION_USB_PERMISSION = "com.glasscanon.USB_PERMISSION";

    public interface Listener {
        void onStatusChanged(String status);
        void onFrameReceived(byte[] jpegData);
    }

    private final Context context;
    private final Handler handler;
    private Listener listener;

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbInterface ptpInterface;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;

    private volatile boolean running = false;
    private volatile boolean shutterRequested = false;
    private boolean permissionReceiverRegistered = false;
    private Thread ioThread;
    private int txnId = 0;

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

    public CanonUsb(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Request shutter release from any thread. Picked up by IO loop. */
    public void requestShutter() {
        shutterRequested = true;
    }

    /** Start with auto-detected device (requests permission if needed). */
    public void start() {
        if (running) return;

        UsbDevice canon = findCanon();
        if (canon == null) {
            notifyStatus("No Canon camera found");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { start(); }
            }, 2000);
            return;
        }

        if (!usbManager.hasPermission(canon)) {
            notifyStatus("Requesting USB permission...");
            if (!permissionReceiverRegistered) {
                context.registerReceiver(permissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                permissionReceiverRegistered = true;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(canon, pi);
            return;
        }

        connectDevice(canon);
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
        notifyStatus("Connected");

        ioThread = new Thread(new IoRunnable(), "CanonIO");
        ioThread.start();
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(3000); } catch (InterruptedException e) { /* ignore */ }
            ioThread = null;
        }
        closeDevice();
        txnId = 0;
        if (permissionReceiverRegistered) {
            try { context.unregisterReceiver(permissionReceiver); } catch (Exception e) { /* ignore */ }
            permissionReceiverRegistered = false;
        }
    }

    private UsbDevice findCanon() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        // First pass: look for Canon VID
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == CANON_VID) {
                Log.i(TAG, "Found Canon: " + device.getDeviceName()
                    + " PID=" + Integer.toHexString(device.getProductId()));
                return device;
            }
        }
        // Fallback: any PTP/imaging class device
        for (UsbDevice device : devices.values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == USB_CLASS_IMAGE) {
                    Log.i(TAG, "Found imaging device: " + device.getDeviceName()
                        + " VID=" + Integer.toHexString(device.getVendorId()));
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

        // Find PTP imaging interface (class 6, subclass 1, protocol 1)
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == USB_CLASS_IMAGE) {
                ptpInterface = iface;
                break;
            }
        }

        if (ptpInterface == null) {
            Log.e(TAG, "No PTP imaging interface found");
            connection.close();
            connection = null;
            return false;
        }

        if (!connection.claimInterface(ptpInterface, true)) {
            Log.e(TAG, "Failed to claim PTP interface");
            connection.close();
            connection = null;
            return false;
        }

        // Find bulk IN and OUT endpoints (skip interrupt endpoint)
        for (int i = 0; i < ptpInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = ptpInterface.getEndpoint(i);
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
            connection.releaseInterface(ptpInterface);
            connection.close();
            connection = null;
            return false;
        }

        Log.i(TAG, "PTP device opened: epIn=0x" + Integer.toHexString(epIn.getAddress())
            + " epOut=0x" + Integer.toHexString(epOut.getAddress())
            + " maxPacket=" + epIn.getMaxPacketSize());
        return true;
    }

    private void closeDevice() {
        if (connection != null) {
            if (ptpInterface != null) {
                connection.releaseInterface(ptpInterface);
            }
            connection.close();
            connection = null;
        }
        ptpInterface = null;
        epIn = null;
        epOut = null;
    }

    // ---- PTP transport helpers ----

    /** Send raw bytes to the bulk OUT endpoint. */
    private boolean sendRaw(byte[] data) {
        int sent = connection.bulkTransfer(epOut, data, data.length, WRITE_TIMEOUT_MS);
        if (sent != data.length) {
            Log.w(TAG, "Send incomplete: " + sent + "/" + data.length);
            return false;
        }
        return true;
    }

    /**
     * Read a complete PTP container from the bulk IN endpoint.
     * PTP containers start with a uint32 length field. We read the first packet
     * to get the length, then continue reading until we have all the bytes.
     *
     * @return Buffer containing the complete container, or null on error.
     */
    private byte[] readContainer() {
        byte[] buf = new byte[MAX_READ_BUF];
        int totalRead = 0;

        // First read to get the container header
        int read = connection.bulkTransfer(epIn, buf, buf.length, READ_TIMEOUT_MS);
        if (read < 4) {
            if (read >= 0) Log.w(TAG, "Short read: " + read + " bytes");
            return null;
        }
        totalRead = read;

        // Parse expected container length
        int containerLen = CanonPtp.getLE32(buf, 0);
        if (containerLen < 12 || containerLen > MAX_READ_BUF) {
            Log.w(TAG, "Invalid container length: " + containerLen);
            return null;
        }

        // Read remaining data if the container spans multiple USB packets
        while (totalRead < containerLen) {
            read = connection.bulkTransfer(epIn, buf, totalRead, buf.length - totalRead, READ_TIMEOUT_MS);
            if (read < 0) {
                Log.w(TAG, "Read error while assembling container, got " + totalRead + "/" + containerLen);
                return null;
            }
            totalRead += read;
        }

        // Return trimmed copy
        byte[] result = new byte[totalRead];
        System.arraycopy(buf, 0, result, 0, totalRead);
        return result;
    }

    /**
     * Execute a simple PTP command (no data phase out, may have data phase in).
     *
     * @return The data container payload (after 12-byte header), or empty array for no-data responses.
     *         Returns null on error.
     */
    private byte[] ptpCommand(int opcode, int... params) {
        txnId++;
        byte[] cmd = CanonPtp.buildCommand(opcode, txnId, params);
        if (!sendRaw(cmd)) return null;

        // Read first container (could be Data or Response)
        byte[] container = readContainer();
        if (container == null) return null;

        int type = CanonPtp.parseContainerType(container, 0);

        if (type == CanonPtp.TYPE_DATA) {
            // Save data payload, then read the Response container
            int dataLen = container.length - 12;
            byte[] data = null;
            if (dataLen > 0) {
                data = new byte[dataLen];
                System.arraycopy(container, 12, data, 0, dataLen);
            }

            byte[] resp = readContainer();
            if (resp != null) {
                int respCode = CanonPtp.parseResponseCode(resp, 0);
                if (respCode != CanonPtp.RESP_OK) {
                    Log.w(TAG, String.format("PTP 0x%04X response: 0x%04X", opcode, respCode));
                }
            }
            return data != null ? data : new byte[0];
        } else if (type == CanonPtp.TYPE_RESPONSE) {
            int respCode = CanonPtp.parseResponseCode(container, 0);
            if (respCode != CanonPtp.RESP_OK) {
                Log.w(TAG, String.format("PTP 0x%04X response: 0x%04X", opcode, respCode));
            }
            return new byte[0];
        } else {
            Log.w(TAG, "Unexpected container type: " + type);
            return null;
        }
    }

    /**
     * Execute a SetDevicePropValue command (requires data phase out).
     * Sends: Command → Data → reads Response.
     */
    private boolean setDeviceProperty(int propCode, int value) {
        txnId++;
        byte[] cmd = CanonPtp.buildCommand(CanonPtp.OP_SET_DEVICE_PROP, txnId, propCode);
        if (!sendRaw(cmd)) return false;

        byte[] data = CanonPtp.buildDataU32(CanonPtp.OP_SET_DEVICE_PROP, txnId, value);
        if (!sendRaw(data)) return false;

        byte[] resp = readContainer();
        if (resp == null) return false;

        int respCode = CanonPtp.parseResponseCode(resp, 0);
        if (respCode != CanonPtp.RESP_OK) {
            Log.w(TAG, String.format("SetDeviceProp 0x%04X = 0x%X → response 0x%04X",
                propCode, value, respCode));
            return false;
        }
        return true;
    }

    // ---- IO thread ----

    private class IoRunnable implements Runnable {
        @Override
        public void run() {
            try {
                initSession();
                if (!running) return;
                liveViewLoop();
            } catch (Exception e) {
                Log.e(TAG, "IO error", e);
            } finally {
                cleanupSession();
            }

            if (running) {
                running = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("Disconnected");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() { start(); }
                        }, 2000);
                    }
                });
            }
        }

        private void initSession() {
            postStatus("Opening session...");

            // 1. Open PTP session
            byte[] result = ptpCommand(CanonPtp.OP_OPEN_SESSION, 1);
            if (result == null) {
                Log.e(TAG, "OpenSession failed");
                running = false;
                return;
            }
            Log.i(TAG, "PTP session opened");

            // 2. Set remote mode (enables Canon vendor commands)
            postStatus("Setting remote mode...");
            ptpCommand(CanonPtp.OP_SET_REMOTE_MODE, 1);

            // 3. Set event mode
            ptpCommand(CanonPtp.OP_SET_EVENT_MODE, 1);

            // 4. Set EVF output to host (0x02 = PC, enables live view over USB)
            postStatus("Starting live view...");
            if (!setDeviceProperty(CanonPtp.PROP_EVF_OUTPUT_DEVICE, 0x02)) {
                Log.w(TAG, "Failed to set EVF output — live view may not work");
            }

            // Brief pause to let the camera initialize live view
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }

        private void liveViewLoop() {
            int errorCount = 0;

            while (running) {
                // Handle shutter request between frames
                if (shutterRequested) {
                    shutterRequested = false;
                    postStatus("Capturing...");
                    // Full press
                    ptpCommand(CanonPtp.OP_REMOTE_RELEASE, 0x03);
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    // Release
                    ptpCommand(CanonPtp.OP_REMOTE_RELEASE, 0x00);
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    postStatus("Captured!");
                    continue;
                }

                // Request live view frame
                // Params: 0x00200000 = image size hint, additional params for Canon protocol
                byte[] data = ptpCommand(CanonPtp.OP_GET_LIVE_VIEW, 0x00200000);

                if (data == null || data.length == 0) {
                    errorCount++;
                    if (errorCount > 20) {
                        Log.e(TAG, "Too many live view errors, giving up");
                        break;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    continue;
                }

                errorCount = 0;

                // Extract JPEG from the live view data
                byte[] jpeg = CanonPtp.extractLiveViewJpeg(data, 0, data.length);
                if (jpeg != null && jpeg.length > 100) {
                    final byte[] frame = jpeg;
                    if (listener != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() { listener.onFrameReceived(frame); }
                        });
                    }
                }

                // Brief pause to avoid overwhelming the camera
                try { Thread.sleep(LIVE_VIEW_POLL_MS); } catch (InterruptedException e) { return; }
            }
        }

        private void cleanupSession() {
            if (connection == null) return;
            try {
                // Turn off EVF output
                setDeviceProperty(CanonPtp.PROP_EVF_OUTPUT_DEVICE, 0x00);
                // Close PTP session
                ptpCommand(CanonPtp.OP_CLOSE_SESSION);
            } catch (Exception e) {
                Log.w(TAG, "Cleanup error", e);
            }
        }

        private void postStatus(final String status) {
            handler.post(new Runnable() {
                @Override
                public void run() { notifyStatus(status); }
            });
        }
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }
}
