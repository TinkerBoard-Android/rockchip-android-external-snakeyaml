/*
 * See LICENSE file in distribution for copyright and licensing information.
 */
package org.yaml.snakeyaml.constructor;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.FieldProperty;
import org.yaml.snakeyaml.introspector.MethodProperty;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tags;

/**
 * @see <a href="http://pyyaml.org/wiki/PyYAML">PyYAML</a> for more information
 */
public class Constructor extends SafeConstructor {
    private final Map<String, Class<? extends Object>> typeTags;
    private final Map<Class<? extends Object>, TypeDescription> typeDefinitions;

    public Constructor() {
        this(Object.class);
    }

    public Constructor(Class<? extends Object> theRoot) {
        if (theRoot == null) {
            throw new NullPointerException("Root type must be provided.");
        }
        this.yamlConstructors.put(null, new ConstructYamlObject());
        rootType = theRoot;
        typeTags = new HashMap<String, Class<? extends Object>>();
        typeDefinitions = new HashMap<Class<? extends Object>, TypeDescription>();
        yamlClassConstructors.put(NodeId.scalar, new ConstructScalarObject());
        yamlClassConstructors.put(NodeId.mapping, new ConstructMappingFromClass());
        yamlClassConstructors.put(NodeId.sequence, new ConstructSeqFromClass());
    }

    /**
     * Create Constructor for a class which does not have to be in the classpath
     * or for a definition from a Spring ApplicationContext.
     * 
     * @param theRoot
     *            fully qualified class name of the root JavaBean
     * @throws ClassNotFoundException
     */
    public Constructor(String theRoot) throws ClassNotFoundException {
        this(Class.forName(check(theRoot)));
    }

    private static final String check(String s) {
        if (s == null) {
            throw new NullPointerException("Root type must be provided.");
        }
        if (s.trim().length() == 0) {
            throw new YAMLException("Root type must be provided.");
        }
        return s;
    }

    /**
     * Make YAML aware how to parse a custom Class. If there is no root Class
     * assigned in constructor then the 'root' property of this definition is
     * respected.
     * 
     * @param definition
     *            to be added to the Constructor
     * @return the previous value associated with <tt>definition</tt>, or
     *         <tt>null</tt> if there was no mapping for <tt>definition</tt>.
     */
    public TypeDescription addTypeDescription(TypeDescription definition) {
        if (definition == null) {
            throw new NullPointerException("TypeDescription is required.");
        }
        if (rootType == Object.class && definition.isRoot()) {
            rootType = definition.getType();
        }
        String tag = definition.getTag();
        typeTags.put(tag, definition.getType());
        return typeDefinitions.put(definition.getType(), definition);
    }

    private class ConstructMappingFromClass implements Construct {
        /**
         * Construct JavaBean. If type safe collections are used please look at
         * <code>TypeDescription</code>.
         * 
         * @param node
         *            node where the keys are property names (they can only be
         *            <code>String</code>s) and values are objects to be created
         * @return constructed JavaBean
         */
        public Object construct(Node node) {
            MappingNode mnode = (MappingNode) node;
            if (Map.class.isAssignableFrom(node.getType())) {
                if (node.isTwoStepsConstruction()) {
                    // TODO when the Map implementation is known it should be
                    // used
                    return createDefaultMap();
                } else {
                    return constructMapping((MappingNode) node);
                }
            } else if (Set.class.isAssignableFrom(node.getType())) {
                if (node.isTwoStepsConstruction()) {
                    // TODO when the Set implementation is known it should be
                    // used
                    return createDefaultSet();
                } else {
                    return constructSet((MappingNode) node);
                }
            } else {
                if (node.isTwoStepsConstruction()) {
                    return createEmptyJavaBean(mnode);
                } else {
                    return constructJavaBean2ndStep(mnode, createEmptyJavaBean(mnode));
                }
            }
        }

        @SuppressWarnings("unchecked")
        public void construct2ndStep(Node node, Object object) {
            if (Map.class.isAssignableFrom(node.getType())) {
                constructMapping2ndStep((MappingNode) node, (Map<Object, Object>) object);
            } else if (Set.class.isAssignableFrom(node.getType())) {
                constructSet2ndStep((MappingNode) node, (Set<Object>) object);
            } else {
                constructJavaBean2ndStep((MappingNode) node, object);
            }
        }

        private Object createEmptyJavaBean(MappingNode node) {
            try {
                Class<? extends Object> type = node.getType();
                if (Modifier.isAbstract(type.getModifiers())) {
                    node.setType(getClassForNode(node));
                }
                /**
                 * Using only default constructor. Everything else will be
                 * initialized on 2nd step. If we do here some partial
                 * initialization, how do we then track what need to be done on
                 * 2nd step? I think it is better to get only object here (to
                 * have it as reference for recursion) and do all other thing on
                 * 2nd step.
                 */
                return node.getType().newInstance();
            } catch (InstantiationException e) {
                throw new YAMLException(e);
            } catch (IllegalAccessException e) {
                throw new YAMLException(e);
            } catch (ClassNotFoundException e) {
                throw new YAMLException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private Object constructJavaBean2ndStep(MappingNode node, Object object) {
            Class<? extends Object> beanType = node.getType();
            List<NodeTuple> nodeValue = (List<NodeTuple>) node.getValue();
            for (NodeTuple tuple : nodeValue) {
                ScalarNode keyNode;
                if (tuple.getKeyNode() instanceof ScalarNode) {
                    // key must be scalar
                    keyNode = (ScalarNode) tuple.getKeyNode();
                } else {
                    throw new YAMLException("Keys must be scalars but found: " + tuple.getKeyNode());
                }
                Node valueNode = tuple.getValueNode();
                // keys can only be Strings
                keyNode.setType(String.class);
                String key = (String) constructObject(keyNode);
                boolean isArray = false;
                try {
                    Property property = getProperty(beanType, key);
                    valueNode.setType(property.getType());
                    TypeDescription memberDescription = typeDefinitions.get(beanType);
                    if (memberDescription != null) {
                        switch (valueNode.getNodeId()) {
                        case sequence:
                            SequenceNode snode = (SequenceNode) valueNode;
                            Class<? extends Object> memberType = memberDescription
                                    .getListPropertyType(key);
                            if (memberType != null) {
                                snode.setListType(memberType);
                            } else if (property.getType().isArray()) {
                                isArray = true;
                                snode.setListType(property.getType().getComponentType());
                            }
                            break;
                        case mapping:
                            MappingNode mnode = (MappingNode) valueNode;
                            Class<? extends Object> keyType = memberDescription.getMapKeyType(key);
                            if (keyType != null) {
                                mnode.setKeyType(keyType);
                                mnode.setValueType(memberDescription.getMapValueType(key));
                            }
                            break;
                        }
                    }
                    Object value = constructObject(valueNode);
                    if (isArray) {
                        List<Object> list = (List<Object>) value;
                        value = list.toArray(createArray(property.getType()));
                    }
                    property.set(object, value);
                } catch (Exception e) {
                    // TODO use more informative error message, mention
                    // property
                    // name
                    System.err.println("key: " + key + "; valueNode=" + valueNode);
                    throw new YAMLException(e);
                }
            }
            return object;
        }

        @SuppressWarnings("unchecked")
        private <T> T[] createArray(Class<T> type) {
            return (T[]) Array.newInstance(type.getComponentType(), 0);
        }

        private Property getProperty(Class<? extends Object> type, String name)
                throws IntrospectionException {
            for (PropertyDescriptor property : Introspector.getBeanInfo(type)
                    .getPropertyDescriptors()) {
                if (property.getName().equals(name)) {
                    if (property.getWriteMethod() != null) {
                        return new MethodProperty(property);
                    } else {
                        throw new YAMLException("Property '" + name + "' on JavaBean: "
                                + type.getName() + " does not have the write method");
                    }
                }
            }
            for (Field field : type.getFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (field.getName().equals(name)) {
                    return new FieldProperty(field);
                }
            }
            throw new YAMLException("Unable to find property '" + name + "' on class: "
                    + type.getName());
        }
    }

    private class ConstructYamlObject extends AbstractConstruct {
        @SuppressWarnings("unchecked")
        public Object construct(Node node) {
            Object result = null;
            try {
                Class cl = getClassForNode(node);
                node.setType(cl);
                Construct constructor = yamlClassConstructors.get(node.getNodeId());
                result = constructor.construct(node);
            } catch (Exception e) {
                throw new ConstructorException(null, null, "Can't construct a java object for "
                        + node.getTag() + "; exception=" + e.getMessage(), node.getStartMark(), e);
            }
            return result;
        }
    }

    /**
     * Construct scalar instance when the runtime class is known. Recursive
     * structures are not supported.
     */
    private class ConstructScalarObject extends AbstractConstruct {
        @SuppressWarnings("unchecked")
        public Object construct(Node nnode) {
            ScalarNode node = (ScalarNode) nnode;
            Class type = node.getType();
            Object result;
            if (type.isPrimitive() || type == String.class || Number.class.isAssignableFrom(type)
                    || type == Boolean.class || Date.class.isAssignableFrom(type)
                    || type == Character.class || type == BigInteger.class
                    || Enum.class.isAssignableFrom(type)) {
                // standard classes created directly
                result = constructStandardJavaInstance(type, node);
            } else {
                // there must be only 1 constructor with 1 argument
                java.lang.reflect.Constructor[] javaConstructors = type.getConstructors();
                boolean found = false;
                java.lang.reflect.Constructor javaConstructor = null;
                for (java.lang.reflect.Constructor c : javaConstructors) {
                    if (c.getParameterTypes().length == 1) {
                        if (found) {
                            throw new YAMLException(
                                    "More then 1 constructor with 1 argument found for " + type);
                        }
                        found = true;
                        javaConstructor = c;
                    }
                }
                if (javaConstructor == null) {
                    throw new YAMLException("No single argument constructor found for " + type);
                } else {
                    Object argument = constructStandardJavaInstance(javaConstructor
                            .getParameterTypes()[0], node);
                    try {
                        result = javaConstructor.newInstance(argument);
                    } catch (Exception e) {
                        throw new ConstructorException(null, null,
                                "Can't construct a java object for scalar " + node.getTag()
                                        + "; exception=" + e.getMessage(), node.getStartMark(), e);
                    }
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private Object constructStandardJavaInstance(Class type, ScalarNode node) {
            Object result;
            if (type == String.class) {
                Construct stringConstructor = yamlConstructors.get(Tags.STR);
                result = stringConstructor.construct((ScalarNode) node);
            } else if (type == Boolean.class || type == Boolean.TYPE) {
                Construct boolConstructor = yamlConstructors.get(Tags.BOOL);
                result = boolConstructor.construct((ScalarNode) node);
            } else if (type == Character.class || type == Character.TYPE) {
                Construct charConstructor = yamlConstructors.get(Tags.STR);
                String ch = (String) charConstructor.construct((ScalarNode) node);
                if (ch.length() == 0) {
                    result = null;
                } else if (ch.length() != 1) {
                    throw new YAMLException("Invalid node Character: '" + ch + "'; length: "
                            + ch.length());
                } else {
                    result = new Character(ch.charAt(0));
                }
            } else if (Date.class.isAssignableFrom(type)) {
                Construct dateConstructor = yamlConstructors.get(Tags.TIMESTAMP);
                Date date = (Date) dateConstructor.construct((ScalarNode) node);
                if (type == Date.class) {
                    result = date;
                } else {
                    try {
                        java.lang.reflect.Constructor<?> constr = type.getConstructor(long.class);
                        result = constr.newInstance(date.getTime());
                    } catch (Exception e) {
                        throw new YAMLException("Cannot construct: '" + type + "'");
                    }
                }
            } else if (type == Float.class || type == Double.class || type == Float.TYPE
                    || type == Double.TYPE || type == BigDecimal.class) {
                Construct doubleConstructor = yamlConstructors.get(Tags.FLOAT);
                result = doubleConstructor.construct(node);
                if (type == Float.class || type == Float.TYPE) {
                    result = new Float((Double) result);
                } else if (type == BigDecimal.class) {
                    result = new BigDecimal(((Double) result).doubleValue());
                }
            } else if (type == Byte.class || type == Short.class || type == Integer.class
                    || type == Long.class || type == BigInteger.class || type == Byte.TYPE
                    || type == Short.TYPE || type == Integer.TYPE || type == Long.TYPE) {
                Construct intConstructor = yamlConstructors.get(Tags.INT);
                result = intConstructor.construct(node);
                if (type == Byte.class || type == Byte.TYPE) {
                    result = new Byte(result.toString());
                } else if (type == Short.class || type == Short.TYPE) {
                    result = new Short(result.toString());
                } else if (type == Integer.class || type == Integer.TYPE) {
                    result = new Integer(result.toString());
                } else if (type == Long.class || type == Long.TYPE) {
                    result = new Long(result.toString());
                } else {
                    // only BigInteger left
                    result = new BigInteger(result.toString());
                }
            } else if (Enum.class.isAssignableFrom(type)) {
                String enumValueName = node.getValue();
                try {
                    result = Enum.valueOf(type, enumValueName);
                } catch (Exception ex) {
                    throw new YAMLException("Unable to find enum value '" + enumValueName
                            + "' for enum class: " + type.getName());
                }
            } else {
                throw new YAMLException("Unsupported class: " + type);
            }
            return result;
        }
    }

    private class ConstructSeqFromClass implements Construct {

        @SuppressWarnings("unchecked")
        public Object construct(Node node) {
            SequenceNode snode = (SequenceNode) node;
            if (List.class.isAssignableFrom(node.getType()) || node.getType().isArray()) {
                if (node.isTwoStepsConstruction()) {
                    return createDefaultList(snode.getValue().size());
                } else {
                    return constructSequence(snode);
                }
            } else {
                // create immutable object
                List<Object> argumentList = (List<Object>) constructSequence(snode);
                Class[] parameterTypes = new Class[argumentList.size()];
                int index = 0;
                for (Object parameter : argumentList) {
                    parameterTypes[index] = parameter.getClass();
                    index++;
                }
                java.lang.reflect.Constructor javaConstructor;
                try {
                    Class cl = getClassForNode(node);
                    javaConstructor = cl.getConstructor(parameterTypes);
                    Object[] initargs = argumentList.toArray();
                    return javaConstructor.newInstance(initargs);
                } catch (Exception e) {
                    throw new YAMLException(e);
                }
            }
        }

        @SuppressWarnings("unchecked")
        public void construct2ndStep(Node node, Object object) {
            SequenceNode snode = (SequenceNode) node;
            List<Object> list = (List<Object>) object;
            if (List.class.isAssignableFrom(node.getType())) {
                constructSequenceStep2(snode, list);
            } else {
                // TODO support arrays
                throw new UnsupportedOperationException("Immutable objects cannot be recursive.");
            }
        }
    }

    private Class<?> getClassForNode(Node node) throws ClassNotFoundException {
        Class<? extends Object> customTag = typeTags.get(node.getTag());
        if (customTag == null) {
            if (node.getTag().length() < Tags.PREFIX.length()) {
                throw new YAMLException("Unknown tag: " + node.getTag());
            }
            String name = node.getTag().substring(Tags.PREFIX.length());
            Class<?> cl = Class.forName(name);
            typeTags.put(node.getTag(), cl);
            return cl;
        } else {
            return customTag;
        }
    }
}
