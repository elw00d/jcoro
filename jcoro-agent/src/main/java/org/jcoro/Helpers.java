package org.jcoro;

import org.objectweb.asm.tree.AnnotationNode;

import java.lang.annotation.Annotation;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * @author elwood
 */
public class Helpers {
    public static List<Await> parseAwaitAnnotations(List<AnnotationNode> restorePoints) {
        return restorePoints.stream().map(annotationNode -> {
            String value = "";
            String desc = "";
            boolean patchable = true;
            String owner = "";
            for (int i = 0; i < annotationNode.values.size(); i+= 2) {
                final String name = (String) annotationNode.values.get(i);
                switch (name) {
                    case "value": {
                        value = (String) annotationNode.values.get(i + 1);
                        break;
                    }
                    case "desc": {
                        desc = (String) annotationNode.values.get(i + 1);
                        break;
                    }
                    case "owner": {
                        owner = (String) annotationNode.values.get(i + 1);
                        break;
                    }
                    case "patchable": {
                        patchable = (Boolean) annotationNode.values.get(i + 1);
                        break;
                    }
                    default:{
                        throw new UnsupportedOperationException("Unknown @Await property: " + name);
                    }
                }
            }
            final String _value = value;
            final String _desc = desc;
            final String _owner = owner;
            final boolean _patchable = patchable;
            return new Await() {
                @Override
                public String value() {
                    return _value;
                }

                @Override
                public String desc() {
                    return _desc;
                }

                @Override
                public String owner() {
                    return _owner;
                }

                @Override
                public boolean patchable() {
                    return _patchable;
                }

                @Override
                public Class<? extends Annotation> annotationType() {
                    return Await.class;
                }
            };
        }).collect(toList());
    }
}
