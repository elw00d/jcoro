package org.jcoro;

import java.util.List;

/**
 * Result of async lambdas searching.
 *
 * @author elwood
 */
public class AsyncLambdaInfo {
    private final String desc;
    private final List<Await> declaredRestorePoints;

    public AsyncLambdaInfo(String desc, List<Await> declaredRestorePoints) {
        this.desc = desc;
        this.declaredRestorePoints = declaredRestorePoints;
    }

    /**
     * Descriptor of method passed to `invokedynamic` instruction.
     * Usually it describes a method accepting some parameters (closures, captured by lambda)
     * and returning instance of functional interface (ICoroRunnable, for example).
     */
    public String getDesc() {
        return desc;
    }

    /**
     * List of restore points parsed from type annotations on lambda.
     */
    public List<Await> getDeclaredRestorePoints() {
        return declaredRestorePoints;
    }
}
