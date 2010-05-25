package com.android.phone;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

public class CallForwardEditPreference extends EditPhoneNumberPreference {
    private static final String LOG_TAG = "CallForwardEditPreference";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private static final String SRC_TAGS[]       = {"{0}"};
    private CharSequence mSummaryOnTemplate;
    private int mButtonClicked;
    private int mServiceClass;
    private MyHandler mHandler = new MyHandler();
    int reason;
    Phone phone;
    CallForwardInfo callForwardInfo;
    TimeConsumingPreferenceListener tcpListener;

    public CallForwardEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        phone = PhoneFactory.getDefaultPhone();
        mSummaryOnTemplate = this.getSummaryOn();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CallForwardEditPreference, 0, R.style.EditPhoneNumberPreference);
        mServiceClass = a.getInt(R.styleable.CallForwardEditPreference_serviceClass,
                CommandsInterface.SERVICE_CLASS_VOICE);
        reason = a.getInt(R.styleable.CallForwardEditPreference_reason,
                CommandsInterface.CF_REASON_UNCONDITIONAL);
        a.recycle();

        if (DBG) Log.d(LOG_TAG, "mServiceClass=" + mServiceClass + ", reason=" + reason);
    }

    public CallForwardEditPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading) {
        tcpListener = listener;
        if (!skipReading) {
            phone.getCallForwardingOption(reason,
                    mHandler.obtainMessage(MyHandler.MESSAGE_GET_CF, reason,
                    MyHandler.MESSAGE_GET_CF, null));
            if (tcpListener != null) {
                tcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        super.onClick(dialog, which);
        mButtonClicked = which;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (DBG) Log.d(LOG_TAG, "mButtonClicked=" + mButtonClicked
                + ", positiveResult=" + positiveResult);
        if (this.mButtonClicked != DialogInterface.BUTTON_NEGATIVE) {
            int action = (isToggled() || (mButtonClicked == DialogInterface.BUTTON_POSITIVE)) ?
                    CommandsInterface.CF_ACTION_REGISTRATION :
                    CommandsInterface.CF_ACTION_DISABLE;
            int time = (reason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;
            final String number = getPhoneNumber();

            if (DBG) Log.d(LOG_TAG, "callForwardInfo=" + callForwardInfo);

            if (action == CommandsInterface.CF_ACTION_REGISTRATION
                    && callForwardInfo != null
                    && callForwardInfo.status == 1
                    && number.equals(callForwardInfo.number)) {
                // no change, do nothing
                if (DBG) Log.d(LOG_TAG, "no change, do nothing");
            } else {
                // set to network
                if (DBG) Log.d(LOG_TAG, "reason=" + reason + ", action=" + action
                        + ", number=" + number);

                // Display no forwarding number while we're waiting for
                // confirmation
                setSummaryOn("");

                // the interface of Phone.setCallForwardingOption has error:
                // should be action, reason...
                phone.setCallForwardingOption(action,
                        reason,
                        number,
                        time,
                        mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF));

                if (tcpListener != null) {
                    tcpListener.onStarted(this, false);
                }
            }
        }
    }

    void handleCallForwardResult(CallForwardInfo cf) {
        callForwardInfo = cf;
        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse done, callForwardInfo=" + callForwardInfo);

        setToggled(callForwardInfo.status == 1);
        setPhoneNumber(callForwardInfo.number);
    }

    private void updateSummaryText() {
        if (isToggled()) {
            CharSequence summaryOn;
            final String number = getRawPhoneNumber();
            if (number != null && number.length() > 0) {
                String values[] = { number };
                summaryOn = TextUtils.replace(mSummaryOnTemplate, SRC_TAGS, values);
            } else {
                summaryOn = getContext().getString(R.string.sum_cfu_enabled_no_number);
            }
            setSummaryOn(summaryOn);
        }

    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_CF = 0;
        private static final int MESSAGE_SET_CF = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CF:
                    handleGetCFResponse(msg);
                    break;
                case MESSAGE_SET_CF:
                    handleSetCFResponse(msg);
                    break;
            }
        }

        private void handleGetCFResponse(Message msg) {
            if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: done");

            if (msg.arg2 == MESSAGE_SET_CF) {
                tcpListener.onFinished(CallForwardEditPreference.this, false);
            } else {
                tcpListener.onFinished(CallForwardEditPreference.this, true);
            }

            AsyncResult ar = (AsyncResult) msg.obj;

            callForwardInfo = null;
            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: ar.exception=" + ar.exception);
                tcpListener.onException(CallForwardEditPreference.this,
                        (CommandException) ar.exception);
            } else {
                if (ar.userObj instanceof Throwable) {
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                }
                CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
                if (cfInfoArray.length == 0) {
                    if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                    setEnabled(false);
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                } else {
                    for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                        if ((mServiceClass & cfInfoArray[i].serviceClass) != 0) {
                            // corresponding class
                            handleCallForwardResult(cfInfoArray[i]);
                        }
                    }
                }
            }

            // Now whether or not we got a new number, reset our enabled
            // summary text since it may have been replaced by an empty
            // placeholder.
            updateSummaryText();
        }

        private void handleSetCFResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: re get");
            phone.getCallForwardingOption(reason,
                    obtainMessage(MESSAGE_GET_CF, reason, MESSAGE_SET_CF, ar.exception));
        }
    }
}
