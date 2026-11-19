package dev.denza.hud.accountcheck;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

/**
 * Emulator-only test running under Navigator's real UID; never handles credentials. The Passport
 * SDK's obfuscated names differ per Navigator base: account_check.py passes them from its table
 * (configureClass/configureMethod/configureExtra, typeClass/typeMethod).
 */
public final class AccountAccessCheck extends Instrumentation {
    private Bundle arguments;
    private String expectedType;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        expectedType = arguments.getString("expectedType");
        start();
    }

    private String argument(String name) {
        String value = arguments.getString(name);
        require(value != null && !value.isEmpty(), "Missing argument " + name);
        return value;
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
            // By name: the id moves with every base (0x7f1515b5 in 29.8.1, 0x7f151402 in 31.1.4).
            int id = context.getResources().getIdentifier("passport_account_type", "string", context.getPackageName());
            result.putString("resource_id", Integer.toHexString(id));
            require(id != 0 && expectedType.equals(context.getString(id)), "Resource account type mismatch");
            ClassLoader loader = context.getClassLoader();
            // Exercise the SDK's existing resource configuration path, without bypassing checks:
            // configure(Context[, extra = null]) reads the resource, type() returns what it stored.
            String extra = arguments.getString("configureExtra");
            Class<?> configure = Class.forName(argument("configureClass"), true, loader);
            if (extra == null || extra.isEmpty()) {
                configure.getMethod(argument("configureMethod"), Context.class).invoke(null, context);
            } else {
                configure.getMethod(argument("configureMethod"), Context.class, Class.forName(extra, true, loader))
                        .invoke(null, context, null);
            }
            Object runtimeType = Class.forName(argument("typeClass"), true, loader)
                    .getMethod(argument("typeMethod")).invoke(null);
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
