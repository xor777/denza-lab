package dev.denza.tools;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/** One-shot client for BYD AutoVoice's exported text-automation input. */
public final class FseVoiceCommandProbe extends Activity {
    private static final String TAG = "FseVoiceCommandProbe";
    private static final String SERVICE_PACKAGE = "com.byd.autovoice";
    private static final String SERVICE_CLASS =
            "com.byd.autovoice_common.utils.automated.service.AutomataTestService";
    private static final String DESCRIPTOR =
            "com.byd.autovoice.automata.AutomatedExecuteAidlInterface";
    private static final String COMMAND_SEPARATOR = "\n---\n";

    private ServiceConnection connection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String encodedCommand = getIntent().getStringExtra("command_base64");
        if (encodedCommand == null) {
            Log.e(TAG, "missing command_base64");
            finish();
            return;
        }
        String command = new String(Base64.decode(encodedCommand, Base64.DEFAULT),
                StandardCharsets.UTF_8);
        String[] commands = command.split(COMMAND_SEPARATOR, -1);

        Intent initialize = new Intent("com.byd.AUTOMATED_TEST_TASKS")
                .setPackage(SERVICE_PACKAGE)
                .putExtra("TEST_TASKS", "6");
        sendBroadcast(initialize);
        new Handler(getMainLooper()).postDelayed(() -> send(commands, 0), 500L);
    }

    private void send(String[] commands, int index) {
        bind(commands[index], () -> {
            int next = index + 1;
            if (next < commands.length) {
                new Handler(getMainLooper()).postDelayed(() -> send(commands, next), 3000L);
            } else {
                finish();
            }
        });
    }

    private void bind(String command, Runnable onSent) {
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Parcel request = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken(DESCRIPTOR);
                    request.writeInt(0);
                    request.writeString(command);
                    if (!service.transact(1, request, reply, 0)) {
                        throw new IllegalStateException("AutoVoice rejected transaction 1");
                    }
                    reply.readException();
                    Log.i(TAG, "sent: " + command);
                } catch (Throwable throwable) {
                    Log.e(TAG, "AutoVoice command failed", throwable);
                } finally {
                    reply.recycle();
                    request.recycle();
                    unbindService(connection);
                    onSent.run();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.e(TAG, "AutoVoice service disconnected");
                finish();
            }
        };

        Intent serviceIntent = new Intent().setComponent(new ComponentName(
                SERVICE_PACKAGE, SERVICE_CLASS));
        if (!bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "could not bind AutoVoice automation service");
            finish();
        }
    }
}
