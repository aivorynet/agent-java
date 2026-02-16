package com.aivory.monitor.agent.capture;

import com.aivory.monitor.agent.AgentConfig;
import com.aivory.monitor.agent.model.CapturedVariable;
import com.aivory.monitor.agent.model.StackFrameInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Captures exception context including stack trace and local variables.
 */
public class ExceptionCapture {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionCapture.class);

    private final AgentConfig config;

    private String id;
    private String exceptionType;
    private String message;
    private String fingerprint;
    private String capturedAt;
    private List<StackFrameInfo> stackTrace;
    private Map<String, CapturedVariable> localVariables;
    private Map<String, CapturedVariable> methodArguments;

    public ExceptionCapture(AgentConfig config) {
        this.config = config;
        this.id = UUID.randomUUID().toString();
        this.capturedAt = Instant.now().toString();
        this.stackTrace = new ArrayList<>();
        this.localVariables = new LinkedHashMap<>();
        this.methodArguments = new LinkedHashMap<>();
    }

    /**
     * Captures the exception context.
     */
    public void capture(Throwable thrown, Object target, Method method, Object[] args) {
        this.exceptionType = thrown.getClass().getName();
        this.message = thrown.getMessage();

        // Capture stack trace
        captureStackTrace(thrown);

        // Calculate fingerprint for deduplication
        this.fingerprint = calculateFingerprint(thrown, method);

        // Capture method arguments
        if (args != null) {
            captureMethodArguments(method, args);
        }

        // Capture 'this' object fields if available
        if (target != null) {
            captureObjectFields(target, "this");
        }

        // Debug logging to verify what was captured
        if (config.isDebugEnabled()) {
            LOG.info("[AIVory] Exception captured: {} - {}", exceptionType, message);
            LOG.info("[AIVory] Stack trace frames: {} (original: {})",
                    stackTrace.size(), thrown.getStackTrace().length);
            LOG.info("[AIVory] Method arguments captured: {}", methodArguments.size());
            LOG.info("[AIVory] Local variables captured: {}", localVariables.size());
            LOG.info("[AIVory] Target object (this): {}", target != null ? target.getClass().getName() : "null");
            LOG.info("[AIVory] Method: {}.{}()", method.getDeclaringClass().getName(), method.getName());

            // Log first 5 stack frames for debugging
            for (int i = 0; i < Math.min(5, stackTrace.size()); i++) {
                StackFrameInfo frame = stackTrace.get(i);
                LOG.debug("[AIVory]   Frame {}: {}.{}({}:{})",
                        i, frame.getClassName(), frame.getMethodName(),
                        frame.getFileName(), frame.getLineNumber());
            }
        }
    }

    private void captureStackTrace(Throwable thrown) {
        StackTraceElement[] elements = thrown.getStackTrace();
        int maxFrames = Math.min(elements.length, 50); // Limit stack trace depth

        for (int i = 0; i < maxFrames; i++) {
            StackTraceElement element = elements[i];
            StackFrameInfo frame = new StackFrameInfo();
            frame.setClassName(element.getClassName());
            frame.setMethodName(element.getMethodName());
            frame.setFileName(element.getFileName());
            frame.setLineNumber(element.getLineNumber());
            frame.setNativeMethod(element.isNativeMethod());

            // Try to determine source availability
            frame.setSourceAvailable(element.getFileName() != null && !element.isNativeMethod());

            stackTrace.add(frame);
        }
    }

    private void captureMethodArguments(Method method, Object[] args) {
        // Get parameter names if available
        java.lang.reflect.Parameter[] params = method.getParameters();

        for (int i = 0; i < args.length && i < params.length; i++) {
            String paramName = params[i].isNamePresent()
                    ? params[i].getName()
                    : "arg" + i;

            CapturedVariable var = captureValue(paramName, args[i], 0);
            methodArguments.put(paramName, var);
        }
    }

    private void captureObjectFields(Object obj, String prefix) {
        if (obj == null) return;

        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();

        int capturedCount = 0;
        int maxFields = 20; // Limit number of fields captured

        for (Field field : fields) {
            if (capturedCount >= maxFields) break;

            // Skip static and synthetic fields
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(obj);

                String name = prefix + "." + field.getName();
                CapturedVariable var = captureValue(field.getName(), value, 0);
                localVariables.put(name, var);
                capturedCount++;

            } catch (Exception e) {
                // Ignore fields we can't access
            }
        }
    }

    private CapturedVariable captureValue(String name, Object value, int depth) {
        CapturedVariable var = new CapturedVariable();
        var.setName(name);

        if (value == null) {
            var.setType("null");
            var.setValue("null");
            var.setNull(true);
            return var;
        }

        Class<?> clazz = value.getClass();
        var.setType(clazz.getName());

        // Primitive types and strings
        if (isPrimitiveOrWrapper(clazz) || value instanceof String) {
            String strValue = String.valueOf(value);
            if (strValue.length() > config.getMaxStringLength()) {
                strValue = strValue.substring(0, config.getMaxStringLength());
                var.setTruncated(true);
            }
            var.setValue(strValue);
            return var;
        }

        // Check depth limit
        if (depth >= config.getMaxCaptureDepth()) {
            var.setValue(clazz.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)));
            var.setTruncated(true);
            return var;
        }

        // Arrays
        if (clazz.isArray()) {
            captureArray(var, value, depth);
            return var;
        }

        // Collections
        if (value instanceof Collection) {
            captureCollection(var, (Collection<?>) value, depth);
            return var;
        }

        // Maps
        if (value instanceof Map) {
            captureMap(var, (Map<?, ?>) value, depth);
            return var;
        }

        // Other objects - capture fields
        captureObject(var, value, depth);
        return var;
    }

    private void captureArray(CapturedVariable var, Object array, int depth) {
        int length = java.lang.reflect.Array.getLength(array);
        var.setArrayLength(length);

        List<CapturedVariable> elements = new ArrayList<>();
        int maxElements = Math.min(length, config.getMaxCollectionSize());

        for (int i = 0; i < maxElements; i++) {
            Object element = java.lang.reflect.Array.get(array, i);
            elements.add(captureValue("[" + i + "]", element, depth + 1));
        }

        if (length > maxElements) {
            var.setTruncated(true);
        }

        var.setArrayElements(elements);
        var.setValue(var.getType().replace("[]", "[" + length + "]"));
    }

    private void captureCollection(CapturedVariable var, Collection<?> collection, int depth) {
        int size = collection.size();
        var.setArrayLength(size);

        List<CapturedVariable> elements = new ArrayList<>();
        int count = 0;
        int maxElements = config.getMaxCollectionSize();

        for (Object element : collection) {
            if (count >= maxElements) {
                var.setTruncated(true);
                break;
            }
            elements.add(captureValue("[" + count + "]", element, depth + 1));
            count++;
        }

        var.setArrayElements(elements);
        var.setValue(collection.getClass().getSimpleName() + "<" + size + " items>");
    }

    private void captureMap(CapturedVariable var, Map<?, ?> map, int depth) {
        Map<String, CapturedVariable> children = new LinkedHashMap<>();
        int count = 0;
        int maxEntries = config.getMaxCollectionSize();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= maxEntries) {
                var.setTruncated(true);
                break;
            }

            String key = String.valueOf(entry.getKey());
            if (key.length() > 50) {
                key = key.substring(0, 47) + "...";
            }

            children.put(key, captureValue(key, entry.getValue(), depth + 1));
            count++;
        }

        var.setChildren(children);
        var.setValue(map.getClass().getSimpleName() + "<" + map.size() + " entries>");
    }

    private void captureObject(CapturedVariable var, Object obj, int depth) {
        Map<String, CapturedVariable> children = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();

        Field[] fields = clazz.getDeclaredFields();
        int capturedCount = 0;
        int maxFields = 20;

        for (Field field : fields) {
            if (capturedCount >= maxFields) {
                var.setTruncated(true);
                break;
            }

            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(obj);
                children.put(field.getName(), captureValue(field.getName(), value, depth + 1));
                capturedCount++;
            } catch (Exception e) {
                // Ignore fields we can't access
            }
        }

        var.setChildren(children);
        var.setValue(clazz.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(obj)));
        var.setHashCode(Integer.toHexString(System.identityHashCode(obj)));
    }

    private String calculateFingerprint(Throwable thrown, Method method) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(thrown.getClass().getName());
            sb.append(":");
            sb.append(method.getDeclaringClass().getName());
            sb.append(".");
            sb.append(method.getName());

            // Add first few stack frames
            StackTraceElement[] elements = thrown.getStackTrace();
            int frames = Math.min(elements.length, 5);
            for (int i = 0; i < frames; i++) {
                sb.append(":");
                sb.append(elements[i].getClassName());
                sb.append(".");
                sb.append(elements[i].getMethodName());
                sb.append(":");
                sb.append(elements[i].getLineNumber());
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes());
            return bytesToHex(hash).substring(0, 16);

        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }

    private boolean isPrimitiveOrWrapper(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz == Boolean.class
                || clazz == Character.class
                || clazz == Byte.class
                || clazz == Short.class
                || clazz == Integer.class
                || clazz == Long.class
                || clazz == Float.class
                || clazz == Double.class;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public List<StackFrameInfo> getStackTrace() {
        return stackTrace;
    }

    public Map<String, CapturedVariable> getLocalVariables() {
        return localVariables;
    }

    public Map<String, CapturedVariable> getMethodArguments() {
        return methodArguments;
    }
}
