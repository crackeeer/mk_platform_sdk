/*
 * Copyright (c) 2013-2014 The MoKee OpenSource Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package org.mokee.platform.internal;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import mokee.app.Profile;

import org.mokee.platform.internal.ProfileManagerService;

import java.util.UUID;

/**
 * @hide
 */
public class ProfileTriggerHelper extends BroadcastReceiver {
    private static final String TAG = "ProfileTriggerHelper";

    private Context mContext;
    private ProfileManagerService mManagerService;

    private WifiManager mWifiManager;
    private String mLastConnectedSSID;

    private IntentFilter mIntentFilter;
    private boolean mFilterRegistered = false;

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateEnabled();
        }
    }
    private final ContentObserver mSettingsObserver;

    public ProfileTriggerHelper(Context context, Handler handler,
            ProfileManagerService profileManagerService) {
        mContext = context;
        mManagerService = profileManagerService;
        mSettingsObserver = new SettingsObserver(handler);

        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mLastConnectedSSID = getActiveSSID();

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        mIntentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
       // mIntentFilter.addAction(AudioManager.A2DP_ROUTE_CHANGED_ACTION);
        updateEnabled();

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SYSTEM_PROFILES_ENABLED), false,
                mSettingsObserver);
    }

    public void updateEnabled() {
        boolean enabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SYSTEM_PROFILES_ENABLED, 1) == 1;
        if (enabled && !mFilterRegistered) {
            Log.v(TAG, "Enabling");
            mContext.registerReceiver(this, mIntentFilter);
            mFilterRegistered = true;
        } else if (!enabled && mFilterRegistered) {
            Log.v(TAG, "Disabling");
            mContext.unregisterReceiver(this);
            mFilterRegistered = false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            String activeSSID = getActiveSSID();
            int triggerState;

            if (activeSSID != null) {
                triggerState = Profile.TriggerState.ON_CONNECT;
                mLastConnectedSSID = activeSSID;
            } else {
                triggerState = Profile.TriggerState.ON_DISCONNECT;
            }
            checkTriggers(Profile.TriggerType.WIFI, mLastConnectedSSID, triggerState);
        } else if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)
                || action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
            int triggerState = action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)
                    ? Profile.TriggerState.ON_CONNECT : Profile.TriggerState.ON_DISCONNECT;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            checkTriggers(Profile.TriggerType.BLUETOOTH, device.getAddress(), triggerState);
/*        } else if (action.equals(AudioManager.A2DP_ROUTE_CHANGED_ACTION)) {
            BluetoothDevice device = intent
                    .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0);
            int triggerState = (state == BluetoothProfile.STATE_CONNECTED)
                    ? Profile.TriggerState.ON_A2DP_CONNECT :
                    Profile.TriggerState.ON_A2DP_DISCONNECT;

            checkTriggers(Profile.TriggerType.BLUETOOTH, device.getAddress(), triggerState);*/
        }
    }

    private void checkTriggers(int type, String id, int newState) {
        for (Profile p : mManagerService.getProfileList()) {
            if (newState != p.getTriggerState(type, id)) {
                continue;
            }

            UUID currentProfileUuid = mManagerService.getActiveProfileInternal().getUuid();
            if (!currentProfileUuid.equals(p.getUuid())) {
                mManagerService.setActiveProfileInternal(p, true);
            }
        }
    }

    private String getActiveSSID() {
        WifiInfo wifiinfo = mWifiManager.getConnectionInfo();
        if (wifiinfo == null) {
            return null;
        }
        WifiSsid ssid = wifiinfo.getWifiSsid();
        if (ssid == null) {
            return null;
        }
        return ssid.toString();
    }
}
