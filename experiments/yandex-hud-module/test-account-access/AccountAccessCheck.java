package dev.denza.hud.accountcheck;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

/** Emulator-only test running under Navigator's real UID; never handles credentials. */
public final class AccountAccessCheck extends Instrumentation {
    private String expectedType;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        expectedType = arguments.getString("expectedType");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        AccountManager manager = null;
        Account owned = null;
        boolean added = false;
        int code = Activity.RESULT_CANCELED;
        try {
            require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"), "Emulator only");
            Context context = getTargetContext();
            require("ru.yandex.yandexnavi".equals(context.getPackageName()), "Wrong target package");
            require("com.yandex.passport".equals(expectedType)
                    || "com.yandex.passport.denza".equals(expectedType), "Unexpected test account type");
            require(expectedType.equals(context.getString(0x7f1515b5)), "Resource account type mismatch");
            ClassLoader loader = context.getClassLoader();
            // Exercise the SDK's existing resource configuration path, without bypassing checks.
            Class.forName("com.yandex.passport.internal.e0", true, loader)
                    .getMethod("b", Context.class).invoke(null, context);
            Object runtimeType = Class.forName("com.yandex.passport.internal.t", true, loader)
                    .getMethod("a").invoke(null);
            require(expectedType.equals(runtimeType), "Passport SDK account type mismatch");
            result.putString("resource_and_sdk_type", expectedType);
            manager = AccountManager.get(context);
            Account foreign = new Account("denza-hud-account-check", "com.yandex.passport");
            boolean denied = false;
            try {
                boolean unexpectedAdd = manager.addAccountExplicitly(foreign, null, null);
                if (unexpectedAdd) manager.removeAccountExplicitly(foreign);
            } catch (SecurityException expected) {
                denied = true;
            }
            require(denied, "Foreign authenticator must reject this signer");
            result.putBoolean("foreign_type_denied", true);
            if ("com.yandex.passport.denza".equals(expectedType)) {
                owned = new Account("denza-hud-account-check", expectedType);
                added = manager.addAccountExplicitly(owned, null, null);
                require(added, "Own account could not be added");
                result.putBoolean("own_account_added", true);
            }
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("error", error.toString());
        } finally {
            if (added) {
                boolean removed = manager.removeAccountExplicitly(owned);
                result.putBoolean("own_account_removed", removed);
                if (!removed) code = Activity.RESULT_CANCELED;
            }
            finish(code, result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
