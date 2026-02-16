package com.aivory.monitor.agent.capture;

import com.aivory.monitor.agent.AgentConfig;
import com.aivory.monitor.agent.model.CapturedVariable;
import com.aivory.monitor.agent.model.StackFrameInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;

/**
 * Captures context when a breakpoint is hit.
 */
public class BreakpointCapture {

    private final AgentConfig config;

    private String breakpointId;
    private String className;
    private int lineNumber;
    private String capturedAt;
    private List<StackFrameInfo> stackTrace;
    private Map<String, CapturedVariable> localVariables;

    public BreakpointCapture(AgentConfig config) {
        this.config = config;
        this.capturedAt = Instant.now().toString();
        this.stackTrace = new ArrayList<>();
        this.localVariables = new LinkedHashMap<>();
    }

    /**
     * Captures the breakpoint context.
     */
    public void capture(String breakpointId, String className, int lineNumber, Object target, Object[] args) {
        this.breakpointId = breakpointId;
        this.className = className;
        this.lineNumber = lineNumber;

        // Capture stack trace
        captureStackTrace();

        // Capture 'this' object if available
        if (target != null) {
            captureObject(target, "this");
        }

        // Capture arguments
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                captureValue("arg" + i, args[i], 0);
            }
        }
    }

    private void captureStackTrace() {
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();

        // Skip internal frames (getStackTrace, capture, onBreakpointHit, etc.)
        int startIndex = 0;
        for (int i = 0; i < elements.length; i++) {
            String name = elements[i].getClassName();
            if (!name.startsWith("com.aivory.monitor.agent") &&
                !name.startsWith("java.lang.Thread")) {
                startIndex = i;
                break;
            }
        }

        int maxFrames = Math.min(elements.length - startIndex, 50);

        for (int i = startIndex; i < startIndex + maxFrames && i < elements.length; i++) {
            StackTraceElement element = elements[i];
            StackFrameInfo frame = new StackFrameInfo();
            frame.setClassName(element.getClassName());
            frame.setMethodName(element.getMethodName());
            frame.setFileName(element.getFileName());
            frame.setLineNumber(element.getLineNumber());
            frame.setNativeMethod(element.isNativeMethod());
            frame.setSourceAvailable(element.getFileName() != null && !element.isNativeMethod());

            stackTrace.add(frame);
        }
    }

    private void captureObject(Object obj, String prefix) {
        if (obj == null) return;

        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();

        int capturedCount = 0;
        int maxFields = 20;

        for (Field field : fields) {
            if (capturedCount >= maxFields) break;

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
                // Ignore inaccessible fields
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

        // Primitives and strings
        if (isPrimitiveOrWrapper(clazz) || value instanceof String) {
            String strValue = String.valueOf(value);
            if (strValue.length() > config.getMaxStringLength()) {
                strValue = strValue.substring(0, config.getMaxStringLength());
                var.setTruncated(true);
            }
            var.setValue(strValue);
            return var;
        }

        // Depth limit
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

        // Other objects
        captureObjectFields(var, value, depth);
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

        for (Object element : collection) {
            if (count >= config.getMaxCollectionSize()) {
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

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= config.getMaxCollectionSize()) {
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

    private void captureObjectFields(CapturedVariable var, Object obj, int depth) {
        Map<String, CapturedVariable> children = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();

        int capturedCount = 0;

        for (Field field : fields) {
            if (capturedCount >= 20) {
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
                // Ignore
            }
        }

        var.setChildren(children);
        var.setValue(clazz.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(obj)));
        var.setHashCode(Integer.toHexString(System.identityHashCode(obj)));
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

    /**
     * Converts capture to a Map for sending.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("breakpoint_id", breakpointId);
        map.put("class_name", className);
        map.put("line_number", lineNumber);
        map.put("captured_at", capturedAt);
        map.put("stack_trace", stackTrace);
        map.put("local_variables", localVariables);
        return map;
    }

    // Getters

    public String getBreakpointId() {
        return breakpointId;
    }

    public String getClassName() {
        return className;
    }

    public int getLineNumber() {
        return lineNumber;
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
}
