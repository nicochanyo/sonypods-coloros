package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * LibXposed Service API 102 ContentProvider receiving IXposedService binder from LSPosed daemon.
 */
public final class XposedProvider extends ContentProvider {
    private static final String METHOD_SEND_BINDER = "SendBinder";
    private static final String KEY_BINDER = "binder";
    private static final String INTERFACE_TOKEN = "io.github.libxposed.service.IXposedService";
    private static final int TX_REQUEST_REMOTE_PREFS = 0x15;
    private static final int TX_UPDATE_REMOTE_PREFS = 0x16;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile IBinder sBinder;
    private static volatile Runnable sConnectionListener;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_SEND_BINDER.equals(method) || extras == null) {
            return null;
        }
        final IBinder binder = extras.getBinder(KEY_BINDER);
        if (binder == null) {
            return new Bundle();
        }
        synchronized (LOCK) {
            sBinder = binder;
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (LOCK) {
                            if (sBinder == binder) {
                                sBinder = null;
                            }
                        }
                        notifyListener();
                    }
                }, 0);
            } catch (Throwable ignored) {
            }
        }
        notifyListener();
        return new Bundle();
    }

    public static boolean isConnected() {
        IBinder b = sBinder;
        return b != null && b.isBinderAlive();
    }

    public static void setConnectionListener(Runnable listener) {
        sConnectionListener = listener;
        if (listener != null && isConnected()) {
            MAIN_HANDLER.post(listener);
        }
    }

    private static void notifyListener() {
        final Runnable listener = sConnectionListener;
        if (listener != null) {
            MAIN_HANDLER.post(listener);
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    public static Map<String, Object> fetchRemotePrefs(String group) {
        IBinder binder = sBinder;
        if (binder == null || !binder.isBinderAlive() || group == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN);
            data.writeString(group);
            binder.transact(TX_REQUEST_REMOTE_PREFS, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                return null;
            }
            Bundle bundle = Bundle.CREATOR.createFromParcel(reply);
            if (bundle == null) {
                return null;
            }
            bundle.setClassLoader(XposedProvider.class.getClassLoader());
            Object mapObj = bundle.getSerializable("map");
            if (mapObj instanceof Map<?, ?>) {
                return new HashMap<>((Map<String, Object>) mapObj);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean pushRemotePrefs(String group, Map<String, Object> values) {
        IBinder binder = sBinder;
        if (binder == null || !binder.isBinderAlive() || group == null || values == null) {
            return false;
        }
        Bundle diff = new Bundle();
        diff.putBoolean("clear", false);
        diff.putSerializable("delete", new HashSet<String>());
        diff.putSerializable("put", new HashMap<String, Object>(values));

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN);
            data.writeString(group);
            data.writeInt(1);
            diff.writeToParcel(data, 0);
            binder.transact(TX_UPDATE_REMOTE_PREFS, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
