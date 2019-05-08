package com.strobel.decompiler.ast.typeinference;

import com.strobel.assembler.metadata.GenericParameter;
import com.strobel.assembler.metadata.MetadataHelper;
import com.strobel.assembler.metadata.TypeReference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ConstraintSolver {
    private final long timeLimit = System.currentTimeMillis() + 7000;
    private Map<Object, EquivalenceSet> equivalences = new LinkedHashMap<>();
    private Map<TypeReference, TypeReference> genericSubstitutions = new HashMap<>();
    private Set<TypeReference> types = new HashSet<>();

    public boolean addExtends(Object e1, Object e2) { // TODO: allow e1 and e2 to be constraint sets
        checkTime();

        e1 = processObject(e1);
        e2 = processObject(e2);
        if (e1.equals(e2)) return false;
        print("type(" + e1 + ") <= type(" + e2 + ")");

        EquivalenceSet s1 = getEquivalenceSet(e1);
        EquivalenceSet s2 = getEquivalenceSet(e2);

        return s1.supertypes.add(s2) | s2.subtypes.add(s1);
    }

    public boolean addEquality(Object e1, Object e2) { // TODO: allow e1 and e2 to be constraint sets
        checkTime();

        e1 = processObject(e1);
        e2 = processObject(e2);
        if (e1.equals(e2)) return false;
        print("type(" + e1 + ") = type(" + e2 + ")");

        // Handle generic parameter equality (substitute its use everywhere)
        if (e1 instanceof TypeReference && e2 instanceof TypeReference) {
            if (e1 instanceof GenericParameter && ((GenericParameter) e1).getOwner() == null) {
                return addGenericSubstitution((GenericParameter) e1, (TypeReference) e2);
            } else if (e2 instanceof GenericParameter && ((GenericParameter) e2).getOwner() == null) {
                return addGenericSubstitution((GenericParameter) e2, (TypeReference) e1);
            }
        }

        // Handle normal equality
        EquivalenceSet s1 = equivalences.get(e1);
        EquivalenceSet s2 = equivalences.get(e2);

        if (s1 == null && s2 == null) {
            EquivalenceSet info = new EquivalenceSet();
            info.add(e1);
            info.add(e2);
            equivalences.put(e1, info);
            equivalences.put(e2, info);
        } else if (s1 == s2) {
            return false;
        } else if (s2 == null) {
            s1.add(e2);
            equivalences.put(e2, s1);
        } else if (s1 == null) {
            s2.add(e1);
            equivalences.put(e1, s2);
        } else {
            mergeSets(s1, s2);
        }

        return true;
    }

    private void checkTime() {
        if (System.currentTimeMillis() > timeLimit) {
            throw new IllegalStateException("Took too long");
        }
    }

    private EquivalenceSet mergeSets(EquivalenceSet into, EquivalenceSet from) {
        for (Object o : from.getObjects()) {
            into.add(o);
            equivalences.put(o, into);
        }

        // Merge supertypes and subtypes
        into.supertypes.addAll(from.supertypes);
        into.supertypes.remove(from);
        into.subtypes.addAll(from.subtypes);
        into.subtypes.remove(from);

        // Prevent sets extending themselves
        into.supertypes.remove(into);
        into.subtypes.remove(into);

        for (EquivalenceSet set : getEquivalenceSets()) {
            if (set.supertypes.remove(from)) {
                set.supertypes.add(into);
            }
            if (set.subtypes.remove(from)) {
                set.subtypes.add(into);
            }
        }

        return into;
    }

    private boolean addGenericSubstitution(GenericParameter from, TypeReference to) {
        if (from == to) {
            throw new IllegalArgumentException("Can't replace generic parameter with itself");
        }

        TypeReference oldValue = genericSubstitutions.put(from, to);
        if (oldValue == to) {
            return false;
        } else if (oldValue != null) {
            throw new IllegalStateException("Tried to replace existing generic substitution");
        }

        Map<TypeReference, TypeReference> mappedTypes = new HashMap<>();
        for (EquivalenceSet equivalenceSet : getEquivalenceSets()) { // TODO: Optimize by keeping a 'GenericParameter -> referring types' map updaed from processObject
            Set<TypeReference> newTypes = new LinkedHashSet<>();
            EquivalenceSet newSet = null;
            boolean changed = false;
            for (TypeReference type : equivalenceSet.types) {
                TypeReference newType = mappedTypes.computeIfAbsent(type, t -> MetadataHelper.substituteGenericArguments(t, genericSubstitutions));
                newTypes.add(newType);

                if (!newType.equals(type)) {
                    // Old type no longer exists
                    equivalences.remove(type);
                    changed = true;
                }

                // Substituting the type may cause the set to now be equal to another set
                if (newSet == null || newSet == equivalenceSet) {
                    newSet = equivalences.get(newType);
                }
            }
            if (!changed) {
                continue;
            }
            equivalenceSet.types = newTypes;

            // Combine sets if generic substitution resulted in an equality
            if (newSet != null && newSet != equivalenceSet) {
                equivalenceSet = mergeSets(newSet, equivalenceSet);
            }

            // Update object to equivalence set map. This is done inside the for
            // loop such that equivalences.get(newType) will correctly return this
            // set if a generic substitution causes two sets to merge into a new set
            // (ex. Map<String, T> and Map<T, String> merge when T -> String).
            for (Object o : equivalenceSet.getObjects()) {
                equivalences.put(o, equivalenceSet);
            }
        }

        return true;
    }

    private EquivalenceSet getEquivalenceSet(Object e) {
        return equivalences.computeIfAbsent(e, k -> {
            EquivalenceSet set = new EquivalenceSet();
            set.add(e);
            return set;
        });
    }

    public boolean add(Object e) {
        if (equivalences.get(e) == null) {
            getEquivalenceSet(e);
            return true;
        } else {
            return false;
        }
    }

    private Object processObject(Object o) {
        if (o instanceof TypeReference) {
            TypeReference type = (TypeReference) o;
            type = MetadataHelper.substituteGenericArguments(type, genericSubstitutions);

            if (types.add(type)) {
                if (type.hasExtendsBound()) {
                    addExtends(type, type.getExtendsBound());
                }

                if (type.hasSuperBound()) {
                    addExtends(type.getSuperBound(), type);
                }

                // TODO: composite types
            }

            return type;
        }

        return o;
    }

    public Set<EquivalenceSet> solve() {
        boolean needsPass = true;
        while (needsPass) {
            findAllConstraints();
            solveAllUnique();
            needsPass = solveAllSingleTypeVariables();
        }

        return getEquivalenceSets();
    }

    private Set<EquivalenceSet> getEquivalenceSets() {
        return new LinkedHashSet<>(equivalences.values());
    }

    private boolean findAllConstraints() {
        boolean changed = false;
        boolean changedThisPass;
        do {
            print("***Finding constraints***");
            changedThisPass = false;
            for (EquivalenceSet type : getEquivalenceSets()) {
                changedThisPass |= type.findConstraints(this);
            }
            changed |= changedThisPass;
        } while (changedThisPass);
        return changed;
    }

    private boolean solveAllUnique() {
        boolean changed = false;
        boolean changedThisPass;
        do {
            print("***Solving unique***");
            changedThisPass = false;
            for (EquivalenceSet type : getEquivalenceSets()) {
                changedThisPass |= type.solveUnique(this);
            }
            changed |= changedThisPass;
        } while (changedThisPass);
        return changed;
    }

    private boolean solveAllSingleTypeVariables() {
        boolean changed = false;
        boolean changedThisPass;
        do {
            print("***Solving multiple***");
            changedThisPass = false;
            for (EquivalenceSet type : getEquivalenceSets()) {
                changedThisPass |= type.solveMultiple(this);
            }
            changed |= changedThisPass;

            if (changedThisPass) {
                findAllConstraints();
                solveAllUnique();
            }
        } while (changedThisPass);
        return changed;
    }

    private static void print(String s) {
        if (InferenceSettings.PRINT) {
            System.out.println(s);
        }
    }
}
