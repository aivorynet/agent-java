package com.aivory.monitor.agent.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/**
 * Model for a captured variable value.
 */
public class CapturedVariable {

    @SerializedName("name")
    private String name;

    @SerializedName("type")
    private String type;

    @SerializedName("value")
    private String value;

    @SerializedName("is_null")
    private boolean isNull;

    @SerializedName("is_truncated")
    private boolean truncated;

    @SerializedName("children")
    private Map<String, CapturedVariable> children;

    @SerializedName("array_elements")
    private List<CapturedVariable> arrayElements;

    @SerializedName("array_length")
    private int arrayLength;

    @SerializedName("hash_code")
    private String hashCode;

    public CapturedVariable() {
    }

    // Getters and Setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isNull() {
        return isNull;
    }

    public void setNull(boolean aNull) {
        isNull = aNull;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public Map<String, CapturedVariable> getChildren() {
        return children;
    }

    public void setChildren(Map<String, CapturedVariable> children) {
        this.children = children;
    }

    public List<CapturedVariable> getArrayElements() {
        return arrayElements;
    }

    public void setArrayElements(List<CapturedVariable> arrayElements) {
        this.arrayElements = arrayElements;
    }

    public int getArrayLength() {
        return arrayLength;
    }

    public void setArrayLength(int arrayLength) {
        this.arrayLength = arrayLength;
    }

    public String getHashCode() {
        return hashCode;
    }

    public void setHashCode(String hashCode) {
        this.hashCode = hashCode;
    }

    @Override
    public String toString() {
        return "CapturedVariable{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}
