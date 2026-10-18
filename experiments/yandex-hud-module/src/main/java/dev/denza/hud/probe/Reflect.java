package dev.denza.hud.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Cached, strict bridge to the pinned APK; no bundled Yandex classes or SDK/API keys. Main thread only. */
final class Reflect {
    private final Map<String, Method> methods = new HashMap<>();
    private final Map<String, Constructor<?>> constructors = new HashMap<>();

    Class<?> type(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException error) { throw new IllegalStateException(name, error); }
    }

    Object call(Object target, String name, Object... arguments) {
        Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
        String key = key(owner, name, arguments);
        Method method = methods.get(key);
        if (method == null) {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(name) && matches(candidate.getParameterTypes(), arguments)) {
                    if (method != null) throw new IllegalStateException("Ambiguous method " + key);
                    method = candidate;
                }
            }
            if (method == null) throw new IllegalStateException("Missing method " + key);
            methods.put(key, method);
        }
        try { return method.invoke(target instanceof Class ? null : target, arguments); }
        catch (ReflectiveOperationException error) { throw failure(key, error); }
    }

    Object make(String name, Object... arguments) {
        Class<?> owner = type(name);
        String key = key(owner, "new", arguments);
        Constructor<?> constructor = constructors.get(key);
        if (constructor == null) {
            for (Constructor<?> candidate : owner.getConstructors()) {
                if (matches(candidate.getParameterTypes(), arguments)) {
                    if (constructor != null) throw new IllegalStateException("Ambiguous constructor " + key);
                    constructor = candidate;
                }
            }
            if (constructor == null) throw new IllegalStateException("Missing constructor " + key);
            constructors.put(key, constructor);
        }
        try { return constructor.newInstance(arguments); }
        catch (ReflectiveOperationException error) { throw failure(key, error); }
    }

    private static RuntimeException failure(String key, ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
        return new IllegalStateException(key, cause);
    }

    private static String key(Class<?> owner, String name, Object[] arguments) {
        StringBuilder result = new StringBuilder(owner.getName()).append('#').append(name);
        for (Object value : arguments) result.append('/').append(value == null ? "null" : value.getClass().getName());
        return result.toString();
    }

    private static boolean matches(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (values[i] == null) { if (type.isPrimitive()) return false; continue; }
            if (type == int.class) type = Integer.class;
            else if (type == long.class) type = Long.class;
            else if (type == float.class) type = Float.class;
            else if (type == double.class) type = Double.class;
            else if (type == boolean.class) type = Boolean.class;
            if (!type.isInstance(values[i])) return false;
        }
        return true;
    }
}
