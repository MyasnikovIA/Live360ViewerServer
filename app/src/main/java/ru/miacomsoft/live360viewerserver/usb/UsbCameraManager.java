package ru.miacomsoft.live360viewerserver.usb;

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
import android.util.Log;

import java.util.HashMap;

public class UsbCameraManager {

    private static final String TAG = "UsbCamera";
    private static final String ACTION_USB_PERMISSION =
            "ru.miacomsoft.live360viewer.USB_PERMISSION";

    public interface Listener {
        void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                         UsbEndpoint epIn, UsbEndpoint epOut);
        void onDetached();
        void onPermissionDenied();
        void onError(String msg);
    }

    private final Context ctx;
    private UsbManager usbManager;
    private Listener listener;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint epIn, epOut;
    private PendingIntent permIntent;
    private boolean registered;

    public UsbCameraManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                boolean ok = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok) openDevice();
                else if (listener != null) listener.onPermissionDenied();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && d.equals(device)) {
                    close();
                    if (listener != null) listener.onDetached();
                }
            }
        }
    };

    public void start(Listener l) {
        this.listener = l;
        this.usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);

        // ★ Регистрируем receiver один раз
        if (!registered) {
            int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            permIntent = PendingIntent.getBroadcast(
                    ctx, 0, new Intent(ACTION_USB_PERMISSION), flags);

            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_USB_PERMISSION);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            ctx.registerReceiver(receiver, f);
            registered = true;
        }

        // ★ Если предыдущее соединение осталось — освободить
        if (connection != null) {
            Log.i(TAG, "start: closing previous connection");
            close();
        }

        device = findCamera();
        if (device == null) {
            if (listener != null) listener.onError("Camera not found");
            return;
        }
        if (usbManager.hasPermission(device)) {
            openDevice();
        } else {
            usbManager.requestPermission(device, permIntent);
        }
    }

    private UsbDevice findCamera() {
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        for (UsbDevice d : list.values()) {
            Log.d(TAG, "USB vid=" + d.getVendorId() + " pid=" + d.getProductId());
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) {
                return d;
            }
        }
        return null;
    }

    private void openDevice() {
        if (device == null) return;
        intf = null; epIn = null; epOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            UsbEndpoint iIn = null, iOut = null;
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) iIn = ep;
                    else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) iOut = ep;
                }
            }
            if (iIn != null && iOut != null) {
                intf = it; epIn = iIn; epOut = iOut; break;
            }
        }
        if (intf == null) {
            if (listener != null) listener.onError("No bulk interface");
            return;
        }
        connection = usbManager.openDevice(device);
        if (connection == null || !connection.claimInterface(intf, true)) {
            if (listener != null) listener.onError("claimInterface failed");
            return;
        }
        Log.i(TAG, "USB connected, epIn=" + epIn + " epOut=" + epOut);
        if (listener != null) listener.onConnected(connection, intf, epIn, epOut);
    }

    public void close() {
        if (connection != null) {
            try { connection.releaseInterface(intf); } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        intf = null; epIn = null; epOut = null; device = null;
    }

    public void stop() {
        close();
        if (registered) {
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            registered = false;
        }
    }
}