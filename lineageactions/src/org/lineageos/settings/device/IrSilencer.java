/*
 * Copyright (c) 2015 The CyanogenMod Project
 * Copyright (c) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.telephony.PhoneStateListener;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.lineageos.settings.device.actions.UpdatedStateNotifier;

import static org.lineageos.settings.device.IrGestureManager.*;
import static android.telephony.TelephonyManager.*;

public class IrSilencer extends PhoneStateListener implements SensorEventListener, UpdatedStateNotifier {
    private static final String TAG = "LineageActions-IRSilencer";

    private static final int IR_GESTURES_FOR_RINGING = (1 << IR_GESTURE_SWIPE) | (1 << IR_GESTURE_APPROACH);
    private static final int SILENCE_DELAY_MS = 500;

    private final Context mContext;
    private final TelecomManager mTelecomManager;
    private final TelephonyManager mTelephonyManager;
    private final LineageActionsSettings mLineageActionsSettings;
    private final SensorHelper mSensorHelper;
    private final Sensor mSensor;
    private final IrGestureVote mIrGestureVote;

    private boolean mPhoneRinging;
    private long mPhoneRingStartedMs;

    private boolean mAlarmRinging;
    private long mAlarmRingStartedMs;

    private int mAlarmAction;

    // Alarm snoozing
    private static final String ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT";
    private static final String ALARM_DISMISS_ACTION = "com.android.deskclock.ALARM_DISMISS";
    private static final String ALARM_SNOOZE_ACTION = "com.android.deskclock.ALARM_SNOOZE";
    private static final String ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE";

    // Alarm action
    private static final int ACTION_NONE = 0;
    private static final int ACTION_DISMISS = 1;
    private static final int ACTION_SNOOZE = 2;

    public IrSilencer(LineageActionsSettings lineageActionsSettings, Context context,
                SensorHelper sensorHelper, IrGestureManager irGestureManager) {
        mContext = context;
        mTelecomManager = (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        mLineageActionsSettings = lineageActionsSettings;
        mSensorHelper = sensorHelper;
        mSensor = sensorHelper.getIrGestureSensor();
        mIrGestureVote = new IrGestureVote(irGestureManager);
        mIrGestureVote.voteForSensors(0);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_ALERT_ACTION);
        intentFilter.addAction(ALARM_DISMISS_ACTION);
        intentFilter.addAction(ALARM_SNOOZE_ACTION);
        intentFilter.addAction(ALARM_DONE_ACTION);
        mContext.registerReceiver(mAlarmStateReceiver, intentFilter);
    }

    @Override
    public void updateState() {
        if (mLineageActionsSettings.isIrSilencerEnabled()) {
            mTelephonyManager.listen(this, LISTEN_CALL_STATE);
            mAlarmAction = mLineageActionsSettings.getWaveAlarmAction();
        } else {
            mTelephonyManager.listen(this, 0);
            // make sure we're disabled
            mPhoneRinging = false;
            mAlarmRinging = false;
        }
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        int gesture = (int) event.values[1];

        if (gesture == IR_GESTURE_SWIPE || gesture == IR_GESTURE_APPROACH) {
            if (mPhoneRinging) {
                long now = System.currentTimeMillis();
                if (now - mPhoneRingStartedMs >= SILENCE_DELAY_MS) {
                    Log.d(TAG, "Silencing ringer");
                    mTelecomManager.silenceRinger();
                } else {
                    Log.d(TAG, "Ignoring silence gesture: " + now + " is too close to " +
                            mPhoneRingStartedMs + ", delay=" + SILENCE_DELAY_MS);
                }
            } else if (mAlarmRinging) {
                long now = System.currentTimeMillis();
                if (now - mAlarmRingStartedMs >= SILENCE_DELAY_MS) {
                    if (mAlarmAction == ACTION_DISMISS) {
                        Log.d(TAG, "Dismissing alarm in case of ACTION_DISMISS");
                        mContext.sendBroadcast(new Intent(ALARM_DISMISS_ACTION));
                    } else if (mAlarmAction == ACTION_SNOOZE) {
                        Log.d(TAG, "Snoozing alarm in case of ACTION_SNOOZE");
                        mContext.sendBroadcast(new Intent(ALARM_SNOOZE_ACTION));
                    } else {
                        Log.d(TAG, "Alarm Snoozing is set ACTION_NONE so do nothing");
                    }
                } else {
                    Log.d(TAG, "Ignoring silence gesture: " + now + " is too close to " +
                            mAlarmRingStartedMs + ", delay=" + SILENCE_DELAY_MS);
                }
            }
        }
    }

    @Override
    public synchronized void onCallStateChanged(int state, String incomingNumber) {
        if (state == CALL_STATE_RINGING && !mPhoneRinging) {
            Log.d(TAG, "Phone ringing started");
            mSensorHelper.registerListener(mSensor, this);
            mIrGestureVote.voteForSensors(IR_GESTURES_FOR_RINGING);
            mPhoneRinging = true;
            mPhoneRingStartedMs = System.currentTimeMillis();
        } else if (state != CALL_STATE_RINGING && mPhoneRinging) {
            Log.d(TAG, "Phone ringing stopped");
            mSensorHelper.unregisterListener(this);
            mIrGestureVote.voteForSensors(0);
            mPhoneRinging = false;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor mSensor, int accuracy) {
    }

    private BroadcastReceiver mAlarmStateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
           if (!mLineageActionsSettings.isIrSilencerEnabled())
              return;

           String action = intent.getAction();
           if (ALARM_ALERT_ACTION.equals(action)) {
               Log.d(TAG, "Alarm ringing started");
               mSensorHelper.registerListener(mSensor, IrSilencer.this);
               mIrGestureVote.voteForSensors(IR_GESTURES_FOR_RINGING);
               mAlarmRinging = true;
               mAlarmRingStartedMs = System.currentTimeMillis();
           } else {
               Log.d(TAG, "Alarm ringing stopped");
               mSensorHelper.unregisterListener(IrSilencer.this);
               mIrGestureVote.voteForSensors(0);
               mAlarmRinging = false;
           }
        }
    };
}
