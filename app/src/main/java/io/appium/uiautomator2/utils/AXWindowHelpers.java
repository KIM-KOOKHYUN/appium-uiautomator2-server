/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.uiautomator2.utils;

import android.os.Build;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.appium.uiautomator2.common.exceptions.UiAutomator2Exception;
import io.appium.uiautomator2.core.UiAutomatorBridge;
import io.appium.uiautomator2.model.internal.CustomUiDevice;
import io.appium.uiautomator2.model.settings.EnableMultiWindows;
import io.appium.uiautomator2.model.settings.EnableTopmostWindowFromActivePackage;
import io.appium.uiautomator2.model.settings.Settings;

public class AXWindowHelpers {
    private static final long AX_ROOT_RETRIEVAL_TIMEOUT_MS = 10000;
    private static AccessibilityNodeInfo[] cachedWindowRoots = null;

    /**
     * Clears the in-process Accessibility cache, removing any stale references. Because the
     * AccessibilityInteractionClient singleton stores copies of AccessibilityNodeInfo instances,
     * calls to public APIs such as `recycle` do not guarantee cached references get updated. See
     * the android.view.accessibility AIC and ANI source code for more information.
     */
    private static void clearAccessibilityCache() {
        try {
            // This call invokes `AccessibilityInteractionClient.getInstance().clearCache();` method
            UiAutomatorBridge.getInstance().getUiAutomation().setServiceInfo(null);
        } catch (NullPointerException npe) {
            // it is fine
            // ignore
        } catch (Exception e) {
            Logger.warn("Failed to clear Accessibility Node cache", e);
        }
    }

    public static void refreshAccessibilityCache() {
        //Device.waitForIdle();
        clearAccessibilityCache();
        cachedWindowRoots = null;
    }

    private static AccessibilityNodeInfo getActiveWindowRoot() {
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < AX_ROOT_RETRIEVAL_TIMEOUT_MS) {
            try {
                AccessibilityNodeInfo root = UiAutomatorBridge.getInstance().getAccessibilityRootNode();
                if (root != null) {
                    return root;
                }
            } catch (Exception e) {
                /*
                 * Sometimes getAccessibilityRootNode() throws
                 * "java.lang.IllegalStateException: Cannot perform this action on a sealed instance."
                 * Ignore it and try to re-get root node.
                 */
                Logger.info("An exception was caught while looking for " +
                        "the root of the active window. Ignoring it", e);
            }
        }
        throw new UiAutomator2Exception(String.format(
                "Timed out after %dms waiting for the root AccessibilityNodeInfo in the active window. " +
                        "Make sure the active window is not constantly hogging the main UI thread " +
                        "(e.g. the application is being idle long enough), so the accessibility " +
                        "manager could do its work", SystemClock.uptimeMillis() - start));
    }

    private static List<AccessibilityWindowInfo> getWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CustomUiDevice.getInstance().getUiAutomation().getWindows();
        }

        int currentDisplayId = UiAutomatorBridge.getInstance().getCurrentDisplayId();

        SparseArray<List<AccessibilityWindowInfo>> windowsOnAllDisplays = CustomUiDevice
                .getInstance()
                .getUiAutomation()
                .getWindowsOnAllDisplays();

        if (!windowsOnAllDisplays.contains(currentDisplayId)) {
            return Collections.emptyList();
        }

        return windowsOnAllDisplays.get(currentDisplayId);
    }

    private static AccessibilityNodeInfo[] getWindowRoots() {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                Logger.info(String.format("Skipping null root node for window: %s", window.toString()));
                continue;
            }
            result.add(root);
        }
        return result.toArray(new AccessibilityNodeInfo[0]);
    }

    private static AccessibilityNodeInfo getTopmostWindowRootFromActivePackage() {
        CharSequence activeRootPackageName = Objects.requireNonNull(getActiveWindowRoot().getPackageName());

        List<AccessibilityWindowInfo> windows = getWindows();
        Collections.sort(windows, new Comparator<AccessibilityWindowInfo>() {
            @Override
            public int compare(AccessibilityWindowInfo w1, AccessibilityWindowInfo w2) {
                return Integer.compare(w2.getLayer(), w1.getLayer()); // descending order
            }
        });
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null &&
                    Objects.equals(root.getPackageName(), activeRootPackageName)) {
                return root;
            }
        }
        throw new UiAutomator2Exception(
                String.format("Unable to find the active topmost window associated with %s package",
                        activeRootPackageName));
    }

    public static AccessibilityNodeInfo[] getCachedWindowRoots() {
        if (cachedWindowRoots == null) {
            boolean shouldRetrieveAllWindowRoots = Settings.get(EnableMultiWindows.class).getValue();
            // Multi-window retrieval is needed to search the topmost window from active package.
            boolean shouldRetrieveTopmostWindowRootFromActivePackage = Settings.get(
                    EnableTopmostWindowFromActivePackage.class
            ).getValue();
            /*
             * ENABLE_MULTI_WINDOWS and ENABLE_TOPMOST_WINDOW_FROM_ACTIVE_PACKAGE
             * are disabled by default
             * because UIAutomatorViewer captures active window properties and
             * end users always rely on its output while writing their tests.
             * https://code.google.com/p/android/issues/detail?id=207569
             */
            cachedWindowRoots = shouldRetrieveAllWindowRoots
                    ? getWindowRoots()
                    : new AccessibilityNodeInfo[] {
                            shouldRetrieveTopmostWindowRootFromActivePackage
                                    ? getTopmostWindowRootFromActivePackage()
                                    : getActiveWindowRoot()
                    };
        }
        return cachedWindowRoots;
    }
}
