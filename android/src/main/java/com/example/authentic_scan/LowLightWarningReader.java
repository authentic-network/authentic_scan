package com.example.authentic_scan;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class LowLightWarningReader implements AutoCloseable {

    private final Object mListenerLock = new Object();
    private final Object mCloseLock = new Object();
    private boolean mIsReaderValid = false;
    private OnLowLightWarningAvailableListener mListener;
    private ListenerHandler mListenerHandler;

    private LowLighWarningState state = LowLighWarningState.ok;

    public static LowLightWarningReader newInstance() {
        return new LowLightWarningReader();
    }

    /**
     * Register a listener to be invoked when a new image becomes available
     * from the ImageReader.
     *
     * @param listener
     *            The listener that will be run.
     * @param handler
     *            The handler on which the listener should be invoked, or null
     *            if the listener should be invoked on the calling thread's looper.
     * @throws IllegalArgumentException
     *            If no handler specified and the calling thread has no looper.
     */
    public void setOnLowLightWarningAvailableListener(LowLightWarningReader.OnLowLightWarningAvailableListener listener, Handler handler) {
        synchronized (mListenerLock) {
            if (listener != null) {
                Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
                if (looper == null) {
                    throw new IllegalArgumentException(
                            "handler is null but the current thread is not a looper");
                }
                if (mListenerHandler == null || mListenerHandler.getLooper() != looper) {
                    mListenerHandler = new ListenerHandler(looper);
                }
                mListener = listener;
            } else {
                mListener = null;
                mListenerHandler = null;
            }
        }
    }

    @Override
    public void close() {
        setOnLowLightWarningAvailableListener(null, null);
        synchronized (mCloseLock) {
        }
    }

    public LowLighWarningState acquireLatestLowLightWarning() {
        return this.state;
    }

    public void newLowLighWarningState(LowLighWarningState state) {
        if(!this.state.equals(state)) {
            this.state = state;
            Log.d("newLowLighWarningState", this.state.name());
            if(mListener != null) {
                mListener.onLowLightWarningAvailable(this);
            }
        }
    }

    /**
     * Callback interface for being notified that a new image is available.
     *
     * <p>
     * The onImageAvailable is called per image basis, that is, callback fires for every new frame
     * available from ImageReader.
     * </p>
     */
    public interface OnLowLightWarningAvailableListener {
        void onLowLightWarningAvailable(LowLightWarningReader reader);
    }

    /**
     * This custom handler runs asynchronously so callbacks don't get queued behind UI messages.
     */
    private final class ListenerHandler extends Handler {

        public ListenerHandler(Looper looper) {
            //super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            OnLowLightWarningAvailableListener listener;
            synchronized (mListenerLock) {
                listener = mListener;
            }

            // It's dangerous to fire onImageAvailable() callback when the ImageReader is being
            // closed, as application could acquire next image in the onImageAvailable() callback.
            boolean isReaderValid = false;
            synchronized (mCloseLock) {
                isReaderValid = mIsReaderValid;
            }
            if (listener != null && isReaderValid) {

                listener.onLowLightWarningAvailable(LowLightWarningReader.this);
            }
        }
    }

    public enum LowLighWarningState {
        ok, low, bad
    }
}
