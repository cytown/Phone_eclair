/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

/**
 * EmergencyDialer is a special dialer that is used ONLY for dialing emergency calls.
 *
 * It's a simplified version of the regular dialer (i.e. the TwelveKeyDialer
 * activity from apps/Contacts) that:
 *   1. Allows ONLY emergency calls to be dialed
 *   2. Disallows voicemail functionality
 *   3. Uses the FLAG_SHOW_WHEN_LOCKED window manager flag to allow this
 *      activity to stay in front of the keyguard.
 *
 * TODO: Even though this is an ultra-simplified version of the normal
 * dialer, there's still lots of code duplication between this class and
 * the TwelveKeyDialer class from apps/Contacts.  Could the common code be
 * moved into a shared base class that would live in the framework?
 * Or could we figure out some way to move *this* class into apps/Contacts
 * also?
 */
public class EmergencyDialer extends Activity
        implements View.OnClickListener, View.OnLongClickListener,
        View.OnKeyListener, TextWatcher {
    // Keys used with onSaveInstanceState().
    private static final String LAST_NUMBER = "lastNumber";

    // Intent action for this activity.
    public static final String ACTION_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    // Debug constants.
    private static final boolean DBG = false;
    private static final String LOG_TAG = "EmergencyDialer";

    /** The length of DTMF tones in milliseconds */
    private static final int TONE_LENGTH_MS = 150;

    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 80;

    /** Stream type used to play the DTMF tones off call, and mapped to the volume control keys */
    private static final int DIAL_TONE_STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private static final int BAD_EMERGENCY_NUMBER_DIALOG = 0;

    /** Play the vibrate pattern only once. */
    private static final int VIBRATE_NO_REPEAT = -1;

    EditText mDigits;
    // If mVoicemailDialAndDeleteRow is null, mDialButton and mDelete are also null.
    private View mVoicemailDialAndDeleteRow;
    private View mDialButton;
    private View mDelete;

    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();

    // new UI background assets
    private Drawable mDigitsBackground;
    private Drawable mDigitsEmptyBackground;

    // determines if we want to playback local DTMF tones.
    private boolean mDTMFToneEnabled;

    // Vibration (haptic feedback) for dialer key presses.
    private Vibrator mVibrator;
    private boolean mVibrateOn;
    private long[] mVibratePattern;

    // close activity when screen turns off
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                finish();
            }
        }
    };

    private String mLastNumber; // last number we tried to dial. Used to restore error dialog.

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing
    }

    public void onTextChanged(CharSequence input, int start, int before, int changeCount) {
        // Do nothing
    }


    public void afterTextChanged(Editable input) {
        if (SpecialCharSequenceMgr.handleChars(this, input.toString(), this)) {
            // A special sequence was entered, clear the digits
            mDigits.getText().clear();
        }

        final boolean notEmpty = mDigits.length() != 0;
        if (notEmpty) {
            mDigits.setBackgroundDrawable(mDigitsBackground);
        } else {
            mDigits.setBackgroundDrawable(mDigitsEmptyBackground);
        }

        updateDialAndDeleteButtonStateEnabledAttr();
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // set this flag so this activity will stay in front of the keyguard
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        // Set the content view
        setContentView(R.layout.emergency_dialer);

        // Load up the resources for the text field and delete button
        Resources r = getResources();
        mDigitsBackground = r.getDrawable(R.drawable.btn_dial_textfield_active);
        mDigitsEmptyBackground = r.getDrawable(R.drawable.btn_dial_textfield);

        mDigits = (EditText) findViewById(R.id.digits);
        mDigits.setKeyListener(DialerKeyListener.getInstance());
        mDigits.setOnClickListener(this);
        mDigits.setOnKeyListener(this);
        mDigits.setLongClickable(false);
        maybeAddNumberFormatting();

        // Check for the presence of the keypad
        View view = findViewById(R.id.one);
        if (view != null) {
            setupKeypad();
        }

        mVoicemailDialAndDeleteRow = findViewById(R.id.voicemailAndDialAndDelete);

        // Check whether we should show the onscreen "Dial" button and co.
        if (r.getBoolean(R.bool.config_show_onscreen_dial_button)) {

            // The voicemail button is not active. Even if we marked
            // it as disabled in the layout, we have to manually clear
            // that state as well (b/2134374)
            // TODO: Check with UI designer if we should not show that button at all. (b/2134854)
            mVoicemailDialAndDeleteRow.findViewById(R.id.voicemailButton).setEnabled(false);

            mDialButton = mVoicemailDialAndDeleteRow.findViewById(R.id.dialButton);
            mDialButton.setOnClickListener(this);

            mDelete = mVoicemailDialAndDeleteRow.findViewById(R.id.deleteButton);
            mDelete.setOnClickListener(this);
            mDelete.setOnLongClickListener(this);
        } else {
            mVoicemailDialAndDeleteRow.setVisibility(View.GONE); // It's VISIBLE by default
            mVoicemailDialAndDeleteRow = null;
        }

        if (icicle != null) {
            super.onRestoreInstanceState(icicle);
        }

        // Extract phone number from intent
        Uri data = getIntent().getData();
        if (data != null && ("tel".equals(data.getScheme()))) {
            String number = PhoneNumberUtils.getNumberFromIntent(getIntent(), this);
            if (number != null) {
                mDigits.setText(number);
            }
        }

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    // we want the user to be able to control the volume of the dial tones
                    // outside of a call, so we use the stream type that is also mapped to the
                    // volume control keys for this activity
                    mToneGenerator = new ToneGenerator(DIAL_TONE_STREAM_TYPE, TONE_RELATIVE_VOLUME);
                    setVolumeControlStream(DIAL_TONE_STREAM_TYPE);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mBroadcastReceiver, intentFilter);

        // Initialize vibration parameters.
        // TODO: We might eventually need to make mVibrateOn come from a
        // user preference rather than a per-platform resource, in which
        // case we would need to update it in onResume() rather than here.
        initVibrationPattern(r);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onRestoreInstanceState(Bundle icicle) {
        mLastNumber = icicle.getString(LAST_NUMBER);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(LAST_NUMBER, mLastNumber);
    }

    /**
     * Explicitly turn off number formatting, since it gets in the way of the emergency
     * number detector
     */
    protected void maybeAddNumberFormatting() {
        // Do nothing.
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // This can't be done in onCreate(), since the auto-restoring of the digits
        // will play DTMF tones for all the old digits if it is when onRestoreSavedInstanceState()
        // is called. This method will be called every time the activity is created, and
        // will always happen after onRestoreSavedInstanceState().
        mDigits.addTextChangedListener(this);
    }

    private void setupKeypad() {
        // Setup the listeners for the buttons
        findViewById(R.id.one).setOnClickListener(this);
        findViewById(R.id.two).setOnClickListener(this);
        findViewById(R.id.three).setOnClickListener(this);
        findViewById(R.id.four).setOnClickListener(this);
        findViewById(R.id.five).setOnClickListener(this);
        findViewById(R.id.six).setOnClickListener(this);
        findViewById(R.id.seven).setOnClickListener(this);
        findViewById(R.id.eight).setOnClickListener(this);
        findViewById(R.id.nine).setOnClickListener(this);
        findViewById(R.id.star).setOnClickListener(this);

        View view = findViewById(R.id.zero);
        view.setOnClickListener(this);
        view.setOnLongClickListener(this);

        findViewById(R.id.pound).setOnClickListener(this);
    }

    /**
     * handle key events
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                if (TextUtils.isEmpty(mDigits.getText().toString())) {
                    // if we are adding a call from the InCallScreen and the phone
                    // number entered is empty, we just close the dialer to expose
                    // the InCallScreen under it.
                    finish();
                } else {
                    // otherwise, we place the call.
                    placeCall();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void keyPressed(int keyCode) {
        vibrate();
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mDigits.onKeyDown(keyCode, event);
    }

    public boolean onKey(View view, int keyCode, KeyEvent event) {
        switch (view.getId()) {
            case R.id.digits:
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    placeCall();
                    return true;
                }
                break;
        }
        return false;
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.one: {
                playTone(ToneGenerator.TONE_DTMF_1);
                keyPressed(KeyEvent.KEYCODE_1);
                return;
            }
            case R.id.two: {
                playTone(ToneGenerator.TONE_DTMF_2);
                keyPressed(KeyEvent.KEYCODE_2);
                return;
            }
            case R.id.three: {
                playTone(ToneGenerator.TONE_DTMF_3);
                keyPressed(KeyEvent.KEYCODE_3);
                return;
            }
            case R.id.four: {
                playTone(ToneGenerator.TONE_DTMF_4);
                keyPressed(KeyEvent.KEYCODE_4);
                return;
            }
            case R.id.five: {
                playTone(ToneGenerator.TONE_DTMF_5);
                keyPressed(KeyEvent.KEYCODE_5);
                return;
            }
            case R.id.six: {
                playTone(ToneGenerator.TONE_DTMF_6);
                keyPressed(KeyEvent.KEYCODE_6);
                return;
            }
            case R.id.seven: {
                playTone(ToneGenerator.TONE_DTMF_7);
                keyPressed(KeyEvent.KEYCODE_7);
                return;
            }
            case R.id.eight: {
                playTone(ToneGenerator.TONE_DTMF_8);
                keyPressed(KeyEvent.KEYCODE_8);
                return;
            }
            case R.id.nine: {
                playTone(ToneGenerator.TONE_DTMF_9);
                keyPressed(KeyEvent.KEYCODE_9);
                return;
            }
            case R.id.zero: {
                playTone(ToneGenerator.TONE_DTMF_0);
                keyPressed(KeyEvent.KEYCODE_0);
                return;
            }
            case R.id.pound: {
                playTone(ToneGenerator.TONE_DTMF_P);
                keyPressed(KeyEvent.KEYCODE_POUND);
                return;
            }
            case R.id.star: {
                playTone(ToneGenerator.TONE_DTMF_S);
                keyPressed(KeyEvent.KEYCODE_STAR);
                return;
            }
            case R.id.dialButton:
            case R.id.digits: {
                vibrate();  // Vibrate here too, just like we do for the regular keys
                placeCall();
                return;
            }
            case R.id.deleteButton: {
                keyPressed(KeyEvent.KEYCODE_DEL);
                return;
            }
        }
    }

    /**
     * called for long touch events
     */
    public boolean onLongClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.deleteButton: {
                mDigits.getText().clear();
                // TODO: The framework forgets to clear the pressed
                // status of disabled button. Until this is fixed,
                // clear manually the pressed status. b/2133127
                mDelete.setPressed(false);
                return true;
            }
            case R.id.zero: {
                keyPressed(KeyEvent.KEYCODE_PLUS);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // retrieve the DTMF tone play back setting.
        mDTMFToneEnabled = Settings.System.getInt(getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        // Disable the status bar and set the poke lock timeout to medium.
        // There is no need to do anything with the wake lock.
        if (DBG) Log.d(LOG_TAG, "disabling status bar, set to long timeout");
        PhoneApp app = (PhoneApp) getApplication();
        app.disableStatusBar();
        app.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.MEDIUM);

        updateDialAndDeleteButtonStateEnabledAttr();
    }

    @Override
    public void onPause() {
        // Reenable the status bar and set the poke lock timeout to default.
        // There is no need to do anything with the wake lock.
        if (DBG) Log.d(LOG_TAG, "reenabling status bar and closing the dialer");
        PhoneApp app = (PhoneApp) getApplication();
        app.reenableStatusBar();
        app.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);

        super.onPause();

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }

    /**
     * place the call, but check to make sure it is a viable number.
     */
    void placeCall() {
        mLastNumber = mDigits.getText().toString();
        if (PhoneNumberUtils.isEmergencyNumber(mLastNumber)) {
            if (DBG) Log.d(LOG_TAG, "placing call to " + mLastNumber);

            // place the call if it is a valid number
            if (mLastNumber == null || !TextUtils.isGraphic(mLastNumber)) {
                // There is no number entered.
                playTone(ToneGenerator.TONE_PROP_NACK);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY);
            intent.setData(Uri.fromParts("tel", mLastNumber, null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else {
            if (DBG) Log.d(LOG_TAG, "rejecting bad requested number " + mLastNumber);

            // erase the number and throw up an alert dialog.
            mDigits.getText().delete(0, mDigits.getText().length());
            showDialog(BAD_EMERGENCY_NUMBER_DIALOG);
        }
    }


    /**
     * Plays the specified tone for TONE_LENGTH_MS milliseconds.
     *
     * The tone is played locally, using the audio stream for phone calls.
     * Tones are played only if the "Audible touch tones" user preference
     * is checked, and are NOT played if the device is in silent mode.
     *
     * @param tone a tone code from {@link ToneGenerator}
     */
    void playTone(int tone) {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }

        // Also do nothing if the phone is in silent mode.
        // We need to re-check the ringer mode for *every* playTone()
        // call, rather than keeping a local flag that's updated in
        // onResume(), since it's possible to toggle silent mode without
        // leaving the current activity (via the ENDCALL-longpress menu.)
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if ((ringerMode == AudioManager.RINGER_MODE_SILENT)
            || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
            return;
        }

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(LOG_TAG, "playTone: mToneGenerator == null, tone: " + tone);
                return;
            }

            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone, TONE_LENGTH_MS);
        }
    }

    private CharSequence createErrorMessage(String number) {
        if (!TextUtils.isEmpty(number)) {
            return getString(R.string.dial_emergency_error, mLastNumber);
        } else {
            return getText(R.string.dial_emergency_empty_error).toString();
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        AlertDialog dialog = null;
        if (id == BAD_EMERGENCY_NUMBER_DIALOG) {
            // construct dialog
            dialog = new AlertDialog.Builder(this)
                    .setTitle(getText(R.string.emergency_enable_radio_dialog_title))
                    .setMessage(createErrorMessage(mLastNumber))
                    .setPositiveButton(R.string.ok, null)
                    .setCancelable(true).create();

            // blur stuff behind the dialog
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        if (id == BAD_EMERGENCY_NUMBER_DIALOG) {
            AlertDialog alert = (AlertDialog) dialog;
            alert.setMessage(createErrorMessage(mLastNumber));
        }
    }

    /**
     * Triggers haptic feedback (if enabled) for dialer key presses.
     */
    private synchronized void vibrate() {
        if (!mVibrateOn) {
            return;
        }
        if (mVibrator == null) {
            mVibrator = new Vibrator();
        }
        mVibrator.vibrate(mVibratePattern, VIBRATE_NO_REPEAT);
    }

    /**
     * Update the enabledness of the "Dial" and "Backspace" buttons if applicable.
     */
    private void updateDialAndDeleteButtonStateEnabledAttr() {
        if (null != mVoicemailDialAndDeleteRow) {
            final boolean notEmpty = mDigits.length() != 0;

            mDialButton.setEnabled(notEmpty);
            mDelete.setEnabled(notEmpty);
        }
    }

    /**
     * Initialize the vibration parameters.
     * @param r The Resources with the vibration parameters.
     */
    private void initVibrationPattern(Resources r) {
        int[] pattern = null;
        try {
            mVibrateOn = r.getBoolean(R.bool.config_enable_dialer_key_vibration);
            pattern = r.getIntArray(com.android.internal.R.array.config_virtualKeyVibePattern);
            if (null == pattern) {
                Log.e(LOG_TAG, "Vibrate pattern is null.");
                mVibrateOn = false;
            }
        } catch (Resources.NotFoundException nfe) {
            Log.e(LOG_TAG, "Vibrate control bool or pattern missing.", nfe);
            mVibrateOn = false;
        }

        if (!mVibrateOn) {
            return;
        }

        // int[] to long[] conversion.
        mVibratePattern = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            mVibratePattern[i] = pattern[i];
        }
    }

}
