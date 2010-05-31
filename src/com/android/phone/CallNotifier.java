/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.phone;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaDisplayInfoRec;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.Checkin;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import android.preference.PreferenceManager;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;
import android.hardware.Sensor;


/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Ringer and Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class CallNotifier extends Handler
        implements CallerInfoAsyncQuery.OnQueryCompleteListener {
    private static final String LOG_TAG = "CallNotifier";
    private static final boolean DBG =
        (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);

    // Strings used with Checkin.logEvent().
    private static final String PHONE_UI_EVENT_RINGER_QUERY_ELAPSED =
        "using default incoming call behavior";
    private static final String PHONE_UI_EVENT_MULTIPLE_QUERY =
        "multiple incoming call queries attempted";

    // Maximum time we allow the CallerInfo query to run,
    // before giving up and falling back to the default ringtone.
    private static final int RINGTONE_QUERY_WAIT_TIME = 500;  // msec

    // Timers related to CDMA Call Waiting
    // 1) For displaying Caller Info
    // 2) For disabling "Add Call" menu option once User selects Ignore or CW Timeout occures
    private static final int CALLWAITING_CALLERINFO_DISPLAY_TIME = 20000; // msec
    private static final int CALLWAITING_ADDCALL_DISABLE_TIME = 30000; // msec

    // Time to display the  DisplayInfo Record sent by CDMA network
    private static final int DISPLAYINFO_NOTIFICATION_TIME = 2000; // msec

    // Boolean to store information if a Call Waiting timed out
    private boolean mCallWaitingTimeOut = false;

    // values used to track the query state
    private static final int CALLERINFO_QUERY_READY = 0;
    private static final int CALLERINFO_QUERYING = -1;

    // the state of the CallerInfo Query.
    private int mCallerInfoQueryState;

    // object used to synchronize access to mCallerInfoQueryState
    private Object mCallerInfoQueryStateGuard = new Object();

    // Event used to indicate a query timeout.
    private static final int RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT = 100;

    // Events from the Phone object:
    private static final int PHONE_STATE_CHANGED = 1;
    private static final int PHONE_NEW_RINGING_CONNECTION = 2;
    private static final int PHONE_DISCONNECT = 3;
    private static final int PHONE_UNKNOWN_CONNECTION_APPEARED = 4;
    private static final int PHONE_INCOMING_RING = 5;
    private static final int PHONE_STATE_DISPLAYINFO = 6;
    private static final int PHONE_STATE_SIGNALINFO = 7;
    private static final int PHONE_CDMA_CALL_WAITING = 8;
    private static final int PHONE_ENHANCED_VP_ON = 9;
    private static final int PHONE_ENHANCED_VP_OFF = 10;

    // Events generated internally:
    private static final int PHONE_MWI_CHANGED = 11;
    private static final int PHONE_BATTERY_LOW = 12;
    private static final int CALLWAITING_CALLERINFO_DISPLAY_DONE = 13;
    private static final int CALLWAITING_ADDCALL_DISABLE_TIMEOUT = 14;
    private static final int DISPLAYINFO_NOTIFICATION_DONE = 15;
    private static final int EVENT_OTA_PROVISION_CHANGE = 16;

    private static final int PHONE_RINGBACK_TONE = 17;
    private static final int PHONE_RESEND_MUTE = 18;

    // Emergency call related defines:
    private static final int EMERGENCY_TONE_OFF = 0;
    private static final int EMERGENCY_TONE_ALERT = 1;
    private static final int EMERGENCY_TONE_VIBRATE = 2;

    // Ringback tone state
    private static final int RINGBACK_TONE_ON = 1;
    private static final int RINGBACK_TONE_OFF = 2;

    private PhoneApp mApplication;
    private Phone mPhone;
    private Ringer mRinger;
    private BluetoothHandsfree mBluetoothHandsfree;
    private boolean mSilentRingerRequested;

    // ToneGenerator instance for playing SignalInfo tones
    private ToneGenerator mSignalInfoToneGenerator;

    // The tone volume relative to other sounds in the stream SignalInfo
    private static final int TONE_RELATIVE_VOLUME_SIGNALINFO = 80;

    private Call.State mPreviousCdmaCallState;
    private boolean mCdmaVoicePrivacyState = false;
    private boolean mIsCdmaRedialCall = false;

    // Emergency call tone and vibrate:
    private int mIsEmergencyToneOn;
    private int mCurrentEmergencyToneState = EMERGENCY_TONE_OFF;
    private EmergencyTonePlayerVibrator mEmergencyTonePlayerVibrator;

    // Ringback tone player
    private int mCurrentRingbackToneState = RINGBACK_TONE_OFF;
    private InCallRingbackTonePlayer mInCallRingbackTonePlayer;

    // add by cytown
    private CallFeaturesSetting mSettings;
    private static final String BLACKLIST = "blacklist";

    public CallNotifier(PhoneApp app, Phone phone, Ringer ringer,
            BluetoothHandsfree btMgr) {
        mSettings = CallFeaturesSetting.getInstance(PreferenceManager.getDefaultSharedPreferences(app));
        mSensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        mApplication = app;

        mPhone = phone;

        mPhone.registerForNewRingingConnection(this, PHONE_NEW_RINGING_CONNECTION, null);
        mPhone.registerForPreciseCallStateChanged(this, PHONE_STATE_CHANGED, null);
        mPhone.registerForDisconnect(this, PHONE_DISCONNECT, null);
        mPhone.registerForUnknownConnection(this, PHONE_UNKNOWN_CONNECTION_APPEARED, null);
        mPhone.registerForIncomingRing(this, PHONE_INCOMING_RING, null);

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            mPhone.registerForCdmaOtaStatusChange(this, EVENT_OTA_PROVISION_CHANGE, null);

            if (DBG) log("Registering for Call Waiting, Signal and Display Info.");
            mPhone.registerForCallWaiting(this, PHONE_CDMA_CALL_WAITING, null);
            mPhone.registerForDisplayInfo(this, PHONE_STATE_DISPLAYINFO, null);
            mPhone.registerForSignalInfo(this, PHONE_STATE_SIGNALINFO, null);
            mPhone.registerForInCallVoicePrivacyOn(this, PHONE_ENHANCED_VP_ON, null);
            mPhone.registerForInCallVoicePrivacyOff(this, PHONE_ENHANCED_VP_OFF, null);

            // Instantiate the ToneGenerator for SignalInfo and CallWaiting
            // TODO(Moto): We probably dont need the mSignalInfoToneGenerator instance
            // around forever. Need to change it so as to create a ToneGenerator instance only
            // when a tone is being played and releases it after its done playing.
            try {
                mSignalInfoToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
                        TONE_RELATIVE_VOLUME_SIGNALINFO);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "CallNotifier: Exception caught while creating " +
                        "mSignalInfoToneGenerator: " + e);
                mSignalInfoToneGenerator = null;
            }
        }

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_GSM) {
            mPhone.registerForRingbackTone(this, PHONE_RINGBACK_TONE, null);
            mPhone.registerForResendIncallMute(this, PHONE_RESEND_MUTE, null);
        }

        mRinger = ringer;
        mBluetoothHandsfree = btMgr;

        TelephonyManager telephonyManager = (TelephonyManager)app.getSystemService(
                Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                | PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case PHONE_NEW_RINGING_CONNECTION:
                if (DBG) log("RINGING... (new)");
                onNewRingingConnection((AsyncResult) msg.obj);
                mSilentRingerRequested = false;
                break;

            case PHONE_INCOMING_RING:
                // repeat the ring when requested by the RIL, and when the user has NOT
                // specifically requested silence.
                if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                    PhoneBase pb =  (PhoneBase)((AsyncResult)msg.obj).result;

                    if ((pb.getState() == Phone.State.RINGING)
                            && (mSilentRingerRequested == false)) {
                        if (DBG) log("RINGING... (PHONE_INCOMING_RING event)");
                        mRinger.ring();
                    } else {
                        if (DBG) log("RING before NEW_RING, skipping");
                    }
                }
                break;

            case PHONE_STATE_CHANGED:
                onPhoneStateChanged((AsyncResult) msg.obj);
                break;

            case PHONE_DISCONNECT:
                if (DBG) log("DISCONNECT");
                onDisconnect((AsyncResult) msg.obj);
                break;

            case PHONE_UNKNOWN_CONNECTION_APPEARED:
                onUnknownConnectionAppeared((AsyncResult) msg.obj);
                break;

            case RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT:
                // CallerInfo query is taking too long!  But we can't wait
                // any more, so start ringing NOW even if it means we won't
                // use the correct custom ringtone.
                Log.w(LOG_TAG, "CallerInfo query took too long; manually starting ringer");

                // In this case we call onCustomRingQueryComplete(), just
                // like if the query had completed normally.  (But we're
                // going to get the default ringtone, since we never got
                // the chance to call Ringer.setCustomRingtoneUri()).
                onCustomRingQueryComplete();
                break;

            case PHONE_MWI_CHANGED:
                onMwiChanged(mPhone.getMessageWaitingIndicator());
                break;

            case PHONE_BATTERY_LOW:
                onBatteryLow();
                break;

            case PHONE_CDMA_CALL_WAITING:
                if (DBG) log("Received PHONE_CDMA_CALL_WAITING event");
                onCdmaCallWaiting((AsyncResult) msg.obj);
                break;

            case CALLWAITING_CALLERINFO_DISPLAY_DONE:
                if (DBG) log("Received CALLWAITING_CALLERINFO_DISPLAY_DONE event ...");
                mCallWaitingTimeOut = true;
                onCdmaCallWaitingReject();
                break;

            case CALLWAITING_ADDCALL_DISABLE_TIMEOUT:
                if (DBG) log("Received CALLWAITING_ADDCALL_DISABLE_TIMEOUT event ...");
                // Set the mAddCallMenuStateAfterCW state to true
                mApplication.cdmaPhoneCallState.setAddCallMenuStateAfterCallWaiting(true);
                mApplication.updateInCallScreenTouchUi();
                break;

            case PHONE_STATE_DISPLAYINFO:
                if (DBG) log("Received PHONE_STATE_DISPLAYINFO event");
                onDisplayInfo((AsyncResult) msg.obj);
                break;

            case PHONE_STATE_SIGNALINFO:
                if (DBG) log("Received PHONE_STATE_SIGNALINFO event");
                onSignalInfo((AsyncResult) msg.obj);
                break;

            case DISPLAYINFO_NOTIFICATION_DONE:
                if (DBG) log("Received Display Info notification done event ...");
                CdmaDisplayInfo.dismissDisplayInfoRecord();
                break;

            case EVENT_OTA_PROVISION_CHANGE:
                mApplication.handleOtaEvents(msg);
                break;

            case PHONE_ENHANCED_VP_ON:
                if (DBG) log("PHONE_ENHANCED_VP_ON...");
                if (!mCdmaVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mCdmaVoicePrivacyState = true;
                    // Update the VP icon:
                    NotificationMgr.getDefault().updateInCallNotification();
                }
                break;

            case PHONE_ENHANCED_VP_OFF:
                if (DBG) log("PHONE_ENHANCED_VP_OFF...");
                if (mCdmaVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mCdmaVoicePrivacyState = false;
                    // Update the VP icon:
                    NotificationMgr.getDefault().updateInCallNotification();
                }
                break;

            case PHONE_RINGBACK_TONE:
                onRingbackTone((AsyncResult) msg.obj);
                break;

            case PHONE_RESEND_MUTE:
                onResendMute();
                break;

            default:
                // super.handleMessage(msg);
        }
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onMessageWaitingIndicatorChanged(boolean mwi) {
            onMwiChanged(mwi);
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean cfi) {
            onCfiChanged(cfi);
        }
    };

    private void onNewRingingConnection(AsyncResult r) {
        Connection c = (Connection) r.result;
        if (DBG) log("onNewRingingConnection(): " + c);

        // Incoming calls are totally ignored if the device isn't provisioned yet
        boolean provisioned = Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                Settings.Secure.DEVICE_PROVISIONED, 0) != 0;
        if (!provisioned) {
            Log.i(LOG_TAG, "CallNotifier: rejecting incoming call: device isn't provisioned");
            // Send the caller straight to voicemail, just like
            // "rejecting" an incoming call.
            PhoneUtils.hangupRingingCall(mPhone);
            return;
        }

        String number = c!=null?c.getAddress():"0000";
        if (android.text.TextUtils.isEmpty(number)) number = "0000";
        if (DBG) log("incoming number is: " + number);
        if (c != null && mSettings.isBlackList(number)) {
            try {
                c.setUserData(BLACKLIST);
                c.hangup();
                if (DBG) Log.i(LOG_TAG, "Reject the incoming call in BL:" + number);
            } catch (Exception e) {}  // ignore
            return;
        }

        // Incoming calls are totally ignored if OTA call is active
        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            boolean activateState = (mApplication.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION);
            boolean dialogState = (mApplication.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_SUCCESS_FAILURE_DLG);
            boolean spcState = mApplication.cdmaOtaProvisionData.inOtaSpcState;

            if (spcState) {
                Log.i(LOG_TAG, "CallNotifier: rejecting incoming call: OTA call is active");
                PhoneUtils.hangupRingingCall(mPhone);
                return;
            } else if (activateState || dialogState) {
                if (dialogState) mApplication.dismissOtaDialogs();
                mApplication.clearOtaState();
                mApplication.clearInCallScreenMode();
            }
        }

        if (c != null && c.isRinging()) {
            // Stop any signalInfo tone being played on receiving a Call
            if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                stopSignalInfoTone();
            }

            Call.State state = c.getState();
            // State will be either INCOMING or WAITING.
            if (VDBG) log("- connection is ringing!  state = " + state);
            // if (DBG) PhoneUtils.dumpCallState(mPhone);

            // No need to do any service state checks here (like for
            // "emergency mode"), since in those states the SIM won't let
            // us get incoming connections in the first place.

            // TODO: Consider sending out a serialized broadcast Intent here
            // (maybe "ACTION_NEW_INCOMING_CALL"), *before* starting the
            // ringer and going to the in-call UI.  The intent should contain
            // the caller-id info for the current connection, and say whether
            // it would be a "call waiting" call or a regular ringing call.
            // If anybody consumed the broadcast, we'd bail out without
            // ringing or bringing up the in-call UI.
            //
            // This would give 3rd party apps a chance to listen for (and
            // intercept) new ringing connections.  An app could reject the
            // incoming call by consuming the broadcast and doing nothing, or
            // it could "pick up" the call (without any action by the user!)
            // by firing off an ACTION_ANSWER intent.
            //
            // We'd need to protect this with a new "intercept incoming calls"
            // system permission.

            // Obtain a partial wake lock to make sure the CPU doesn't go to
            // sleep before we finish bringing up the InCallScreen.
            // (This will be upgraded soon to a full wake lock; see
            // PhoneUtils.showIncomingCallUi().)
            if (VDBG) log("Holding wake lock on new incoming connection.");
            mApplication.requestWakeState(PhoneApp.WakeState.PARTIAL);

            // - don't ring for call waiting connections
            // - do this before showing the incoming call panel
            if (state == Call.State.INCOMING) {
                PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_RINGING);
                startIncomingCallQuery(c);
                startSensor();
            } else {
                if (mSettings.mVibCallWaiting) {
                    mApplication.vibrate(200,300,500);
                }
                if (VDBG) log("- starting call waiting tone...");
                new InCallTonePlayer(InCallTonePlayer.TONE_CALL_WAITING).start();
                // The InCallTonePlayer will automatically stop playing (and
                // clean itself up) after playing the tone.

                // TODO: alternatively, consider starting an
                // InCallTonePlayer with an "unlimited" tone length, and
                // manually stop it later when the ringing call either (a)
                // gets answered, or (b) gets disconnected.

                // in this case, just fall through like before, and call
                // PhoneUtils.showIncomingCallUi
                PhoneUtils.showIncomingCallUi();
            }
        }

        if (VDBG) log("- onNewRingingConnection() done.");
    }

    /**
     * Helper method to manage the start of incoming call queries
     */
    private void startIncomingCallQuery(Connection c) {
        // TODO: cache the custom ringer object so that subsequent
        // calls will not need to do this query work.  We can keep
        // the MRU ringtones in memory.  We'll still need to hit
        // the database to get the callerinfo to act as a key,
        // but at least we can save the time required for the
        // Media player setup.  The only issue with this is that
        // we may need to keep an eye on the resources the Media
        // player uses to keep these ringtones around.

        // make sure we're in a state where we can be ready to
        // query a ringtone uri.
        boolean shouldStartQuery = false;
        synchronized (mCallerInfoQueryStateGuard) {
            if (mCallerInfoQueryState == CALLERINFO_QUERY_READY) {
                mCallerInfoQueryState = CALLERINFO_QUERYING;
                shouldStartQuery = true;
            }
        }
        if (shouldStartQuery) {
            // create a custom ringer using the default ringer first
            mRinger.setCustomRingtoneUri(Settings.System.DEFAULT_RINGTONE_URI);

            // query the callerinfo to try to get the ringer.
            PhoneUtils.CallerInfoToken cit = PhoneUtils.startGetCallerInfo(
                    mPhone.getContext(), c, this, this);

            // if this has already been queried then just ring, otherwise
            // we wait for the alloted time before ringing.
            if (cit.isFinal) {
                if (VDBG) log("- CallerInfo already up to date, using available data");
                onQueryComplete(0, this, cit.currentInfo);
            } else {
                if (VDBG) log("- Starting query, posting timeout message.");
                sendEmptyMessageDelayed(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT,
                        RINGTONE_QUERY_WAIT_TIME);
            }
            // calls to PhoneUtils.showIncomingCallUi will come after the
            // queries are complete (or timeout).
        } else {
            // This should never happen; its the case where an incoming call
            // arrives at the same time that the query is still being run,
            // and before the timeout window has closed.
            Checkin.logEvent(mPhone.getContext().getContentResolver(),
                    Checkin.Events.Tag.PHONE_UI,
                    PHONE_UI_EVENT_MULTIPLE_QUERY);

            // In this case, just log the request and ring.
            if (VDBG) log("RINGING... (request to ring arrived while query is running)");
            mRinger.ring();

            // in this case, just fall through like before, and call
            // PhoneUtils.showIncomingCallUi
            PhoneUtils.showIncomingCallUi();
        }
    }

    /**
     * Performs the final steps of the onNewRingingConnection sequence:
     * starts the ringer, and launches the InCallScreen to show the
     * "incoming call" UI.
     *
     * Normally, this is called when the CallerInfo query completes (see
     * onQueryComplete()).  In this case, onQueryComplete() has already
     * configured the Ringer object to use the custom ringtone (if there
     * is one) for this caller.  So we just tell the Ringer to start, and
     * proceed to the InCallScreen.
     *
     * But this method can *also* be called if the
     * RINGTONE_QUERY_WAIT_TIME timeout expires, which means that the
     * CallerInfo query is taking too long.  In that case, we log a
     * warning but otherwise we behave the same as in the normal case.
     * (We still tell the Ringer to start, but it's going to use the
     * default ringtone.)
     */
    private void onCustomRingQueryComplete() {
        boolean isQueryExecutionTimeExpired = false;
        synchronized (mCallerInfoQueryStateGuard) {
            if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                isQueryExecutionTimeExpired = true;
            }
        }
        if (isQueryExecutionTimeExpired) {
            // There may be a problem with the query here, since the
            // default ringtone is playing instead of the custom one.
            Log.w(LOG_TAG, "CallerInfo query took too long; falling back to default ringtone");
            Checkin.logEvent(mPhone.getContext().getContentResolver(),
                    Checkin.Events.Tag.PHONE_UI,
                    PHONE_UI_EVENT_RINGER_QUERY_ELAPSED);
        }

        // Make sure we still have an incoming call!
        //
        // (It's possible for the incoming call to have been disconnected
        // while we were running the query.  In that case we better not
        // start the ringer here, since there won't be any future
        // DISCONNECT event to stop it!)
        //
        // Note we don't have to worry about the incoming call going away
        // *after* this check but before we call mRinger.ring() below,
        // since in that case we *will* still get a DISCONNECT message sent
        // to our handler.  (And we will correctly stop the ringer when we
        // process that event.)
        if (mPhone.getState() != Phone.State.RINGING) {
            Log.i(LOG_TAG, "onCustomRingQueryComplete: No incoming call! Bailing out...");
            // Don't start the ringer *or* bring up the "incoming call" UI.
            // Just bail out.
            return;
        }

        // Ring, either with the queried ringtone or default one.
        if (VDBG) log("RINGING... (onCustomRingQueryComplete)");
        mRinger.ring();

        // ...and show the InCallScreen.
        PhoneUtils.showIncomingCallUi();
    }

    private void onUnknownConnectionAppeared(AsyncResult r) {
        Phone.State state = mPhone.getState();

        if (state == Phone.State.OFFHOOK) {
            // basically do onPhoneStateChanged + displayCallScreen
            onPhoneStateChanged(r);
            PhoneUtils.showIncomingCallUi();
        }
    }

    private void onPhoneStateChanged(AsyncResult r) {
        Phone.State state = mPhone.getState();
        if (VDBG) log("onPhoneStateChanged: state = " + state);

        // Turn status bar notifications on or off depending upon the state
        // of the phone.  Notification Alerts (audible or vibrating) should
        // be on if and only if the phone is IDLE.
        NotificationMgr.getDefault().getStatusBarMgr()
        .enableNotificationAlerts(state == Phone.State.IDLE);

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            if ((mPhone.getForegroundCall().getState() == Call.State.ACTIVE)
                    && ((mPreviousCdmaCallState == Call.State.DIALING)
                            ||  (mPreviousCdmaCallState == Call.State.ALERTING))) {
                if (mIsCdmaRedialCall) {
                    int toneToPlay = InCallTonePlayer.TONE_REDIAL;
                    new InCallTonePlayer(toneToPlay).start();
                }
                // Stop any signal info tone when call moves to ACTIVE state
                stopSignalInfoTone();
            }
            mPreviousCdmaCallState = mPhone.getForegroundCall().getState();
        }

        // Have the PhoneApp recompute its mShowBluetoothIndication
        // flag based on the (new) telephony state.
        // There's no need to force a UI update since we update the
        // in-call notification ourselves (below), and the InCallScreen
        // listens for phone state changes itself.
        mApplication.updateBluetoothIndication(false);

        // Update the proximity sensor mode (on devices that have a
        // proximity sensor).
        mApplication.updatePhoneState(state);

        if (state == Phone.State.OFFHOOK) {
            PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_OFFHOOK);
            if (VDBG) log("onPhoneStateChanged: OFF HOOK");

            Call call = PhoneUtils.getCurrentCall(mPhone);
            Connection c = PhoneUtils.getConnection(mPhone, call);
            if (VDBG) PhoneUtils.dumpCallState(mPhone);
            Call.State cstate = call.getState();
            if (cstate == Call.State.ACTIVE && !c.isIncoming()) {
                long callDurationMsec = c.getDurationMillis();
                if (VDBG) Log.i(LOG_TAG, "duration is " + callDurationMsec);
                if (mSettings.mVibOutgoing && callDurationMsec < 200) {
                    mApplication.vibrate(100,0,0);
                }
                if (mSettings.mVib45) {
                    callDurationMsec = callDurationMsec % 60000;
                    mApplication.startVib45(callDurationMsec);
                }
            }

            // If Audio Mode is not In Call, then set the Audio Mode.  This
            // changes is needed because for one of the carrier specific test case,
            // call is originated from the lower layer without using the UI, and
            // since calling does not go through DIALING state, it skips the steps
            // of setting the Audio Mode
            if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                AudioManager audioManager =
                    (AudioManager) mPhone.getContext().getSystemService(Context.AUDIO_SERVICE);
                if (audioManager.getMode() != AudioManager.MODE_IN_CALL) {
                    PhoneUtils.setAudioMode(mPhone.getContext(), AudioManager.MODE_IN_CALL);
                }
            }

            // if the call screen is showing, let it handle the event,
            // otherwise handle it here.
            if (!mApplication.isShowingCallScreen()) {
                mApplication.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);
                mApplication.requestWakeState(PhoneApp.WakeState.SLEEP);
            }

            // Since we're now in-call, the Ringer should definitely *not*
            // be ringing any more.  (This is just a sanity-check; we
            // already stopped the ringer explicitly back in
            // PhoneUtils.answerCall(), before the call to phone.acceptCall().)
            // TODO: Confirm that this call really *is* unnecessary, and if so,
            // remove it!
            if (DBG) log("stopRing()... (OFFHOOK state)");
            mRinger.stopRing();

            // put a icon in the status bar
            NotificationMgr.getDefault().updateInCallNotification();
        }

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            Connection c = mPhone.getForegroundCall().getLatestConnection();
            if ((c != null) && (PhoneNumberUtils.isEmergencyNumber(c.getAddress()))) {
                if (VDBG) log("onPhoneStateChanged: it is an emergency call.");
                Call.State callState = mPhone.getForegroundCall().getState();
                if (mEmergencyTonePlayerVibrator == null) {
                    mEmergencyTonePlayerVibrator = new EmergencyTonePlayerVibrator();
                }

                if (callState == Call.State.DIALING || callState == Call.State.ALERTING) {
                    mIsEmergencyToneOn = Settings.System.getInt(
                            mPhone.getContext().getContentResolver(),
                            Settings.System.EMERGENCY_TONE, EMERGENCY_TONE_OFF);
                    if (mIsEmergencyToneOn != EMERGENCY_TONE_OFF &&
                            mCurrentEmergencyToneState == EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.start();
                        }
                    }
                } else if (callState == Call.State.ACTIVE) {
                    if (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.stop();
                        }
                    }
                }
            }
        }

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_GSM) {
            Call.State callState = mPhone.getForegroundCall().getState();
            if (!callState.isDialing()) {
                // If call get activated or disconnected before the ringback
                // tone stops, we have to stop it to prevent disturbing.
                if (mInCallRingbackTonePlayer != null &&
                        mCurrentRingbackToneState == RINGBACK_TONE_ON) {
                    mInCallRingbackTonePlayer.stop();
                }
            }
        }
    }

    void updateCallNotifierRegistrationsAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateCallNotifierRegistrationsAfterRadioTechnologyChange...");
        // Unregister all events from the old obsolete phone
        mPhone.unregisterForNewRingingConnection(this);
        mPhone.unregisterForPreciseCallStateChanged(this);
        mPhone.unregisterForDisconnect(this);
        mPhone.unregisterForUnknownConnection(this);
        mPhone.unregisterForIncomingRing(this);
        mPhone.unregisterForCallWaiting(this);
        mPhone.unregisterForDisplayInfo(this);
        mPhone.unregisterForSignalInfo(this);
        mPhone.unregisterForCdmaOtaStatusChange(this);
        mPhone.unregisterForRingbackTone(this);
        mPhone.unregisterForResendIncallMute(this);

        // Release the ToneGenerator used for playing SignalInfo and CallWaiting
        if (mSignalInfoToneGenerator != null) {
            mSignalInfoToneGenerator.release();
        }

        // Clear ringback tone player
        mInCallRingbackTonePlayer = null;

        mPhone.unregisterForInCallVoicePrivacyOn(this);
        mPhone.unregisterForInCallVoicePrivacyOff(this);

        // Register all events new to the new active phone
        mPhone.registerForNewRingingConnection(this, PHONE_NEW_RINGING_CONNECTION, null);
        mPhone.registerForPreciseCallStateChanged(this, PHONE_STATE_CHANGED, null);
        mPhone.registerForDisconnect(this, PHONE_DISCONNECT, null);
        mPhone.registerForUnknownConnection(this, PHONE_UNKNOWN_CONNECTION_APPEARED, null);
        mPhone.registerForIncomingRing(this, PHONE_INCOMING_RING, null);
        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            if (DBG) log("Registering for Call Waiting, Signal and Display Info.");
            mPhone.registerForCallWaiting(this, PHONE_CDMA_CALL_WAITING, null);
            mPhone.registerForDisplayInfo(this, PHONE_STATE_DISPLAYINFO, null);
            mPhone.registerForSignalInfo(this, PHONE_STATE_SIGNALINFO, null);
            mPhone.registerForCdmaOtaStatusChange(this, EVENT_OTA_PROVISION_CHANGE, null);

            // Instantiate the ToneGenerator for SignalInfo
            try {
                mSignalInfoToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
                        TONE_RELATIVE_VOLUME_SIGNALINFO);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "CallNotifier: Exception caught while creating " +
                        "mSignalInfoToneGenerator: " + e);
                mSignalInfoToneGenerator = null;
            }

            mPhone.registerForInCallVoicePrivacyOn(this, PHONE_ENHANCED_VP_ON, null);
            mPhone.registerForInCallVoicePrivacyOff(this, PHONE_ENHANCED_VP_OFF, null);
        }

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_GSM) {
            mPhone.registerForRingbackTone(this, PHONE_RINGBACK_TONE, null);
            mPhone.registerForResendIncallMute(this, PHONE_RESEND_MUTE, null);
        }
    }

    /**
     * Implemented for CallerInfoAsyncQuery.OnQueryCompleteListener interface.
     * refreshes the CallCard data when it called.  If called with this
     * class itself, it is assumed that we have been waiting for the ringtone
     * and direct to voicemail settings to update.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
        if (cookie instanceof Long) {
            if (VDBG) log("CallerInfo query complete, posting missed call notification");

            NotificationMgr.getDefault().notifyMissedCall(ci.name, ci.phoneNumber,
                    ci.phoneLabel, ((Long) cookie).longValue());
        } else if (cookie instanceof CallNotifier) {
            if (VDBG) log("CallerInfo query complete, updating data");

            // get rid of the timeout messages
            removeMessages(RINGER_CUSTOM_RINGTONE_QUERY_TIMEOUT);

            boolean isQueryExecutionTimeOK = false;
            synchronized (mCallerInfoQueryStateGuard) {
                if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                    mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                    isQueryExecutionTimeOK = true;
                }
            }
            //if we're in the right state
            if (isQueryExecutionTimeOK) {

                // send directly to voicemail.
                if (ci.shouldSendToVoicemail) {
                    if (DBG) log("send to voicemail flag detected. hanging up.");
                    PhoneUtils.hangupRingingCall(mPhone);
                    return;
                }

                // set the ringtone uri to prepare for the ring.
                if (ci.contactRingtoneUri != null) {
                    if (DBG) log("custom ringtone found, setting up ringer.");
                    Ringer r = ((CallNotifier) cookie).mRinger;
                    r.setCustomRingtoneUri(ci.contactRingtoneUri);
                }
                // ring, and other post-ring actions.
                onCustomRingQueryComplete();
            }
        }
    }

    private void onDisconnect(AsyncResult r) {
        if (VDBG) log("onDisconnect()...  phone state: " + mPhone.getState());

        mCdmaVoicePrivacyState = false;
        int autoretrySetting = 0;
        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            autoretrySetting = android.provider.Settings.System.getInt(mPhone.getContext().
                    getContentResolver(),android.provider.Settings.System.CALL_AUTO_RETRY, 0);
        }

        if (mPhone.getState() == Phone.State.IDLE) {
            PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_IDLE);
        }

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            // Stop any signalInfo tone being played when a call gets ended
            stopSignalInfoTone();

            // Resetting the CdmaPhoneCallState members
            mApplication.cdmaPhoneCallState.resetCdmaPhoneCallState();

            // Remove Call waiting timers
            removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
            removeMessages(CALLWAITING_ADDCALL_DISABLE_TIMEOUT);
        }

        Connection c = (Connection) r.result;
        if (DBG && c != null) {
            log("- onDisconnect: cause = " + c.getDisconnectCause()
                    + ", incoming = " + c.isIncoming()
                    + ", date = " + c.getCreateTime());
        }

        if (c != null) {
            Object o = c.getUserData();
            if (BLACKLIST.equals(o)) {
                if (VDBG) Log.i(LOG_TAG, "in blacklist so skip calllog");
                return;
            }
            if (c.getDurationMillis() > 0 && mSettings.mVibHangup) {
                mApplication.vibrate(50, 100, 50);
            }
            if (!c.isIncoming()) {
                mApplication.stopVib45();
            }
        }

        // Stop the ringer if it was ringing (for an incoming call that
        // either disconnected by itself, or was rejected by the user.)
        //
        // TODO: We technically *shouldn't* stop the ringer if the
        // foreground or background call disconnects while an incoming call
        // is still ringing, but that's a really rare corner case.
        // It's safest to just unconditionally stop the ringer here.

        // CDMA: For Call collision cases i.e. when the user makes an out going call
        // and at the same time receives an Incoming Call, the Incoming Call is given
        // higher preference. At this time framework sends a disconnect for the Out going
        // call connection hence we should *not* be stopping the ringer being played for
        // the Incoming Call
        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            if (mPhone.getRingingCall().getState() == Call.State.INCOMING) {
                // Also we need to take off the "In Call" icon from the Notification
                // area as the Out going Call never got connected
                if (DBG) log("cancelCallInProgressNotification()... (onDisconnect)");
                NotificationMgr.getDefault().cancelCallInProgressNotification();
            } else {
                if (DBG) log("stopRing()... (onDisconnect)");
                mRinger.stopRing();
            }
        } else { // GSM
            if (DBG) log("stopRing()... (onDisconnect)");
            mRinger.stopRing();
        }

        // Check for the various tones we might need to play (thru the
        // earpiece) after a call disconnects.
        int toneToPlay = InCallTonePlayer.TONE_NONE;

        // The "Busy" or "Congestion" tone is the highest priority:
        if (c != null) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if (cause == Connection.DisconnectCause.BUSY) {
                if (DBG) log("- need to play BUSY tone!");
                toneToPlay = InCallTonePlayer.TONE_BUSY;
            } else if (cause == Connection.DisconnectCause.CONGESTION) {
                if (DBG) log("- need to play CONGESTION tone!");
                toneToPlay = InCallTonePlayer.TONE_CONGESTION;
            } else if (((cause == Connection.DisconnectCause.NORMAL)
                    || (cause == Connection.DisconnectCause.LOCAL))
                    && (mApplication.isOtaCallInActiveState())) {
                if (DBG) log("- need to play OTA_CALL_END tone!");
                toneToPlay = InCallTonePlayer.TONE_OTA_CALL_END;
            } else if (cause == Connection.DisconnectCause.CDMA_REORDER) {
                if (DBG) log("- need to play CDMA_REORDER tone!");
                toneToPlay = InCallTonePlayer.TONE_REORDER;
            } else if (cause == Connection.DisconnectCause.CDMA_INTERCEPT) {
                if (DBG) log("- need to play CDMA_INTERCEPT tone!");
                toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
            } else if (cause == Connection.DisconnectCause.CDMA_DROP) {
                if (DBG) log("- need to play CDMA_DROP tone!");
                toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
            } else if (cause == Connection.DisconnectCause.OUT_OF_SERVICE) {
                if (DBG) log("- need to play OUT OF SERVICE tone!");
                toneToPlay = InCallTonePlayer.TONE_OUT_OF_SERVICE;
            } else if (cause == Connection.DisconnectCause.ERROR_UNSPECIFIED) {
                if (DBG) log("- DisconnectCause is ERROR_UNSPECIFIED: play TONE_CALL_ENDED!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
            }
        }

        // If we don't need to play BUSY or CONGESTION, then play the
        // "call ended" tone if this was a "regular disconnect" (i.e. a
        // normal call where one end or the other hung up) *and* this
        // disconnect event caused the phone to become idle.  (In other
        // words, we *don't* play the sound if one call hangs up but
        // there's still an active call on the other line.)
        // TODO: We may eventually want to disable this via a preference.
        if ((toneToPlay == InCallTonePlayer.TONE_NONE)
                && (mPhone.getState() == Phone.State.IDLE)
                && (c != null)) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if ((cause == Connection.DisconnectCause.NORMAL)  // remote hangup
                    || (cause == Connection.DisconnectCause.LOCAL)) {  // local hangup
                if (VDBG) log("- need to play CALL_ENDED tone!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                mIsCdmaRedialCall = false;
            }
        }

        if (mPhone.getState() == Phone.State.IDLE) {
            // Don't reset the audio mode or bluetooth/speakerphone state
            // if we still need to let the user hear a tone through the earpiece.
            if (toneToPlay == InCallTonePlayer.TONE_NONE) {
                resetAudioStateAfterDisconnect();
            }

            NotificationMgr.getDefault().cancelCallInProgressNotification();

            // If the InCallScreen is *not* in the foreground, forcibly
            // dismiss it to make sure it won't still be in the activity
            // history.  (But if it *is* in the foreground, don't mess
            // with it; it needs to be visible, displaying the "Call
            // ended" state.)
            if (!mApplication.isShowingCallScreen()) {
                if (VDBG) log("onDisconnect: force InCallScreen to finish()");
                mApplication.dismissCallScreen();
            }
        }

        if (c != null) {
            final String number = c.getAddress();
            final long date = c.getCreateTime();
            final long duration = c.getDurationMillis();
            final Connection.DisconnectCause cause = c.getDisconnectCause();

            // Set the "type" to be displayed in the call log (see constants in CallLog.Calls)
            final int callLogType;
            if (c.isIncoming()) {
                callLogType = (cause == Connection.DisconnectCause.INCOMING_MISSED ?
                        CallLog.Calls.MISSED_TYPE :
                            CallLog.Calls.INCOMING_TYPE);
            } else {
                callLogType = CallLog.Calls.OUTGOING_TYPE;
            }
            if (VDBG) log("- callLogType: " + callLogType + ", UserData: " + c.getUserData());


            {
                final CallerInfo ci = getCallerInfoFromConnection(c);  // May be null.
                final String logNumber = getLogNumber(c, ci);

                if (DBG) log("- onDisconnect(): logNumber set to: " + logNumber);

                // TODO: In getLogNumber we use the presentation from
                // the connection for the CNAP. Should we use the one
                // below instead? (comes from caller info)

                // For international calls, 011 needs to be logged as +
                final int presentation = getPresentation(c, ci);

                if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    if ((PhoneNumberUtils.isEmergencyNumber(number))
                            && (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF)) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.stop();
                        }
                    }
                }

                // To prevent accidental redial of emergency numbers
                // (carrier requirement) the quickest solution is to
                // not log the emergency number. We gate on CDMA
                // (ugly) when we actually mean carrier X.
                // TODO: Clean this up and come up with a unified strategy.
                final boolean shouldNotlogEmergencyNumber =
                    (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA);

                // Don't call isOtaSpNumber on GSM phones.
                final boolean isOtaNumber = (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA)
                && mPhone.isOtaSpNumber(number);
                final boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(number);

                // Don't put OTA or CDMA Emergency calls into call log
                if (!(isOtaNumber || isEmergencyNumber && shouldNotlogEmergencyNumber)) {
                    // Watch out: Calls.addCall() hits the Contacts database,
                    // so we shouldn't call it from the main thread.
                    Thread t = new Thread() {
                        public void run() {
                            Calls.addCall(ci, mApplication, logNumber, presentation,
                                    callLogType, date, (int) duration / 1000);
                            // if (DBG) log("onDisconnect helper thread: Calls.addCall() done.");
                        }
                    };
                    t.start();
                }
            }

            if (callLogType == CallLog.Calls.MISSED_TYPE) {
                // Show the "Missed call" notification.
                // (Note we *don't* do this if this was an incoming call that
                // the user deliberately rejected.)
                showMissedCallNotification(c, date);
            }

            // Possibly play a "post-disconnect tone" thru the earpiece.
            // We do this here, rather than from the InCallScreen
            // activity, since we need to do this even if you're not in
            // the Phone UI at the moment the connection ends.
            if (toneToPlay != InCallTonePlayer.TONE_NONE) {
                if (VDBG) log("- starting post-disconnect tone (" + toneToPlay + ")...");
                new InCallTonePlayer(toneToPlay).start();

                // TODO: alternatively, we could start an InCallTonePlayer
                // here with an "unlimited" tone length,
                // and manually stop it later when this connection truly goes
                // away.  (The real connection over the network was closed as soon
                // as we got the BUSY message.  But our telephony layer keeps the
                // connection open for a few extra seconds so we can show the
                // "busy" indication to the user.  We could stop the busy tone
                // when *that* connection's "disconnect" event comes in.)
            }

            if (mPhone.getState() == Phone.State.IDLE) {
                // Release screen wake locks if the in-call screen is not
                // showing. Otherwise, let the in-call screen handle this because
                // it needs to show the call ended screen for a couple of
                // seconds.
                if (!mApplication.isShowingCallScreen()) {
                    if (VDBG) log("- NOT showing in-call screen; releasing wake locks!");
                    mApplication.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);
                    mApplication.requestWakeState(PhoneApp.WakeState.SLEEP);
                } else {
                    if (VDBG) log("- still showing in-call screen; not releasing wake locks.");
                }
            } else {
                if (VDBG) log("- phone still in use; not releasing wake locks.");
            }

            if (((mPreviousCdmaCallState == Call.State.DIALING)
                    || (mPreviousCdmaCallState == Call.State.ALERTING))
                    && (!PhoneNumberUtils.isEmergencyNumber(number))
                    && (cause != Connection.DisconnectCause.INCOMING_MISSED )
                    && (cause != Connection.DisconnectCause.NORMAL)
                    && (cause != Connection.DisconnectCause.LOCAL)
                    && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {
                if (!mIsCdmaRedialCall) {
                    if (autoretrySetting == InCallScreen.AUTO_RETRY_ON) {
                        // TODO: (Moto): The contact reference data may need to be stored and use
                        // here when redialing a call. For now, pass in NULL as the URI parameter.
                        PhoneUtils.placeCall(mPhone, number, null);
                        mIsCdmaRedialCall = true;
                    } else {
                        mIsCdmaRedialCall = false;
                    }
                } else {
                    mIsCdmaRedialCall = false;
                }
            }
        }
    }

    /**
     * Resets the audio mode and speaker state when a call ends.
     */
    private void resetAudioStateAfterDisconnect() {
        if (VDBG) log("resetAudioStateAfterDisconnect()...");

        if (mBluetoothHandsfree != null) {
            mBluetoothHandsfree.audioOff();
        }

        // call turnOnSpeaker() with state=false and store=true even if speaker
        // is already off to reset user requested speaker state.
        PhoneUtils.turnOnSpeaker(mPhone.getContext(), false, true);

        PhoneUtils.setAudioMode(mPhone.getContext(), AudioManager.MODE_NORMAL);
    }

    private void onMwiChanged(boolean visible) {
        if (VDBG) log("onMwiChanged(): " + visible);
        NotificationMgr.getDefault().updateMwi(visible);
    }

    /**
     * Posts a delayed PHONE_MWI_CHANGED event, to schedule a "retry" for a
     * failed NotificationMgr.updateMwi() call.
     */
    /* package */ void sendMwiChangedDelayed(long delayMillis) {
        Message message = Message.obtain(this, PHONE_MWI_CHANGED);
        sendMessageDelayed(message, delayMillis);
    }

    private void onCfiChanged(boolean visible) {
        if (VDBG) log("onCfiChanged(): " + visible);
        NotificationMgr.getDefault().updateCfi(visible);
    }

    private SensorManager mSensorManager;
    private boolean mSensorRunning = false;
    private TurnListener mTurnListener = new TurnListener();

    class TurnListener implements SensorEventListener {
        int count = 0;
        public void onSensorChanged(SensorEvent event) {
            if (++count < 5) {  // omit the first 5 times
                return;
            }
            float[] values = event.values;
            // Log.i("==="," @ " + values[1] + " : " + values[2]);
            if (count <= 7) {   // test 5 to 7 times
                if (Math.abs(values[1]) > 15 || Math.abs(values[2]) > 20) {
                    // Log.i("===","force stop sensor! @ " + values[1] + " : " + values[2]);
                    stopSensor();
                }
            } else {
                if (Math.abs(values[1]) > 165 && Math.abs(values[2]) < 20) {
                    if (DBG) log("turn over!");
                    silenceRinger();
                }
            }
        }
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    void stopSensor() {
        if (mSensorRunning) {
            if (DBG) log("stop sensor!");
            mTurnListener.count = 0;
            mSensorManager.unregisterListener(mTurnListener);
            mSensorRunning = false;
        }
    }


    void startSensor() {
        if (mSettings.mTurnSilence && !mSensorRunning) {
            if (DBG) log("startSensor()...");
            mSensorManager.registerListener(mTurnListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION),
                    SensorManager.SENSOR_DELAY_NORMAL);
            mSensorRunning = true;
        }
    }

    /**
     * Indicates whether or not this ringer is ringing.
     */
    boolean isRinging() {
        return mRinger.isRinging();
    }

    /**
     * Stops the current ring, and tells the notifier that future
     * ring requests should be ignored.
     */
    void silenceRinger() {
        mSilentRingerRequested = true;
        if (DBG) log("stopRing()... (silenceRinger)");
        // Log.i("===","silence sensor!");
        stopSensor();
        mRinger.stopRing();
    }

    /**
     * Posts a PHONE_BATTERY_LOW event, causing us to play a warning
     * tone if the user is in-call.
     */
    /* package */ void sendBatteryLow() {
        Message message = Message.obtain(this, PHONE_BATTERY_LOW);
        sendMessage(message);
    }

    private void onBatteryLow() {
        if (DBG) log("onBatteryLow()...");

        // Play the "low battery" warning tone, only if the user is
        // in-call.  (The test here is exactly the opposite of the test in
        // StatusBarPolicy.updateBattery(), where we bring up the "low
        // battery warning" dialog only if the user is NOT in-call.)
        if (mPhone.getState() != Phone.State.IDLE) {
            new InCallTonePlayer(InCallTonePlayer.TONE_BATTERY_LOW).start();
        }
    }


    /**
     * Helper class to play tones through the earpiece (or speaker / BT)
     * during a call, using the ToneGenerator.
     *
     * To use, just instantiate a new InCallTonePlayer
     * (passing in the TONE_* constant for the tone you want)
     * and start() it.
     *
     * When we're done playing the tone, if the phone is idle at that
     * point, we'll reset the audio routing and speaker state.
     * (That means that for tones that get played *after* a call
     * disconnects, like "busy" or "congestion" or "call ended", you
     * should NOT call resetAudioStateAfterDisconnect() yourself.
     * Instead, just start the InCallTonePlayer, which will automatically
     * defer the resetAudioStateAfterDisconnect() call until the tone
     * finishes playing.)
     */
    private class InCallTonePlayer extends Thread {
        private int mToneId;

        // The possible tones we can play.
        public static final int TONE_NONE = 0;
        public static final int TONE_CALL_WAITING = 1;
        public static final int TONE_BUSY = 2;
        public static final int TONE_CONGESTION = 3;
        public static final int TONE_BATTERY_LOW = 4;
        public static final int TONE_CALL_ENDED = 5;
        public static final int TONE_VOICE_PRIVACY = 6;
        public static final int TONE_REORDER = 7;
        public static final int TONE_INTERCEPT = 8;
        public static final int TONE_CDMA_DROP = 9;
        public static final int TONE_OUT_OF_SERVICE = 10;
        public static final int TONE_REDIAL = 11;
        public static final int TONE_OTA_CALL_END = 12;

        // The tone volume relative to other sounds in the stream
        private static final int TONE_RELATIVE_VOLUME_HIPRI = 80;
        private static final int TONE_RELATIVE_VOLUME_LOPRI = 50;

        // Buffer time (in msec) to add on to tone timeout value.
        // Needed mainly when the timeout value for a tone is the
        // exact duration of the tone itself.
        private static final int TONE_TIMEOUT_BUFFER = 20;

        InCallTonePlayer(int toneId) {
            super();
            mToneId = toneId;
        }

        @Override
        public void run() {
            if (VDBG) log("InCallTonePlayer.run(toneId = " + mToneId + ")...");

            int toneType = 0;  // passed to ToneGenerator.startTone()
            int toneVolume;  // passed to the ToneGenerator constructor
            int toneLengthMillis;

            AudioManager audioManager = (AudioManager) mPhone.getContext()
            .getSystemService(Context.AUDIO_SERVICE);
            switch (mToneId) {
                case TONE_CALL_WAITING:
                    toneType = ToneGenerator.TONE_SUP_CALL_WAITING;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_BUSY:
                    int phoneType = mPhone.getPhoneType();
                    if (phoneType == Phone.PHONE_TYPE_CDMA) {
                        toneType = ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT;
                        toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                        toneLengthMillis = 1000;
                    } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                        toneType = ToneGenerator.TONE_SUP_BUSY;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 4000;
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    break;
                case TONE_CONGESTION:
                    toneType = ToneGenerator.TONE_SUP_CONGESTION;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 4000;
                    break;
                case TONE_BATTERY_LOW:
                    // For now, use ToneGenerator.TONE_PROP_ACK (two quick
                    // beeps).  TODO: is there some other ToneGenerator
                    // tone that would be more appropriate here?  Or
                    // should we consider adding a new custom tone?
                    toneType = ToneGenerator.TONE_PROP_ACK;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 1000;
                    break;
                case TONE_CALL_ENDED:
                    toneType = ToneGenerator.TONE_PROP_PROMPT;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 200;
                    break;
                case TONE_OTA_CALL_END:
                    if (mApplication.cdmaOtaConfigData.otaPlaySuccessFailureTone ==
                        OtaUtils.OTA_PLAY_SUCCESS_FAILURE_TONE_ON) {
                        toneType = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 750;
                    } else {
                        toneType = ToneGenerator.TONE_PROP_PROMPT;
                        toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                        toneLengthMillis = 200;
                    }
                    break;
                case TONE_VOICE_PRIVACY:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 5000;
                    break;
                case TONE_REORDER:
                    toneType = ToneGenerator.TONE_CDMA_ABBR_REORDER;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 4000;
                    break;
                case TONE_INTERCEPT:
                    toneType = ToneGenerator.TONE_CDMA_ABBR_INTERCEPT;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 500;
                    break;
                case TONE_CDMA_DROP:
                case TONE_OUT_OF_SERVICE:
                    toneType = ToneGenerator.TONE_CDMA_CALLDROP_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 375;
                    break;
                case TONE_REDIAL:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_LOPRI;
                    toneLengthMillis = 5000;
                    break;
                default:
                    throw new IllegalArgumentException("Bad toneId: " + mToneId);
            }

            // If the mToneGenerator creation fails, just continue without it.  It is
            // a local audio signal, and is not as important.
            ToneGenerator toneGenerator;
            try {
                int stream;
                if (mBluetoothHandsfree != null) {
                    stream = mBluetoothHandsfree.isAudioOn() ? AudioManager.STREAM_BLUETOOTH_SCO:
                        AudioManager.STREAM_VOICE_CALL;
                } else {
                    stream = AudioManager.STREAM_VOICE_CALL;
                }
                toneGenerator = new ToneGenerator(stream, toneVolume);
                // if (DBG) log("- created toneGenerator: " + toneGenerator);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG,
                        "InCallTonePlayer: Exception caught while creating ToneGenerator: " + e);
                toneGenerator = null;
            }

            // Using the ToneGenerator (with the CALL_WAITING / BUSY /
            // CONGESTION tones at least), the ToneGenerator itself knows
            // the right pattern of tones to play; we do NOT need to
            // manually start/stop each individual tone, or manually
            // insert the correct delay between tones.  (We just start it
            // and let it run for however long we want the tone pattern to
            // continue.)
            //
            // TODO: When we stop the ToneGenerator in the middle of a
            // "tone pattern", it sounds bad if we cut if off while the
            // tone is actually playing.  Consider adding API to the
            // ToneGenerator to say "stop at the next silent part of the
            // pattern", or simply "play the pattern N times and then
            // stop."
            boolean needToStopTone = true;
            boolean okToPlayTone = false;

            if (toneGenerator != null) {
                int phoneType = mPhone.getPhoneType();
                int ringerMode = audioManager.getRingerMode();
                if (phoneType == Phone.PHONE_TYPE_CDMA) {
                    if (toneType == ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("- InCallTonePlayer: start playing call tone=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_INTERCEPT) ||
                            (toneType == ToneGenerator.TONE_CDMA_CALLDROP_LITE)) {
                        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                            if (DBG) log("InCallTonePlayer:playing call fail tone:" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE) ||
                            (toneType == ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE)) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("InCallTonePlayer:playing tone for toneType=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else { // For the rest of the tones, always OK to play.
                        okToPlayTone = true;
                    }
                } else {  // Not "CDMA"
                    okToPlayTone = true;
                }

                if (okToPlayTone) {
                    toneGenerator.startTone(toneType);
                    SystemClock.sleep(toneLengthMillis + TONE_TIMEOUT_BUFFER);
                    if (needToStopTone) {
                        toneGenerator.stopTone();
                    }
                }
                // if (DBG) log("- InCallTonePlayer: done playing.");
                toneGenerator.release();
            }

            // Finally, do the same cleanup we otherwise would have done
            // in onDisconnect().
            //
            // (But watch out: do NOT do this if the phone is in use,
            // since some of our tones get played *during* a call (like
            // CALL_WAITING and BATTERY_LOW) and we definitely *don't*
            // want to reset the audio mode / speaker / bluetooth after
            // playing those!
            // This call is really here for use with tones that get played
            // *after* a call disconnects, like "busy" or "congestion" or
            // "call ended", where the phone has already become idle but
            // we need to defer the resetAudioStateAfterDisconnect() call
            // till the tone finishes playing.)
            if (mPhone.getState() == Phone.State.IDLE) {
                resetAudioStateAfterDisconnect();
            }
        }
    }

    /**
     * Displays a notification when the phone receives a DisplayInfo record.
     */
    private void onDisplayInfo(AsyncResult r) {
        // Extract the DisplayInfo String from the message
        CdmaDisplayInfoRec displayInfoRec = (CdmaDisplayInfoRec)(r.result);

        if (displayInfoRec != null) {
            String displayInfo = displayInfoRec.alpha;
            if (DBG) log("onDisplayInfo: displayInfo=" + displayInfo);
            CdmaDisplayInfo.displayInfoRecord(mApplication, displayInfo);

            // start a 2 second timer
            sendEmptyMessageDelayed(DISPLAYINFO_NOTIFICATION_DONE,
                    DISPLAYINFO_NOTIFICATION_TIME);
        }
    }

    /**
     * Helper class to play SignalInfo tones using the ToneGenerator.
     *
     * To use, just instantiate a new SignalInfoTonePlayer
     * (passing in the ToneID constant for the tone you want)
     * and start() it.
     */
    private class SignalInfoTonePlayer extends Thread {
        private int mToneId;

        SignalInfoTonePlayer(int toneId) {
            super();
            mToneId = toneId;
        }

        @Override
        public void run() {
            if (DBG) log("SignalInfoTonePlayer.run(toneId = " + mToneId + ")...");

            if (mSignalInfoToneGenerator != null) {
                //First stop any ongoing SignalInfo tone
                mSignalInfoToneGenerator.stopTone();

                //Start playing the new tone if its a valid tone
                mSignalInfoToneGenerator.startTone(mToneId);
            }
        }
    }

    /**
     * Plays a tone when the phone receives a SignalInfo record.
     */
    private void onSignalInfo(AsyncResult r) {
        if (mPhone.getRingingCall().getState() == Call.State.INCOMING) {
            // Do not start any new SignalInfo tone when Call state is INCOMING
            // and stop any previous SignalInfo tone which is being played
            stopSignalInfoTone();
        } else {
            // Extract the SignalInfo String from the message
            CdmaSignalInfoRec signalInfoRec = (CdmaSignalInfoRec)(r.result);
            // Only proceed if a Signal info is present.
            if (signalInfoRec != null) {
                boolean isPresent = signalInfoRec.isPresent;
                if (DBG) log("onSignalInfo: isPresent=" + isPresent);
                if (isPresent) {// if tone is valid
                    int uSignalType = signalInfoRec.signalType;
                    int uAlertPitch = signalInfoRec.alertPitch;
                    int uSignal = signalInfoRec.signal;

                    if (DBG) log("onSignalInfo: uSignalType=" + uSignalType + ", uAlertPitch=" +
                            uAlertPitch + ", uSignal=" + uSignal);
                    //Map the Signal to a ToneGenerator ToneID only if Signal info is present
                    int toneID = SignalToneUtil.getAudioToneFromSignalInfo
                    (uSignalType, uAlertPitch, uSignal);

                    //Create the SignalInfo tone player and pass the ToneID
                    new SignalInfoTonePlayer(toneID).start();
                }
            }
        }
    }

    /**
     * Stops a SignalInfo tone in the following condition
     * 1 - On receiving a New Ringing Call
     * 2 - On disconnecting a call
     * 3 - On answering a Call Waiting Call
     */
    /* package */ void stopSignalInfoTone() {
        if (DBG) log("stopSignalInfoTone: Stopping SignalInfo tone player");
        new SignalInfoTonePlayer(ToneGenerator.TONE_CDMA_SIGNAL_OFF).start();
    }

    /**
     * Plays a Call waiting tone if it is present in the second incoming call.
     */
    private void onCdmaCallWaiting(AsyncResult r) {
        // Remove any previous Call waiting timers in the queue
        removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
        removeMessages(CALLWAITING_ADDCALL_DISABLE_TIMEOUT);

        // Set the Phone Call State to SINGLE_ACTIVE as there is only one connection
        // else we would not have received Call waiting
        mApplication.cdmaPhoneCallState.setCurrentCallState(
                CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE);

        // Start the InCallScreen Activity if its not on foreground
        if (!mApplication.isShowingCallScreen()) {
            PhoneUtils.showIncomingCallUi();
        }

        // Start timer for CW display
        mCallWaitingTimeOut = false;
        sendEmptyMessageDelayed(CALLWAITING_CALLERINFO_DISPLAY_DONE,
                CALLWAITING_CALLERINFO_DISPLAY_TIME);

        // Set the mAddCallMenuStateAfterCW state to false
        mApplication.cdmaPhoneCallState.setAddCallMenuStateAfterCallWaiting(false);

        // Start the timer for disabling "Add Call" menu option
        sendEmptyMessageDelayed(CALLWAITING_ADDCALL_DISABLE_TIMEOUT,
                CALLWAITING_ADDCALL_DISABLE_TIME);

        // Extract the Call waiting information
        CdmaCallWaitingNotification infoCW = (CdmaCallWaitingNotification) r.result;
        int isPresent = infoCW.isPresent;
        if (DBG) log("onCdmaCallWaiting: isPresent=" + isPresent);
        if (isPresent == 1 ) {//'1' if tone is valid
            int uSignalType = infoCW.signalType;
            int uAlertPitch = infoCW.alertPitch;
            int uSignal = infoCW.signal;
            if (DBG) log("onCdmaCallWaiting: uSignalType=" + uSignalType + ", uAlertPitch="
                    + uAlertPitch + ", uSignal=" + uSignal);
            //Map the Signal to a ToneGenerator ToneID only if Signal info is present
            int toneID =
                SignalToneUtil.getAudioToneFromSignalInfo(uSignalType, uAlertPitch, uSignal);

            //Create the SignalInfo tone player and pass the ToneID
            new SignalInfoTonePlayer(toneID).start();
        }
    }

    /**
     * Performs Call logging based on Timeout or Ignore Call Waiting Call for CDMA,
     * and finally calls Hangup on the Call Waiting connection.
     */
    /* package */ void onCdmaCallWaitingReject() {
        final Call ringingCall = mPhone.getRingingCall();

        // Call waiting timeout scenario
        if (ringingCall.getState() == Call.State.WAITING) {
            // Code for perform Call logging and missed call notification
            Connection c = ringingCall.getLatestConnection();

            if (c != null) {
                String number = c.getAddress();
                int presentation = c.getNumberPresentation();
                final long date = c.getCreateTime();
                final long duration = c.getDurationMillis();
                final int callLogType = mCallWaitingTimeOut ?
                        CallLog.Calls.MISSED_TYPE  : CallLog.Calls.INCOMING_TYPE;

                // get the callerinfo object and then log the call with it.
                Object o = c.getUserData();
                final CallerInfo ci;
                if ((o == null) || (o instanceof CallerInfo)) {
                    ci = (CallerInfo) o;
                } else {
                    ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
                }

                // Do final CNAP modifications of logNumber prior to logging [mimicking
                // onDisconnect()]
                final String logNumber = PhoneUtils.modifyForSpecialCnapCases(
                        mPhone.getContext(), ci, number, presentation);
                final int newPresentation = (ci != null) ? ci.numberPresentation : presentation;
                if (DBG) log("- onCdmaCallWaitingReject(): logNumber set to: " + logNumber
                        + ", newPresentation value is: " + newPresentation);

                // Watch out: Calls.addCall() hits the Contacts database,
                // so we shouldn't call it from the main thread.
                Thread t = new Thread() {
                    public void run() {
                        Calls.addCall(ci, mApplication, logNumber, newPresentation,
                                callLogType, date, (int) duration / 1000);
                        if (DBG) log("onCdmaCallWaitingReject helper thread: Calls.addCall() done.");
                    }
                };
                t.start();

                if (callLogType == CallLog.Calls.MISSED_TYPE) {
                    // Add missed call notification
                    showMissedCallNotification(c, date);
                } else {
                    // Remove Call waiting 20 second display timer in the queue
                    removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
                }

                // Hangup the RingingCall connection for CW
                PhoneUtils.hangup(c);
            }

            //Reset the mCallWaitingTimeOut boolean
            mCallWaitingTimeOut = false;
        }
    }

    /**
     * Return the private variable mPreviousCdmaCallState.
     */
    /* package */ Call.State getPreviousCdmaCallState() {
        return mPreviousCdmaCallState;
    }

    /**
     * Return the private variable mCdmaVoicePrivacyState.
     */
    /* package */ boolean getCdmaVoicePrivacyState() {
        return mCdmaVoicePrivacyState;
    }

    /**
     * Return the private variable mIsCdmaRedialCall.
     */
    /* package */ boolean getIsCdmaRedialCall() {
        return mIsCdmaRedialCall;
    }

    /**
     * Helper function used to show a missed call notification.
     */
    private void showMissedCallNotification(Connection c, final long date) {
        PhoneUtils.CallerInfoToken info =
            PhoneUtils.startGetCallerInfo(mApplication, c, this, Long.valueOf(date));
        if (info != null) {
            // at this point, we've requested to start a query, but it makes no
            // sense to log this missed call until the query comes back.
            if (VDBG) log("showMissedCallNotification: Querying for CallerInfo on missed call...");
            if (info.isFinal) {
                // it seems that the query we have actually is up to date.
                // send the notification then.
                CallerInfo ci = info.currentInfo;

                // Check number presentation value; if we have a non-allowed presentation,
                // then display an appropriate presentation string instead as the missed
                // call.
                String name = ci.name;
                String number = ci.phoneNumber;
                if (ci.numberPresentation == Connection.PRESENTATION_RESTRICTED) {
                    name = mPhone.getContext().getString(R.string.private_num);
                } else if (ci.numberPresentation != Connection.PRESENTATION_ALLOWED) {
                    name = mPhone.getContext().getString(R.string.unknown);
                } else {
                    number = PhoneUtils.modifyForSpecialCnapCases(mPhone.getContext(),
                            ci, number, ci.numberPresentation);
                }
                NotificationMgr.getDefault().notifyMissedCall(name, number,
                        ci.phoneLabel, date);
            }
        } else {
            // getCallerInfo() can return null in rare cases, like if we weren't
            // able to get a valid phone number out of the specified Connection.
            Log.w(LOG_TAG, "showMissedCallNotification: got null CallerInfo for Connection " + c);
        }
    }

    /**
     *  Inner class to handle emergency call tone and vibrator
     */
    private class EmergencyTonePlayerVibrator {
        private final int EMG_VIBRATE_LENGTH = 1000;  // ms.
        private final int EMG_VIBRATE_PAUSE  = 1000;  // ms.
        private final long[] mVibratePattern =
            new long[] { EMG_VIBRATE_LENGTH, EMG_VIBRATE_PAUSE };

        private ToneGenerator mToneGenerator;
        private AudioManager mAudioManager;
        private Vibrator mEmgVibrator;

        /**
         * constructor
         */
        public EmergencyTonePlayerVibrator() {
            Context context = mApplication.getApplicationContext();
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }

        /**
         * Start the emergency tone or vibrator.
         */
        private void start() {
            if (VDBG) log("call startEmergencyToneOrVibrate.");
            int ringerMode = mAudioManager.getRingerMode();

            if ((mIsEmergencyToneOn == EMERGENCY_TONE_ALERT) &&
                    (ringerMode == AudioManager.RINGER_MODE_NORMAL)) {
                if (VDBG) log("Play Emergency Tone.");
                mToneGenerator = new ToneGenerator (AudioManager.STREAM_VOICE_CALL,
                        InCallTonePlayer.TONE_RELATIVE_VOLUME_HIPRI);
                if (mToneGenerator != null) {
                    mToneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK);
                    mCurrentEmergencyToneState = EMERGENCY_TONE_ALERT;
                }
            } else if (mIsEmergencyToneOn == EMERGENCY_TONE_VIBRATE) {
                if (VDBG) log("Play Emergency Vibrate.");
                mEmgVibrator = new Vibrator();
                if (mEmgVibrator != null) {
                    mEmgVibrator.vibrate(mVibratePattern, 0);
                    mCurrentEmergencyToneState = EMERGENCY_TONE_VIBRATE;
                }
            }
        }

        /**
         * If the emergency tone is active, stop the tone or vibrator accordingly.
         */
        private void stop() {
            if (VDBG) log("call stopEmergencyToneOrVibrate.");

            if ((mCurrentEmergencyToneState == EMERGENCY_TONE_ALERT)
                    && (mToneGenerator != null)) {
                mToneGenerator.stopTone();
                mToneGenerator.release();
            } else if ((mCurrentEmergencyToneState == EMERGENCY_TONE_VIBRATE)
                    && (mEmgVibrator != null)) {
                mEmgVibrator.cancel();
            }
            mCurrentEmergencyToneState = EMERGENCY_TONE_OFF;
        }
    }

    private void onRingbackTone(AsyncResult r) {
        boolean playTone = (Boolean)(r.result);

        if (playTone == true) {
            if (mInCallRingbackTonePlayer == null) {
                mInCallRingbackTonePlayer = new InCallRingbackTonePlayer();
            }

            // Only play when foreground call is in DIALING or ALERTING.
            // to prevent a late coming palytone after ALERTING.
            // Don't play ringback tone if it is in play, otherwise it will cut
            // the current tone and replay it
            if (mInCallRingbackTonePlayer != null
                    && mPhone.getForegroundCall().getState().isDialing()
                    && mCurrentRingbackToneState == RINGBACK_TONE_OFF) {
                mInCallRingbackTonePlayer.start();
            }
        } else {
            if (mInCallRingbackTonePlayer != null &&
                    mCurrentRingbackToneState == RINGBACK_TONE_ON) {
                mInCallRingbackTonePlayer.stop();
            }
        }
    }

    /**
     * Toggle mute and unmute requests while keeping the same mute state
     */
    private void onResendMute() {
        boolean muteState = PhoneUtils.getMute(mPhone);
        PhoneUtils.setMuteInternal(mPhone, !muteState);
        PhoneUtils.setMuteInternal(mPhone, muteState);
    }

    /**
     * Inner class to handle in call ringback tone
     *
     */
    private class InCallRingbackTonePlayer {
        private ToneGenerator mToneGenerator;

        /**
         * constructor
         */
        InCallRingbackTonePlayer() {
            mToneGenerator = null;
        }

        /**
         * start the ringback tone
         */
        private void start() {
            if (VDBG) log("InCallRingbackTonePlayer.start()");

            AudioManager audioManager = (AudioManager) mPhone.getContext()
            .getSystemService(Context.AUDIO_SERVICE);

            try {
                int stream;
                if (mBluetoothHandsfree != null) {
                    stream = mBluetoothHandsfree.isAudioOn() ? AudioManager.STREAM_BLUETOOTH_SCO:
                        AudioManager.STREAM_VOICE_CALL;
                } else {
                    stream = AudioManager.STREAM_VOICE_CALL;
                }
                mToneGenerator = new ToneGenerator(stream,
                        InCallTonePlayer.TONE_RELATIVE_VOLUME_HIPRI);
            } catch (RuntimeException e) {
                // If mToneGenerator creation fails, just continue without it.
                // It is a local audio signal, and is not as important.
                Log.w(LOG_TAG,
                        "InCallRingbackTonePlayer: Exception caught while creating ToneGenerator: " + e);
                mToneGenerator = null;
            }

            if (mToneGenerator != null) {
                mToneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
                mCurrentRingbackToneState = RINGBACK_TONE_ON;
            }
        }

        /**
         * If the ringback tone is playing, stop it and release toneGenerator.
         */
        private void stop() {
            if (VDBG) log("InCallRingbackTonePlayer.stop()");

            if (mToneGenerator != null) {
                mToneGenerator.stopTone();
                mToneGenerator.release();
                mToneGenerator = null;
            }

            mCurrentRingbackToneState = RINGBACK_TONE_OFF;
        }
    }

    /**
     * Retrieve the phone number from the caller info or the connection.
     *
     * For incoming call the number is in the Connection object. For
     * outgoing call we use the CallerInfo phoneNumber field if
     * present. All the processing should have been done already (CDMA vs GSM numbers).
     *
     * If CallerInfo is missing the phone number, get it from the connection.
     * Apply the Call Name Presentation (CNAP) transform in the connection on the number.
     *
     * @param conn The phone connection.
     * @param info The CallerInfo. Maybe null.
     * @return the phone number.
     */
    private String getLogNumber(Connection conn, CallerInfo callerInfo) {
        String number = null;

        if (conn.isIncoming()) {
            number = conn.getAddress();
        } else {
            // For emergency and voicemail calls,
            // CallerInfo.phoneNumber does *not* contain a valid phone
            // number.  Instead it contains an I18N'd string such as
            // "Emergency Number" or "Voice Mail" so we get the number
            // from the connection.
            if (null == callerInfo || TextUtils.isEmpty(callerInfo.phoneNumber) ||
                    callerInfo.isEmergencyNumber() || callerInfo.isVoiceMailNumber()) {
                if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                    // In cdma getAddress() is not always equals to getOrigDialString().
                    number = conn.getOrigDialString();
                } else {
                    number = conn.getAddress();
                }
            } else {
                number = callerInfo.phoneNumber;
            }
        }

        if (null == number) {
            return null;
        } else {
            int presentation = conn.getNumberPresentation();

            // Do final CNAP modifications.
            number = PhoneUtils.modifyForSpecialCnapCases(mPhone.getContext(), callerInfo,
                    number, presentation);
            number = PhoneNumberUtils.stripSeparators(number);
            if (VDBG) log("getLogNumber: " + number);
            return number;
        }
    }

    /**
     * Get the caller info.
     *
     * @param conn The phone connection.
     * @return The CallerInfo associated with the connection. Maybe null.
     */
    private CallerInfo getCallerInfoFromConnection(Connection conn) {
        CallerInfo ci = null;
        Object o = conn.getUserData();

        if ((o == null) || (o instanceof CallerInfo)) {
            ci = (CallerInfo) o;
        } else {
            ci = ((PhoneUtils.CallerInfoToken) o).currentInfo;
        }
        return ci;
    }

    /**
     * Get the presentation from the callerinfo if not null otherwise,
     * get it from the connection.
     *
     * @param conn The phone connection.
     * @param info The CallerInfo. Maybe null.
     * @return The presentation to use in the logs.
     */
    private int getPresentation(Connection conn, CallerInfo callerInfo) {
        int presentation;

        if (null == callerInfo) {
            presentation = conn.getNumberPresentation();
        } else {
            presentation = callerInfo.numberPresentation;
            if (DBG) log("- getPresentation(): ignoring connection's presentation: " +
                    conn.getNumberPresentation());
        }
        if (DBG) log("- getPresentation: presentation: " + presentation);
        return presentation;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
