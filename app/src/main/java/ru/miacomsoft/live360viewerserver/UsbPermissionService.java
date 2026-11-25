package ru.miacomsoft.live360viewerserver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class UsbPermissionService extends AccessibilityService {
    private static final String TAG = "UsbPermissionService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Реагируем на любые изменения окон
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // 1. Проверяем, есть ли на экране слова USB или CAM
            boolean isUsbDialog = false;
            List<AccessibilityNodeInfo> usbTexts = root.findAccessibilityNodeInfosByText("USB");
            if (usbTexts == null || usbTexts.isEmpty()) {
                usbTexts = root.findAccessibilityNodeInfosByText("CAM");
            }
            if (usbTexts != null && !usbTexts.isEmpty()) {
                isUsbDialog = true;
            }

            // 2. Если это диалог USB, ищем кнопки подтверждения
            if (isUsbDialog) {
                clickButtonByText(root, "OK");
                clickButtonByText(root, "Allow");
                clickButtonByText(root, "Разрешить");
                clickButtonByText(root, "ОК");
            }
        }
    }

    private void clickButtonByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                // Проверяем, что это действительно кнопка (или её родитель)
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "✅ Автоматически нажата кнопка: " + text);
                    return;
                } else if (node.getParent() != null && node.getParent().isClickable()) {
                    node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "✅ Автоматически нажата кнопка (parent): " + text);
                    return;
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility Service CONNECTED");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // ★ Важно: разрешаем службе видеть все окна
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
    }
}