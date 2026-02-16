package com.aivory.monitor.agent.model;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * Model for a single stack frame.
 */
public class StackFrameInfo {

    @SerializedName("class_name")
    private String className;

    @SerializedName("method_name")
    private String methodName;

    @SerializedName("file_name")
    private String fileName;

    @SerializedName("file_path")
    private String filePath;

    @SerializedName("line_number")
    private int lineNumber;

    @SerializedName("column_number")
    private int columnNumber;

    @SerializedName("is_native")
    private boolean nativeMethod;

    @SerializedName("source_available")
    private boolean sourceAvailable;

    @SerializedName("local_variables")
    private Map<String, CapturedVariable> localVariables;

    public StackFrameInfo() {
    }

    // Getters and Setters

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public void setColumnNumber(int columnNumber) {
        this.columnNumber = columnNumber;
    }

    public boolean isNativeMethod() {
        return nativeMethod;
    }

    public void setNativeMethod(boolean nativeMethod) {
        this.nativeMethod = nativeMethod;
    }

    public boolean isSourceAvailable() {
        return sourceAvailable;
    }

    public void setSourceAvailable(boolean sourceAvailable) {
        this.sourceAvailable = sourceAvailable;
    }

    public Map<String, CapturedVariable> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(Map<String, CapturedVariable> localVariables) {
        this.localVariables = localVariables;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (className != null) {
            sb.append(className).append(".");
        }
        sb.append(methodName != null ? methodName : "<unknown>");
        sb.append("(");
        if (fileName != null) {
            sb.append(fileName);
            if (lineNumber > 0) {
                sb.append(":").append(lineNumber);
            }
        } else if (nativeMethod) {
            sb.append("Native Method");
        } else {
            sb.append("Unknown Source");
        }
        sb.append(")");
        return sb.toString();
    }
}
