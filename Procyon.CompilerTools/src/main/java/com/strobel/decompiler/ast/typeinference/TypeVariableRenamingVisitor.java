package com.strobel.decompiler.ast.typeinference;

import com.strobel.assembler.metadata.*;
import com.strobel.core.CollectionUtilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TypeVariableRenamingVisitor extends DefaultTypeVisitor<Void, TypeReference> { // TODO: use captured types instead of renaming?
    public Map<GenericParameter, GenericParameter> renamedParameters = new HashMap<>();
    public static Map<String, Integer> counters = new HashMap<>();

    @Override
    public TypeReference visit(final TypeReference t, Void v) {
        return t.accept(this, null);
    }

    @Override
    public TypeReference visitArrayType(final ArrayType t, Void v) {
        return visit(t.getElementType(), null).makeArrayType();
    }

    public int getNextCounter(String name) {
        if (Character.isDigit(name.charAt(name.length() - 1))) {
            name = name + "_";
        }

        int result = counters.getOrDefault(name, 0);
        counters.put(name, result + 1);
        return result;
    }

    @Override
    public TypeReference visitGenericParameter(GenericParameter t, Void v) {
        GenericParameter renamed = renamedParameters.get(t);

        if (renamed == null) {
            renamed = new GenericParameter(t.getName() + String.valueOf(getNextCounter(t.getName())));

            // Add before visiting extends bound to avoid infinite recursion (for example, with 'T extends Comparable<T>')
            renamedParameters.put(t, renamed);

            if (t.hasExtendsBound()) {
                renamed.setExtendsBound(visit(t.getExtendsBound()));
            }
        }

        return renamed;
    }

    @Override
    public TypeReference visitWildcard(WildcardType t, Void v) {
        if (t.hasExtendsBound()) {
            return WildcardType.makeExtends(visit(t.getExtendsBound(), null));
        } else if (t.hasSuperBound()) {
            return WildcardType.makeSuper(visit(t.getSuperBound(), null));
        } else {
            return t;
        }
    }

    @Override
    public TypeReference visitCompoundType(final CompoundTypeReference t, Void v) {
        return new CompoundTypeReference(t.getBaseType() == null ? visit(t.getBaseType(), null) : null,
                t.getInterfaces() != null ? CollectionUtilities.map(t.getInterfaces(), i -> visit(i, null)) : null);
    }

    @Override
    public TypeReference visitParameterizedType(final TypeReference t, Void v) {
        return t.makeGenericType(CollectionUtilities.map(((IGenericInstance) t).getTypeArguments(), arg -> visit(arg, null)));
    }

    @Override
    public TypeReference visitPrimitiveType(final PrimitiveType t, Void v) {
        return t;
    }

    @Override
    @SuppressWarnings("unchecked")
    public TypeReference visitClassType(final TypeReference t, Void v) {
        final TypeReference resolvedType = t.isGenericType() ? t : t.resolve();
        final List<TypeReference> typeArguments = (List<TypeReference>) (Object) resolvedType.getGenericParameters();

        return typeArguments.isEmpty() ? t : t.makeGenericType(CollectionUtilities.map(typeArguments, arg -> visit(arg, null)));
    }

    @Override
    public TypeReference visitNullType(final TypeReference t, Void v) {
        return t;
    }

    @Override
    public TypeReference visitBottomType(final TypeReference t, Void v) {
        return t;
    }

    @Override
    public TypeReference visitRawType(final RawType t, Void v) {
        return t;
    }
}
