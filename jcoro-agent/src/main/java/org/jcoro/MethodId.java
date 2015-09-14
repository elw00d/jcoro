package org.jcoro;

/**
 * @author elwood
 */
public class MethodId {
    public final String className;
    public final String methodName; // mangled ?
    public final String signature;

    public MethodId(String className, String methodName, String signature) {
        if (className == null) throw new IllegalArgumentException("className shouldn't be null");
        if (methodName == null) throw new IllegalArgumentException("methodName shouldn't be null");
        if (signature == null) throw new IllegalArgumentException("signature shouldn't be null");
        this.className = className;
        this.methodName = methodName;
        this.signature = signature;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MethodId methodId = (MethodId) o;

        if (!className.equals(methodId.className)) return false;
        if (!methodName.equals(methodId.methodName)) return false;
        return signature.equals(methodId.signature);

    }

    @Override
    public int hashCode() {
        int result = className.hashCode();
        result = 31 * result + methodName.hashCode();
        result = 31 * result + signature.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s.%s [%s]", className, methodName, signature);
    }
}
