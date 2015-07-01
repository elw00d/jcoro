package org.jcoro;

/**
 * @author elwood
 */
public class TransformResult {
    private final boolean wasModified;
    private final String className;
    private final byte[] data;

    public TransformResult(boolean wasModified, String className, byte[] data) {
        this.wasModified = wasModified;
        this.className = className;
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public String getClassName() {
        return className;
    }

    public boolean wasModified() {
        return wasModified;
    }
}
